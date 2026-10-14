# 终端 AI 智慧管理平台 · 编排图（M2）
#
# 拓扑：一个 StateGraph，十个节点
#
#      START
#        │
#        ▼
#    supervise（意图识别：chart / replenish / kb 三分类）
#        │
#        ├─(chart)────► chart_plan ─► chart_build ─────────────────────────┐
#        │          （LLM 选维度+样式）（确定性取数+组装图表卡，可切换图型） │
#        │                                                                 │
#        ├─(replenish)─► fetch ─► algorithm ─► report ─► verify_numbers ─┐ │
#        │                （取数）   （算法）    （建议卡）    （数字自检）  │ │
#        │                                                               │ │
#        │     建议卡上的「申请调拨」按钮 ──(suggestion_id)──► order ─────┼─┤
#        │                                    （建单）                    │ │
#        └─(kb)────────► digital_human ───────────────────────────────────┼─┘
#                          （数字员工内核接管问答：RAG + 工具）              ▼
#                                              critic ──(数字不符)──► 回炉（≤2 轮）
#                                                │
#                                                └──(通过/降级)──► respond ──► END
#
# 三条业务路线（用户视角，2026-10-07 与用户对齐）：
#   ① 生成报表：chart_plan（LLM 只选图型：内容维度 + 展示样式，不许写数字）→
#      chart_build（取数→图表卡）；门户卡片头可在「条形/饼图/折线/表格」间即时切换，
#      同一份数据纯前端换图型（不发请求、不重跑 LLM），把图型选择权交给用户
#   ② 推荐补仓形成调拨单：replenish 出建议卡（含「申请调拨」按钮）→
#      **用户点按钮**（带 suggestion_id 再请求）→ order 建单。
#      **建单不是独立意图**：它由按钮触发、确定性直达（route_after_supervise 特判），
#      不问 LLM；「要调拨/建单」类输入统一归入本路线（先建议、后按钮）。
#   ③ 流程问答：digital_human（数字员工内核，含 RAG 与工具生态）
#
# 设计要点（对应开发文档 M2）：
#   1. 自治性按需分配 —— 只有存在真实决策空间的地方才用 LLM：
#      supervise（选路线）、chart_plan（选图型）、report（写总结句）、
#      order（解析话术）、critic（语义判分）；
#      其余全部是确定性代码（fetch 取数、algorithm 计算、chart_build 组装、verify_numbers 比对）。
#   2. 数字红线 —— 所有库存数字来自「查数据库 + 固定算法」，LLM 不生成任何数字。
#      图表卡的数字同样来自取数接口原值搬运，report 之后有 verify_numbers 自检。
#   3. 闭环边界 —— 编排层负责到「调拨单创建成功 + 卡片展示」为止，
#      出库/入库由后端业务接口承担（B 账管接受/拒绝等事务动作直连业务服务，不进编排图），
#      AI 不介入库存事务。
import logging
import re
import sys
import time
import uuid
from datetime import date
from pathlib import Path
from typing import Literal

from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel, Field

sys.path.insert(0, str(Path(__file__).resolve().parent))

from config import settings
from llm import get_llm
from node_params import prompt_for
from state import OrchestratorState
from tools.algorithms import (
    STRATEGY_MIXED,
    STRATEGY_PROCURE_ONLY,
    STRATEGY_TRANSFER_ONLY,
    InventoryRow,
    plan_replenishment,
    summarize_aging,
)
from tools.digital_human_api import DigitalHumanError, digital_human_chat
from tools.java_agent import JavaAgentError, java_rag_agent
from tools.terminal_api import (
    TerminalApiError,
    aggregate_inventory,
    aging_distribution,
    create_transfer_order,
    fetch_snapshot,
    inbound_trend,
    list_warehouses,
)

# 回炉上限：revision_count 达到该值仍不合格 → 降级放行（meta.critic_degraded=true）
MAX_REVISIONS = 2


# ---------------------------------------------------------------------------
# 数据可见范围（2026-10-07 与用户对齐）：本仓 + 所有下级仓（按 parentCode 递归）
#
# 规则：基层用户只能看本仓；上一级可看本仓与全部下级，以此类推（四级组织通用）；
#       无仓库归属的账号（如演示账号）不限制。
# 落点：编排层所有取数（图表 / 快照 / 库龄）与补货建议产出都按可见范围过滤 ——
#       但补货算法本身跑全量（调拨建议天然需要看到可调出仓的存量，见 algorithm 节点注释）。
# ---------------------------------------------------------------------------
_SCOPE_CACHE: dict[str, tuple[float, set | None]] = {}
_SCOPE_TTL_SECONDS = 300


def visible_scope(warehouse_code: str | None) -> set[str] | None:
    """算某用户的可见仓位集合（含自身与所有下级）；None = 不限制。"""
    if not warehouse_code:
        return None
    cached = _SCOPE_CACHE.get(warehouse_code)
    if cached and time.time() - cached[0] < _SCOPE_TTL_SECONDS:
        return cached[1]
    try:
        rows = list_warehouses()
    except TerminalApiError:
        # 取不到层级时按最严口径：只看本仓（宁可少看，不可多看）
        return {warehouse_code}
    children: dict[str, list[str]] = {}
    for w in rows or []:
        children.setdefault(w.get("parentCode") or "", []).append(w.get("warehouseCode") or "")
    scope: set[str] = set()
    stack = [warehouse_code]
    while stack:
        code = stack.pop()
        if not code or code in scope:
            continue
        scope.add(code)
        stack.extend(children.get(code, []))
    _SCOPE_CACHE[warehouse_code] = (time.time(), scope)
    return scope


def _scoped_aging(scope: set[str]) -> list[dict]:
    """按可见仓逐仓取库龄分布并按 band 合并（全量接口不支持按仓筛选）。"""
    merged: dict[str, dict] = {}
    for code in sorted(scope):
        try:
            rows = aging_distribution(code)
        except TerminalApiError:
            continue
        for r in rows or []:
            band = str(r.get("band") or "")
            item = merged.setdefault(band, {"band": band, "cnt": 0, "availableQty": 0,
                                            "sortNo": r.get("sortNo") or 0})
            item["cnt"] += int(r.get("cnt") or 0)
            item["availableQty"] += int(r.get("availableQty") or 0)
    return sorted(merged.values(), key=lambda x: x.get("sortNo") or 0)


def _scope_snapshot(snapshot: dict, scope: set[str]) -> dict:
    """把快照裁剪到可见仓位：明细行过滤 + 汇总数字重算（库龄按可见仓合并）。"""
    rows = [r for r in (snapshot.get("inventory") or [])
            if r.get("warehouseCode") in scope]
    aging = _scoped_aging(scope)
    long_aging = sum(int(b.get("cnt") or 0) for b in aging
                     if b.get("band") in ("180-365", "365+"))
    return {**snapshot,
            "inventory": rows,
            "aging": aging,
            "totalTerminals": sum(int(r.get("totalQty") or 0) for r in rows),
            "totalAvailable": sum(int(r.get("availableQty") or 0) for r in rows),
            "totalInTransit": sum(int(r.get("inTransitQty") or 0) for r in rows),
            "longAgingCount": long_aging}


def _scoped(rows: list[dict], scope: set[str] | None) -> list[dict]:
    """按可见范围过滤明细行（None = 不限制）。"""
    if scope is None:
        return rows
    return [r for r in rows if r.get("warehouseCode") in scope]

# 问答通道的落点（数字员工接管后指向 digital_human）。
# supervise 输出的意图标签仍是 "kb"（不改提示词，减少 LLM 侧不确定性），
# 只在路由映射处改写目标。要回退到旧的 Java RAG agent，把这里改回 "kb" 即可。
KB_CHANNEL = "digital_human"


# ---------------------------------------------------------------------------
# 提示词
# ---------------------------------------------------------------------------

# 只有三类意图：chart / replenish / kb。
# 「建单」不是独立意图 —— 它是补货线末端卡片上的按钮动作（两段式第二段），
# 由 route_after_supervise 的 suggestion_id 特判确定性直达 order 节点，不问 LLM。
ROUTE_PROMPT = (
    "你是「终端 AI 智慧管理平台」的路由器，把用户输入归为三类之一。\n"
    "chart=要看图/要报表（统计图表、各仓分布、库龄分布、型号汇总、"
    "入库趋势/各月入库/月度入库对比等入库量时间序列、趋势/占比/对比等可视化诉求）\n"
    "replenish=要「算一算、给建议」（仓库分析/库存分析/数据分析/补货建议/补仓/"
    "调拨建议/长库龄分析与盘活/该补多少该调多少/生成建议清单），"
    "以及「要调拨/建单」的诉求（会先给出建议卡与「申请调拨」按钮，点按钮才建单）\n"
    "kb=咨询类（流程规则/操作方式/故障排查）或单点查询（某库位、某串码、某状态的终端在哪、"
    "某个仓现在有多少台）\n"
    "分界线：\n"
    "- 「查出来给人看」（某仓现在有多少台、某串码现在在哪）→ kb\n"
    "- 「入库量随时间的变化」（入库趋势、各月入库数据/明细/对比、今年入库多少）→ chart\n"
    "- 「分析后给结论/建议」（仓库分析、库存分析、数据分析、该补多少、哪些该清）→ replenish\n"
    "- 「明确要图表/报表/分布图/可视化」→ chart\n"
    "关键区分：说「分析」默认是要建议 → replenish；"
    "只有明确要「图表 / 报表 / 分布图 / 可视化」才 → chart；"
    "入库量的月度/年度序列一律 → chart（出可切换图型的图表卡）。\n"
    "只输出一个词（chart 或 replenish 或 kb），不要任何其他内容。\n"
    "输入：调拨有哪几种操作\n输出：kb\n"
    "输入：光猫在智家手里但系统显示备机怎么办\n输出：kb\n"
    "输入：西湖营业厅有多少台终端\n输出：kb\n"
    "输入：CPE202600001 这台设备现在在哪\n输出：kb\n"
    "输入：看看各仓库存分布图\n输出：chart\n"
    "输入：库龄分布是什么样的\n输出：chart\n"
    "输入：给我画一下各型号的库存\n输出：chart\n"
    "输入：给我出一份库存报表\n输出：chart\n"
    "输入：查一下各月入库数据明细\n输出：chart\n"
    "输入：最近半年入库趋势\n输出：chart\n"
    "输入：今年入库了多少台\n输出：chart\n"
    "输入：做一个仓库分析\n输出：replenish\n"
    "输入：分析一下当前库存情况\n输出：replenish\n"
    "输入：现在哪些仓位缺 FTTR\n输出：replenish\n"
    "输入：给我一份补货建议\n输出：replenish\n"
    "输入：有哪些终端库龄太长了\n输出：replenish\n"
    "输入：长库龄分析一下，怎么盘活\n输出：replenish\n"
    "输入：帮我从长春调 50 台 F657 到九台\n输出：replenish\n"
    "用户输入：{query}\n输出："
)

# 意图路由已改为纯 LLM 判断（2026-10-08 用户要求「用 AI 识别，不走关键词」）：
# 原先的 _keyword_route 关键词表兜底/覆盖机制已移除 —— 路由质量交给模型，
# 提示词里保留充足示例；如模型判断出现系统性偏差，应改提示词而不是加回关键词。

CHART_PLAN_PROMPT = (
    "你是图表选型助手。根据用户的问题，选择最合适的内容维度与展示样式。\n"
    "内容维度（五选一）：\n"
    "warehouse=各仓库存分布（想看每个仓位库存有多少）\n"
    "aging=库龄分布（想看不同库龄区间的数量）\n"
    "model=按型号汇总（想看各型号库存有多少）\n"
    "stat=整体统计（想看总量、可用、在途、长库龄四个总数）\n"
    "time=入库量时间序列（想看入库趋势、各月/各年入库多少台、入库对比、月度入库走势）\n"
    "展示样式（四选一）：\n"
    "bar=条形图（比大小，默认）\n"
    "pie=饼图（看占比/构成）\n"
    "line=折线图（看趋势/走势）\n"
    "table=表格（看明细/清单）\n"
    "输出格式：「维度 样式」两个词用空格分隔；stat 只输出 stat。不要任何其他内容。\n"
    "输入：各仓库存分布怎么样\n输出：warehouse bar\n"
    "输入：给我看看库龄分布\n输出：aging bar\n"
    "输入：哪些型号库存最多\n输出：model bar\n"
    "输入：现在库存总体情况\n输出：stat\n"
    "输入：给我画个各仓库存占比饼图\n输出：warehouse pie\n"
    "输入：各型号库存构成是什么样\n输出：model pie\n"
    "输入：看看库存趋势\n输出：warehouse line\n"
    "输入：给我一份库存明细表格\n输出：model table\n"
    "输入：最近半年入库趋势\n输出：time line\n"
    "输入：各月入库对比\n输出：time bar\n"
    "输入：查一下今年入库多少台\n输出：time bar\n"
    "用户输入：{query}\n输出："
)

# 图表选型词 → (展示名, 卡片构建函数在 chart_build 里按 kind 分派)
CHART_KINDS = ("warehouse", "aging", "model", "stat", "time")
CHART_STYLES = ("bar", "pie", "line", "table")  # 展示样式；stat 恒为 metric 统计卡
CHART_TOP_N = 10  # 分布类图表最多画前 N 项，避免仓位/型号过多时图不可读

# 样式词 → 中文展示名（选型日志与门户卡片头切换按钮共用同一口径）
CHART_STYLE_LABEL = {"bar": "条形图", "pie": "饼图", "line": "折线图",
                     "table": "表格", "metric": "统计卡"}

# 样式关键词兜底（模型没给样式时按用户原话定）：与 _keyword_route 同思路，
# 只认高置信词，避免误伤；未命中由调用方定默认。
_STYLE_HINTS = (
    ("pie", ("饼图", "占比", "构成", "比例", "份额")),
    ("line", ("折线", "趋势", "走势")),
    ("table", ("表格", "明细", "清单", "列表")),
    ("bar", ("柱状", "条形", "对比图", "排名")),
)

# 维度关键词表（2026-10-10 性能修复）：图表选型改为「规则优先 + LLM 兜底」。
# 背景：选型本质是 5 选 1 的分类，规则即可命中绝大多数问法；原先 LLM 优先 +
# 静默重试 3 次，实测一次提供方抖动把整条图表链路拖到 301.8 秒
# （17:15:45 的调用花了 293 秒，重试又被 except:pass 吞掉，日志零痕迹）。
# 规则命中则完全不烧 LLM（<1ms）。顺序按具体度排列：time 最具体放最前，
# warehouse 的「库存分布」最泛放最后（避免"各型号库存"误命中）。
_KIND_HINTS = (
    ("time", ("入库趋势", "入库走势", "各月入库", "月度入库", "入库对比", "入库数据明细",
              "各年入库", "入库多少台", "入库量", "入库时间", "今年入库", "最近半年入库")),
    ("aging", ("库龄分布", "库龄区间", "库龄结构", "库龄")),
    ("model", ("各型号", "型号汇总", "型号分布", "型号库存", "型号构成", "型号排行", "按型号")),
    ("stat", ("总体情况", "整体统计", "库存总量", "总库存", "总台数", "整体情况", "库存统计")),
    ("warehouse", ("各仓", "仓位分布", "仓库分布", "库存分布", "仓位库存", "各仓位")),
)


def _keyword_style(query: str) -> str | None:
    """从用户原话里提取展示样式（高置信词）；未命中返回 None。"""
    q = (query or "").strip()
    if not q:
        return None
    for style, words in _STYLE_HINTS:
        if any(w in q for w in words):
            return style
    return None


class RouteDecision(BaseModel):
    """路由决策（保留给需要结构化输出的调用方；supervise 现走文本解析）。"""

    route: Literal["chart", "replenish", "order", "kb"] = Field(
        description="路线：chart=生成报表，replenish=补货建议（含建单诉求），"
                    "order=建单落点（按钮触发，不由意图识别产生），kb=流程问答"
    )
    reason: str = Field(description="一句话路由依据")


REPORT_SUMMARY_PROMPT = (
    "你是库存分析助手。下面是**已经算好的**补货分析结果，请写一句面向管理者的总结。\n"
    "硬规则：只能复述下面给出的数字，不得新增、推算或估算任何数字。\n"
    "策略：{strategy}\n"
    "调拨建议：{transfer_count} 条，合计 {transfer_qty} 台\n"
    "采购建议：{procure_count} 条，合计 {procure_qty} 台\n"
    "涉及型号：{models}\n"
    "长库龄台数：{long_aging}\n"
    "请输出一句话（不超过 60 字），口吻简洁专业，点出「优先调拨、不足转采购」的思路。"
)

ORDER_PARSE_PROMPT = (
    "你是调拨单解析助手。把用户的一句话指令解析成结构化建单参数。\n"
    "已知仓位（编码 名称）：\n{warehouses}\n"
    "已知型号（型号 → 物料编码）：\n{models}\n"
    "用户指令：{query}\n"
    "要求：\n"
    "- operationType 只能是 TRANSFER / ISSUE / RECALL / RETURN，默认 TRANSFER\n"
    "- fromWarehouse / toWarehouse 必须用上面给出的仓位编码，不要自己编\n"
    "- items 每项要有 materialCode、model、quantity\n"
    "- 信息不足（缺仓位/型号/数量）时把 needMoreInfo 设为 true 并说明缺什么"
)


class OrderItem(BaseModel):
    materialCode: str = Field(description="物料编码")
    model: str = Field(description="规格型号")
    quantity: int = Field(description="调拨数量")


class OrderIntent(BaseModel):
    """建单智能体的结构化解析结果。"""

    operationType: Literal["TRANSFER", "ISSUE", "RECALL", "RETURN"] = Field(
        description="操作类型，默认 TRANSFER"
    )
    fromWarehouse: str = Field(description="调出仓位编码")
    toWarehouse: str = Field(description="调入仓位编码")
    items: list[OrderItem] = Field(description="调拨明细")
    needMoreInfo: bool = Field(description="信息是否不足")
    message: str = Field(description="信息不足时说明缺什么")


CRITIC_PROMPT = (
    "用户查询：「{query}」\n助手回答：「{answer}」\n"
    "请判断该回答是否有效回答了查询：合格标准是内容与查询相关、非空泛、"
    "不是拒答或答非所问；不合格时给出一句话整改建议。"
)


class CriticVerdict(BaseModel):
    """critic 的结构化判分输出。"""

    passed: bool = Field(description="回答是否合格")
    reason: str = Field(description="判定理由；不合格时给出具体整改建议")


# ---------------------------------------------------------------------------
# 建议缓存（两段式人工确认）
#
# 第一轮输出建议卡片 + suggestion_id，用户点「申请调拨」后第二轮带 id 回来建单。
# 生产环境应放 Redis（30 分钟 TTL）；当前用进程内字典，重启即失效 —— 对这个规模够用，
# 且不引入额外中间件依赖。替换成 Redis 时只改下面两个函数。
# ---------------------------------------------------------------------------
_SUGGESTIONS: dict[str, dict] = {}
_SUGGESTION_TTL_SECONDS = 30 * 60


def save_suggestion(payload: dict) -> str:
    """存一份建议快照，返回 suggestion_id。"""
    sid = "sug_" + uuid.uuid4().hex[:12]
    _SUGGESTIONS[sid] = {**payload, "_saved_at": time.time()}
    now = time.time()
    for key in [k for k, v in list(_SUGGESTIONS.items())
                if now - v.get("_saved_at", 0) > _SUGGESTION_TTL_SECONDS]:
        _SUGGESTIONS.pop(key, None)
    return sid


def load_suggestion(sid: str) -> dict | None:
    """按 id 取建议快照（order 通道用）；过期返回 None。"""
    got = _SUGGESTIONS.get(sid)
    if not got:
        return None
    if time.time() - got.get("_saved_at", 0) > _SUGGESTION_TTL_SECONDS:
        _SUGGESTIONS.pop(sid, None)
        return None
    return got


# ---------------------------------------------------------------------------
# 节点 1/N：supervise —— 意图识别（LLM 结构化分类，temperature=0）
# ---------------------------------------------------------------------------

def supervise(state: OrchestratorState) -> dict:
    """判断走哪条路线（chart / replenish / kb）。

    「专职 LLM 节点 + 结构化输出 + 条件边」三件套，不是新 agent ——
    决策点只有一个（选路线），用最小成本的确定性分类解决。

    路由方式（2026-10-08 用户要求「意图识别用 AI，不走关键词」）：
    **纯 LLM 判断** —— 提示词要求只输出一个词，本地做包含匹配（防模型自由发挥）；
    不再有关键词表兜底/覆盖（此前 _keyword_route 会覆盖模型结果）。
    连续失败时才降级 kb；路由依据原样透出，便于观察模型判断质量。
    """
    start = time.perf_counter()
    meta = state.get("meta") or {}
    # 两段式第二段短路（2026-10-08）：请求带 suggestion_id = 用户点了建议卡按钮，
    # 是**协议动作**不是自然语言，落点是确定性的 order —— 不烧 LLM。
    # 实测 glm-5.2 路由一次 8~17s 且按钮点击根本不需要意图识别，
    # 这步曾是「申请调拨」提交慢的唯一元凶（真正建单只要 0.06s）。
    if meta.get("suggestion_id") or state.get("suggestion_id"):
        duration = int((time.perf_counter() - start) * 1000)
        get_stream_writer()({"seq": 0, "text": "[路由] order：建议凭据直达建单（免 LLM）"})
        return {"route": "order",
                "meta": {"route": "order", "route_reason": "suggestion_id 直达建单（免 LLM）",
                         "router_duration_ms": duration}}
    route, reason = "kb", "路由连续失败，降级到知识问答"
    last_error = ""
    for attempt in range(1, 4):
        try:
            raw = get_llm(temperature=0, user_id=meta.get("user_id"),
                          agent_code="intent_router").invoke(
                prompt_for("route", ROUTE_PROMPT).format(query=state["query"])).content.strip().lower()
            # 长词优先匹配，避免输出里带杂质时误判（如 "kbb"）
            matched = next((w for w in ("replenish", "chart", "kb") if w in raw), None)
            if not matched and "order" in raw:
                # 兼容：模型偶尔仍吐旧标签 order —— 建单不是独立意图，归入补货线
                # （先给建议卡与按钮，点按钮才走两段式建单）
                matched = "replenish"
                raw = "order（归一为 replenish）"
            if matched:
                route = matched
                reason = raw[:60]
                break
            last_error = f"第{attempt}次无法解析：{raw[:40]!r}"
        except Exception as exc:  # noqa: BLE001
            last_error = f"第{attempt}次异常：{type(exc).__name__}"
    duration = int((time.perf_counter() - start) * 1000)
    if route == "kb" and last_error:
        reason = f"路由降级（{last_error}）"
    get_stream_writer()({"seq": 0, "text": f"[路由] {route}：{reason}"})
    return {"route": route,
            "meta": {"route": route, "route_reason": reason,
                     "router_duration_ms": duration}}


def route_after_supervise(state: OrchestratorState) -> str:
    """条件边：三意图分流 + 一个确定性落点。

    - 两段式第二段：请求带 suggestion_id = 用户点了建议卡上的「申请调拨」按钮
      → 直接去 order 建单节点（**建单不是意图**，是补货线末端的按钮动作）；
    - 问答意图（"kb"）改写为 KB_CHANNEL 指向的节点，实现「数字员工接管问答」
      而无需改动路由提示词。
    """
    meta = state.get("meta") or {}
    if meta.get("suggestion_id") or state.get("suggestion_id"):
        return "order"
    route = state.get("route") or "kb"
    if route == "kb":
        return KB_CHANNEL
    return route


# ---------------------------------------------------------------------------
# 节点 2/8：kb_node —— 知识库问答（纯转发到 Java 侧 RAG 管线）
# ---------------------------------------------------------------------------

def kb_node(state: OrchestratorState) -> dict:
    """转发给 Java 侧 /oa/chat/sync。

    为什么不在这里重建检索：Java 侧已有五轮迭代验证过的 RAG 全管线
    （文档加载 → embedding → pgvector HNSW → 查询重写+分类 filter → 增强问答），
    重写一遍既浪费又更差。本节点只做协议适配 + 失败降级。
    """
    start = time.perf_counter()
    query = state["query"]
    # conversationId 必须传：Java 侧 SelectiveMemoryAdvisor 的 param 不接受 null
    # （实测报 "value cannot be null"），server.py 已在入口生成并放进 meta
    conversation_id = (state.get("meta") or {}).get("conversation_id")
    try:
        answer = java_rag_agent(query, conversation_id=conversation_id)
    except JavaAgentError as exc:
        answer = f"知识库暂时不可用（{exc}）"
    duration = int((time.perf_counter() - start) * 1000)
    get_stream_writer()({"seq": 1, "text": answer})
    return {"rewritten_query": query,
            "agent_response": answer,
            "meta": {"kb_duration_ms": duration, "channel": "kb"}}


# ---------------------------------------------------------------------------
# 节点 2b/9：digital_human —— 数字员工内核（接管问答）
# ---------------------------------------------------------------------------

def digital_human_node(state: OrchestratorState) -> dict:
    """转发给数字员工内核 aismp-digital-human（Spring Boot 4 + Spring AI 2.0 + SAA）。

    与 kb_node 的取舍相同：不在编排层重建检索/工具链，只做协议适配 + 失败降级。
    差别在于数字员工是「配置驱动」的 —— 人设、技能、工具都在库里，
    这里只传 agent_code，装配由内核的 AgentRegistry 负责。

    meta 里带 version 是为了可追溯：事后能确认这次回答用的是哪一版配置。
    """
    start = time.perf_counter()
    query = state["query"]
    meta = state.get("meta") or {}
    agent_code = meta.get("agent_code") or settings.DIGITAL_HUMAN_AGENT_CODE
    # 数据可见范围（2026-10-07）：知识问答同样收口 —— 内核据此加【数据权限】片段
    # 并在工具层拦截越权仓位（见 kernel 的 ApiCallToolCallback）
    scope = visible_scope(meta.get("warehouse_code"))

    agent_version = None
    try:
        # conversationId 透传：内核有自己的会话表 + 选择性记忆顾问 ——
        # 同一会话里的追问能接上上下文（编排层的历史在 orch_message，两边同 id 对齐）
        result = digital_human_chat(agent_code, query,
                                    data_scope=sorted(scope) if scope is not None else None,
                                    conversation_id=meta.get("conversation_id"))
        answer = result.get("answer") or ""
        agent_version = result.get("version")
    except DigitalHumanError as exc:
        answer = f"数字员工暂时不可用（{exc}）"

    duration = int((time.perf_counter() - start) * 1000)
    get_stream_writer()({"seq": 1, "text": answer})
    return {"rewritten_query": query,
            "agent_response": answer,
            "meta": {"digital_human_duration_ms": duration,
                     "channel": "digital_human",
                     "agent_code": agent_code,
                     "agent_version": agent_version,
                     "kb_scope": sorted(scope) if scope is not None else None}}


# ---------------------------------------------------------------------------
# 图表路线（新增，2026-10-07）：chart_plan 选图型 → chart_build 取数组装
#
# 与补仓路线的取舍一致：LLM 的决策空间只有「画哪种图（维度+样式）」，
# 数字全部来自取数接口（aggregate / aging / byModel / snapshot）的原值搬运。
# 图表卡契约（门户照此渲染）：
#   {"type": "chart", "chartType": "bar"|"pie"|"line"|"table"|"metric", "title": "...",
#    "labels": [...], "series": [{"name": "...", "data": [...]}], "note": "..."}
# 图型选择权（2026-10-07 与用户对齐）：后端只定「初始样式」，
# 门户卡片头提供「条形|饼图|折线|表格」切换按钮 —— 同一份 labels/series 纯前端换图，
# 不再发请求、不重跑 LLM（数据仍是接口原值，换的只是呈现方式）。
# ---------------------------------------------------------------------------

CHART_KIND_LABEL = {
    "warehouse": "各仓库存分布",
    "aging": "库龄分布",
    "model": "型号汇总",
    "stat": "整体统计",
    "time": "入库时间趋势",
}


def chart_plan(state: OrchestratorState) -> dict:
    """图表选型：**规则优先 + LLM 兜底**（2026-10-10 性能修复）。

    选型本质是「5 维度 × 4 样式」的分类，规则即可命中绝大多数问法 ——
    命中则完全不烧 LLM（<1ms）。原先 LLM 优先 + 静默重试 3 次，实测一次
    提供方抖动把整条图表链路拖到 301.8 秒（17:15:45 的调用花了 293 秒，
    重试又被 except:pass 吞掉，日志零痕迹，排障无从下手）。

    现在：
      ① 规则命中 → 直接返回（<1ms）；
      ② 未命中才走 LLM，最多 2 次、间隔 2s，失败/不匹配都记 warning（带原始输出）；
      ③ 全部失败降级 stat（统计卡只用一次快照调用，最稳）。
    """
    start = time.perf_counter()
    query = state["query"]
    log = logging.getLogger("orchestrator.graph")

    # ① 规则优先：命中即返回，不烧 LLM
    kind = next((k for k, words in _KIND_HINTS if any(w in query for w in words)), None)
    if kind:
        style = "metric" if kind == "stat" else (_keyword_style(query) or "bar")
        reason = f"规则命中（{query[:30]}）"
        duration = int((time.perf_counter() - start) * 1000)
        get_stream_writer()({"seq": 0, "text":
            f"[图表选型] {CHART_KIND_LABEL[kind]}·{CHART_STYLE_LABEL[style]}（{reason}）"})
        return {"chart_plan": {"kind": kind, "style": style, "reason": reason},
                "meta": {"chart_kind": kind, "chart_style": style,
                         "chart_plan_duration_ms": duration, "channel": "chart"}}

    # ② LLM 兜底：最多 2 次、间隔 2s、全程留痕
    kind, style, reason = "stat", "metric", "选型连续失败，降级整体统计"
    for attempt in range(1, 3):
        try:
            raw = get_llm(temperature=0, user_id=(state.get("meta") or {}).get("user_id"),
                          agent_code="chart_planner").invoke(
                prompt_for("chart_plan", CHART_PLAN_PROMPT).format(query=query)).content.strip().lower()
            matched = next((w for w in CHART_KINDS if w in raw), None)
            if matched:
                kind = matched
                picked = (next((s for s in CHART_STYLES if s in raw), None)
                          or _keyword_style(query) or "bar")
                # stat 是四个总数，没有分布形态 —— 样式固定统计卡，忽略模型给的 pie/line…
                style = "metric" if kind == "stat" else picked
                reason = raw[:60]
                break
            # 模型给了输出但不含任何维度词 —— 原先这里静默重试，现在必须留痕
            log.warning("[图表选型] 第 %d 次输出不匹配维度词，raw=%r", attempt, raw[:80])
        except Exception as exc:  # noqa: BLE001 —— 选型失败不阻塞，走降级
            log.warning("[图表选型] 第 %d 次调用失败: %s", attempt, exc)
        if attempt < 2:
            time.sleep(2)
    duration = int((time.perf_counter() - start) * 1000)
    get_stream_writer()({"seq": 0, "text":
        f"[图表选型] {CHART_KIND_LABEL[kind]}·{CHART_STYLE_LABEL[style]}（{reason}）"})
    return {"chart_plan": {"kind": kind, "style": style, "reason": reason},
            "meta": {"chart_kind": kind, "chart_style": style,
                     "chart_plan_duration_ms": duration, "channel": "chart"}}


def _chart_rows_to_bar(rows: list[dict], key: str, name_key: str,
                       top_n: int = CHART_TOP_N) -> tuple[list[str], list[int], int, int]:
    """把明细行按 key 聚合求和：返回 (标签, 数据, 组数, 合计)。

    统一用**可用库存（availableQty）**口径；排序取前 top_n，避免图不可读。
    """
    agg: dict[str, dict] = {}
    for r in rows:
        k = str(r.get(key) or "")
        if not k:
            continue
        item = agg.setdefault(k, {"name": r.get(name_key) or k, "qty": 0})
        item["qty"] += int(r.get("availableQty") or 0)
    ordered = sorted(agg.values(), key=lambda x: -x["qty"])[:top_n]
    return ([str(x["name"]) for x in ordered],
            [int(x["qty"]) for x in ordered],
            len(agg),
            sum(int(x["qty"]) for x in agg.values()))


def chart_build(state: OrchestratorState) -> dict:
    """按选型取数并组装图表卡（确定性，无 LLM）。

    与 report 节点相同的失败纪律：取数失败/getdata 为空 → 明确说"没取到"，
    绝不渲染一张空图让人误以为库存为零。
    """
    start = time.perf_counter()
    plan = state.get("chart_plan") or {}
    kind = plan.get("kind") or "stat"
    style = plan.get("style") or ("metric" if kind == "stat" else "bar")
    scope = visible_scope((state.get("meta") or {}).get("warehouse_code"))
    try:
        card, note = _build_chart(kind, style, scope, state.get("query") or "")
        if scope is not None:
            note += "（数据范围：本仓及下级仓）"
            card["note"] = note
    except TerminalApiError as exc:
        duration = int((time.perf_counter() - start) * 1000)
        text = "图表数据获取失败，暂时无法绘图（库存服务不可达，请确认服务已启动后重试）。"
        get_stream_writer()({"seq": 1, "text": text})
        return {"cards": [], "agent_response": text,
                "meta": {"chart_duration_ms": duration, "chart_ok": False,
                         "chart_error": str(exc)}}

    if not card.get("labels"):
        duration = int((time.perf_counter() - start) * 1000)
        text = "当前没有可用于绘图的数据（查询结果为空）。"
        get_stream_writer()({"seq": 1, "text": text})
        return {"cards": [], "agent_response": text,
                "meta": {"chart_duration_ms": duration, "chart_ok": False,
                         "chart_error": "empty"}}

    duration = int((time.perf_counter() - start) * 1000)
    get_stream_writer()({"seq": 1, "text": f"[图表] {note}"})
    return {"cards": [card], "agent_response": note,
            "meta": {"chart_duration_ms": duration, "chart_ok": True}}


def _build_chart(kind: str, style: str = "bar",
                 scope: set[str] | None = None,
                 query: str = "") -> tuple[dict, str]:
    """取数 + 组装卡片 + 写一句确定性说明（note）。数字全部为接口原值。

    样式只决定卡片的初始 chartType（门户可在卡片头即时切换同一份数据）；
    stat 恒为统计卡（四个总数没有分布形态，不参与样式切换）。
    取数口径：scope 非空时按可见范围过滤（本仓 + 下级仓）；
    型号汇总改由聚合明细本地汇总 —— 全量的 byModel 接口没有仓位维度，过滤不了。
    """
    if kind == "time":
        # 时间序列：标签按时间升序、桶全量展示，不走 Top N 合并（时间轴砍尾巴没有意义）
        return _build_time_chart(style, scope, query)

    if kind == "stat":
        # 一次快照拿四个总数（快照本身就是一致性视图）
        snap = fetch_snapshot()
        if scope is not None:
            snap = _scope_snapshot(snap, scope)
        labels = ["终端总数", "可用", "在途", "长库龄"]
        data = [int(snap.get("totalTerminals") or 0), int(snap.get("totalAvailable") or 0),
                int(snap.get("totalInTransit") or 0), int(snap.get("longAgingCount") or 0)]
        note = (f"终端总数 {data[0]} 台，可用 {data[1]} 台，"
                f"在途 {data[2]} 台，长库龄 {data[3]} 台")
        return _chart_card("库存整体统计", "metric", labels, data, note, "台数"), note

    if kind == "warehouse":
        labels, data, groups, total = _chart_rows_to_bar(
            _scoped(aggregate_inventory(), scope), "warehouseCode", "warehouseName")
        title = "各仓可用库存分布"
        note = (f"共 {groups} 个仓位，可用库存合计 {total} 台"
                + (f"，最高为 {labels[0]}（{data[0]} 台）" if labels else ""))
    elif kind == "model":
        labels, data, groups, total = _chart_rows_to_bar(
            _scoped(aggregate_inventory(), scope), "model", "model")
        title = "各型号可用库存汇总"
        note = (f"共 {groups} 个型号，可用库存合计 {total} 台"
                + (f"，最高为 {labels[0]}（{data[0]} 台）" if labels else ""))
    else:  # aging
        rows = sorted(_scoped_aging(scope) if scope is not None else aging_distribution(),
                      key=lambda r: r.get("sortNo") or 0)
        labels = [str(r.get("band") or "") for r in rows]
        data = [int(r.get("cnt") or 0) for r in rows]
        groups, total = len(labels), sum(data)
        title = "终端库龄分布"
        long_aging = sum(n for b, n in zip(labels, data) if b in ("180-365", "365+"))
        note = f"共 {sum(data)} 台在统计范围内，其中 180 天以上长库龄 {long_aging} 台"

    labels, data = _merge_tail(labels, data, groups, total)
    if style == "line":
        # 数据里没有时间维度：折线是「按值降序」的分布形态，必须在 note 里如实说明
        note += "（折线为按值降序的分布走势，非时间序列）"
    return _chart_card(title, style, labels, data, note), note


# ---------------------------------------------------------------------------
# 时间维度（2026-10-08）：入库趋势 / 月度对比 —— 图表路线的第五个内容维度
#
# 数据源：agent 原子接口 /inventory/inboundTrend（按首次入库时间归桶，totalCnt=各桶之和）。
# 时间范围由**确定性关键词**解析（今年/去年/近N月/按年），不问 LLM ——
# 选型师只管选「维度 样式」，范围解析是可判定的规则活，放进提示词只会引入不确定性。
# 未命中任何时间词时默认「最近 6 个月」按月分桶。
# ---------------------------------------------------------------------------

def _shift_months(d: "date", n: int) -> "date":
    """d 所在月份往前推 n 个月的第一天（纯日历运算，不引第三方依赖）。"""
    total = d.year * 12 + (d.month - 1) - n
    return date(total // 12, total % 12 + 1, 1)


def _time_range(query: str) -> tuple[str, str | None, str | None]:
    """从用户话术解析时间范围：返回 (粒度, from, to)。只认高置信词，未命中默认近 6 个月。

    中文数字只支持单字（近三年→近3年、近三个月→近3个月）、「半年」按 6 个月折算；
    日粒度需要显式日期且后端限跨度 ≤366 天，聊天路线不开放（避免解析歧义）。
    """
    q = (query or "").strip()
    # 短语归一在单字归一之前：「近半年」→「近6个月」（防止 半→6 后被误配成年粒度）
    q = q.replace("半年", "6个月")
    # 中文数字归一（单字替换）：让下面的数字正则同时命中「近三年」「近三个月」这类口语
    q = "".join({"一": "1", "两": "2", "二": "2", "三": "3", "四": "4", "五": "5",
                 "六": "6", "七": "7", "八": "8", "九": "9"}.get(ch, ch)
                for ch in q)
    today = date.today()
    y = today.year

    m = re.search(r"近(\d+)年|最近(\d+)年", q)
    if m or any(w in q for w in ("按年", "年度", "各年")):
        n = int(next(g for g in m.groups() if g)) if m else 3
        return "year", f"{y - n + 1}-01-01", today.isoformat()
    if "去年" in q:
        return "month", f"{y - 1}-01-01", f"{y - 1}-12-31"
    if "今年" in q:
        return "month", f"{y}-01-01", today.isoformat()

    m = re.search(r"近(\d+)\s*个?月|最近(\d+)\s*个?月", q)
    if m:
        n = int(next(g for g in m.groups() if g))
        return "month", _shift_months(today, n - 1).isoformat(), today.isoformat()

    return "month", _shift_months(today, 5).isoformat(), today.isoformat()


def _build_time_chart(style: str, scope: set[str] | None,
                      query: str) -> tuple[dict, str]:
    """入库时间序列出卡：labels=时间桶（升序），data=各桶入库台数（接口原值）。

    可见范围：inboundTrend 只收单仓参数，带 scope 时改调 groupBy=warehouse
    再按 groupKey 过滤聚合 —— 与其他维度的"取数全量、产出收口"同一纪律。
    """
    granularity, frm, to = _time_range(query)
    trend = inbound_trend(granularity=granularity, from_date=frm, to_date=to,
                          group_by=("warehouse" if scope is not None else "none"))
    rows = trend.get("rows") or []
    if scope is not None:
        agg: dict[str, int] = {}
        for r in rows:
            if r.get("groupKey") in scope:
                p = str(r.get("period") or "")
                agg[p] = agg.get(p, 0) + int(r.get("cnt") or 0)
        periods = sorted(agg)
        data = [agg[p] for p in periods]
        total = sum(data)
    else:
        periods = [str(r.get("period") or "") for r in rows]
        data = [int(r.get("cnt") or 0) for r in rows]
        total = int(trend.get("totalCnt") or sum(data))

    unit = "年" if granularity == "year" else "月"
    title = f"入库量趋势（按{unit}）"
    note = f"统计范围 {frm or '最早'} ~ {to or '今'}，共入库 {total} 台（{len(periods)} 个{unit}，按首次入库时间归桶）"
    if data and (peak := max(data)):
        note += f"，最高为 {periods[data.index(peak)]}（{peak} 台）"
    return _chart_card(title, style, periods, data, note, "入库台数"), note


def _merge_tail(labels: list[str], data: list[int], groups: int,
                total: int) -> tuple[list[str], list[int]]:
    """把 Top N 之外的尾巴合并为「其他」一项。

    只画前 N 项时，饼图/表格的占比会失真（分母应含全集）；
    合并后各项之和 == 接口原值合计，各图型（含用户切换后的）共用同一份口径一致的数据。
    """
    if groups > len(labels):
        rest = total - sum(data)
        if rest > 0:
            labels = labels + ["其他"]
            data = data + [int(rest)]
    return labels, data


def _chart_card(title: str, chart_type: str, labels: list[str], data: list[int],
                note: str, series_name: str = "可用库存(台)") -> dict:
    """图表卡契约（门户照此渲染）：chartType 定初始图型，labels/series 各图型通用。"""
    return {
        "type": "chart",
        "chartType": chart_type,
        "title": title,
        "labels": labels,
        "series": [{"name": series_name, "data": data}],
        "note": note,
    }


# ---------------------------------------------------------------------------
# 节点 3/8：fetch —— 取数
# ---------------------------------------------------------------------------

def fetch(state: OrchestratorState) -> dict:
    """取库存数据。

    为什么不让 LLM 决定"先查哪个再查哪个"：库存聚合、库龄分布、型号汇总
    本来就是同一时刻的一致视图，拆成多轮只会引入时间差（导致"可用+在途"对不上）。
    这里没有决策空间，所以用确定性代码而非 agent 循环 ——
    符合"能用 workflow 就不用自主 agent"的取舍。
    """
    start = time.perf_counter()
    scope = visible_scope((state.get("meta") or {}).get("warehouse_code"))
    try:
        snapshot = fetch_snapshot()
        if scope is not None:
            # 汇总与库龄用可见范围口径（展示给用户看）；
            # **明细行保留全量** —— 补货算法需要跨仓可比才能找到可调出仓，
            # 产出再按可见范围过滤（见 algorithm 节点）。
            scoped = _scope_snapshot(snapshot, scope)
            snapshot = {**scoped, "inventory": snapshot.get("inventory") or []}
        ok, err = True, None
    except TerminalApiError as exc:
        snapshot, ok, err = {}, False, str(exc)

    duration = int((time.perf_counter() - start) * 1000)
    if ok:
        text = (f"[取数] 终端总数 {snapshot.get('totalTerminals', 0)}，"
                f"可用 {snapshot.get('totalAvailable', 0)}，"
                f"在途 {snapshot.get('totalInTransit', 0)}，"
                f"长库龄 {snapshot.get('longAgingCount', 0)}"
                + ("（数据范围：本仓及下级仓）" if scope is not None else ""))
    else:
        text = f"[取数失败] {err}"
    get_stream_writer()({"seq": 1, "text": text})

    return {"inventory_snapshot": snapshot,
            "meta": {"fetch_duration_ms": duration, "fetch_ok": ok,
                     "fetch_error": err, "channel": "replenish",
                     "scope": sorted(scope) if scope is not None else None}}


# ---------------------------------------------------------------------------
# 节点 4/8：algorithm —— 算法（LLM 选路 + 确定性工具箱执行）
# ---------------------------------------------------------------------------

def algorithm(state: OrchestratorState) -> dict:
    """跑补货算法。

    "算法即工具"的落地：LLM 的决策空间只有「跑哪几种分析、用什么参数」，
    计算全部在 tools/algorithms.py 里（纯函数、12 个单测覆盖）。
    当前数据源没有用量流水，默认启用分布口径；
    开发文档 B 附录的报表口径函数（weekly_usage / waterline / gap）已备好，
    补上流水表即可切换。
    """
    start = time.perf_counter()
    snapshot = state.get("inventory_snapshot") or {}
    scope = visible_scope((state.get("meta") or {}).get("warehouse_code"))

    rows = [InventoryRow.from_dict(r) for r in (snapshot.get("inventory") or [])]
    plan = plan_replenishment(rows)

    # 可见范围过滤（2026-10-07）：算法跑全量（调拨建议天然要看到可调出仓的存量，
    # 否则基层用户找不到货源），但**产出只保留与本仓/下级仓相关的行** ——
    # 任一端命中可见范围即保留（调入要确认、调出也要本仓放行）。
    if scope is not None:
        plan.transfers = [t for t in plan.transfers
                          if t.fromWarehouse in scope or t.toWarehouse in scope]
        plan.procurements = [p for p in plan.procurements
                             if not p.toWarehouse or p.toWarehouse in scope]
        has_t, has_p = bool(plan.transfers), bool(plan.procurements)
        plan.strategy = (STRATEGY_MIXED if (has_t and has_p)
                         else STRATEGY_PROCURE_ONLY if has_p else STRATEGY_TRANSFER_ONLY)

    aging = summarize_aging(snapshot.get("aging") or [])

    result = {
        **plan.to_dict(),
        "agingSummary": aging,
        "totalAvailable": snapshot.get("totalAvailable", 0),
        "totalInTransit": snapshot.get("totalInTransit", 0),
        "longAgingCount": snapshot.get("longAgingCount", 0),
    }
    duration = int((time.perf_counter() - start) * 1000)
    get_stream_writer()({
        "seq": 2,
        "text": f"[算法] 策略={plan.strategy}，调拨 {len(plan.transfers)} 条、"
                f"采购 {len(plan.procurements)} 条"
                f"（长库龄占比 {aging['longAgingRatio']:.1%}）",
    })
    return {"algo_result": result,
            "strategy": plan.strategy,
            "meta": {"algorithm_duration_ms": duration, "channel": "replenish",
                     "algo_trace": plan.trace}}


# ---------------------------------------------------------------------------
# 节点 5/8：report —— 装配卡片（数字原值搬运，不做算术）
# ---------------------------------------------------------------------------

def report(state: OrchestratorState) -> dict:
    """把算法结果装配成前端能直接渲染的富卡片。

    卡片类型遵循 docs/contracts 的约定：
        replenish_reminder / transfer_list / procurement_list
    所有数字都是 algo_result 里对应字段的**原值搬运**，本节点不做任何算术 ——
    这样 critic 的 diff 闸门（卡片数字 === 算法输出）才有意义。
    """
    start = time.perf_counter()

    # 取数失败必须短路：空快照会被算法算成「0 条建议」，再交给 LLM 措辞，
    # 就会写出「库存均衡、暂无需操作」—— 把「数据没取到」误报成「库存健康」，
    # 这是最危险的一类误导（用户会据此认为无需行动）。所以此时不让 LLM 介入。
    if (state.get("meta") or {}).get("fetch_ok") is False:
        err = (state.get("meta") or {}).get("fetch_error") or "未知原因"
        duration = int((time.perf_counter() - start) * 1000)
        # 对用户只说结论，不暴露内部接口路径；原始错误进 meta 供排障
        text = "库存数据获取失败，暂时无法给出补货建议（库存服务不可达，请确认服务已启动后重试）。"
        get_stream_writer()({"seq": 3, "text": text})
        return {
            "cards": [{
                "type": "replenish_unavailable",
                "title": "补货建议暂不可用",
                "detail": "库存数据未取到，本次未生成任何建议",
                "actions": [],
            }],
            "agent_response": text,
            "meta": {"report_duration_ms": duration,
                     "channel": "replenish",
                     "report_skipped_reason": "fetch_failed",
                     "fetch_error": err},
        }

    algo = state.get("algo_result") or {}
    strategy = state.get("strategy") or "transfer_only"
    transfers = algo.get("transfers") or []
    procurements = algo.get("procurements") or []

    # 快照（可见范围口径）：提醒卡的 stats 用（2026-10-10）
    snapshot = state.get("inventory_snapshot") or {}

    transfer_qty = sum(int(t.get("quantity") or 0) for t in transfers)
    procure_qty = sum(int(p.get("suggestedQty") or 0) for p in procurements)
    models = sorted({t.get("model") for t in transfers if t.get("model")}
                    | {p.get("model") for p in procurements if p.get("model")})

    cards: list[dict] = []

    # 1) 补货提醒卡（总是有，作为对话流入口）
    # stats（2026-10-10 需求）：前端要在卡片里展示「当前库存状况」分析。
    # 这里**只给数据不给文案** —— 文案由前端拼。原因：实测（2026-10-10 16:47）
    # 在 report 节点改 REPORT_SUMMARY_PROMPT 后整条流程会卡在 LLM 总结调用上
    # （algorithm 完成后 3.5 分钟无进展，回滚即恢复），原因未明；绕开它最稳。
    # 数字全部来自算法输出/快照，不做推算 —— 遵守数字红线。
    cards.append({
        "type": "replenish_reminder",
        "title": "小智补货提醒",
        "detail": (f"共需补货 {transfer_qty + procure_qty} 台"
                   if (transfer_qty + procure_qty) > 0
                   else "当前各仓位库存均衡，暂无补货需求"),
        "stats": {
            "totalTerminals": snapshot.get("totalTerminals", 0),
            "totalAvailable": algo.get("totalAvailable", 0),
            "totalInTransit": algo.get("totalInTransit", 0),
            "longAgingCount": algo.get("longAgingCount", 0),
        },
        "actions": ["去补货", "下载补货清单"],
    })

    # 2) 调拨清单卡（strategy 三态驱动：需要调拨时才出）
    if transfers:
        cards.append({
            "type": "transfer_list",
            "title": "建议调拨补货清单",
            "subtitle": "优先匹配可调拨库存，减少重复采购并提升库存周转效率",
            "badge": "AI 智能匹配",
            "totalQuantity": transfer_qty,
            "rows": [{
                "model": t.get("model"),
                "materialName": t.get("materialName"),
                "fromWarehouse": t.get("fromWarehouse"),
                "fromWarehouseName": t.get("fromWarehouseName"),
                "toWarehouse": t.get("toWarehouse"),
                "toWarehouseName": t.get("toWarehouseName"),
                "quantity": t.get("quantity"),
                "priority": t.get("priority"),
                # 操作类型透给前端（2026-10-08 收回单已下线，恒为 TRANSFER）
                "operationType": t.get("operationType") or "TRANSFER",
                "reason": t.get("reason"),
                # 物料明细透给前端（2026-10-08）：人工改数后由前端按明细直接建单，不再回编排层
                "materials": t.get("materials") or [],
            } for t in transfers],
            "actions": ["申请调拨"],
        })

    # 3) 采购清单卡（运营商商城的采购单在另一个平台，这里只提供导出）
    if procurements:
        cards.append({
            "type": "procurement_list",
            "title": "建议采购补货清单",
            "badge": "AI 智能推荐",
            "totalQuantity": procure_qty,
            "rows": [{
                "model": p.get("model"),
                # 2026-10-12：采购清单表增加「厂商」与「物料型号」两列，前端直接取用
                # （materialCode/brand 本就在 ProcurementSuggestion 里，此前未透传）
                "materialCode": p.get("materialCode"),
                "materialName": p.get("materialName"),
                # vendor = 该物料的实际厂商（库存数据自带）；suggestedBrand 是算法从
                # 「可调拨同型库存」推导的建议品牌，推导不出时为"待定"——前端优先用 vendor
                "vendor": p.get("vendor"),
                "suggestedBrand": p.get("suggestedBrand") or "待定",
                "suggestedQty": p.get("suggestedQty"),
                "priority": p.get("priority"),
                "reason": p.get("reason"),
            } for p in procurements],
            "actions": ["导出采购清单"],
        })

    # 总结句：LLM 只做措辞，数字由槽位预填；失败落模板句（绝不让 LLM 填数字）
    try:
        summary = get_llm(temperature=0.3, user_id=(state.get("meta") or {}).get("user_id"),
                          agent_code="replenish_reporter").invoke(
            prompt_for("report_summary", REPORT_SUMMARY_PROMPT).format(
                strategy=strategy,
                transfer_count=len(transfers), transfer_qty=transfer_qty,
                procure_count=len(procurements), procure_qty=procure_qty,
                models="、".join(models) or "无",
                long_aging=algo.get("longAgingCount", 0),
            )).content.strip()
    except Exception:  # noqa: BLE001 —— 总结句是装饰，失败不该拖垮主流程
        summary = (f"共需补货 {transfer_qty + procure_qty} 台，"
                   f"其中建议调拨 {transfer_qty} 台、建议采购 {procure_qty} 台。")

    suggestion_id = save_suggestion({
        "strategy": strategy,
        "transfers": transfers,
        "procurements": procurements,
        "models": models,
    })

    duration = int((time.perf_counter() - start) * 1000)
    get_stream_writer()({"seq": 3, "text": summary})

    return {"cards": cards, "suggestion_id": suggestion_id,
            "agent_response": summary,
            "meta": {"report_duration_ms": duration,
                     "report_summary": summary,
                     "suggestion_id": suggestion_id}}


# ---------------------------------------------------------------------------
# 节点 6/8：verify_numbers —— 数字自检（数字红线第一道闸门）
# ---------------------------------------------------------------------------

def verify_numbers(state: OrchestratorState) -> dict:
    """逐项核对卡片里的数字是否都能在算法输出里找到**同值**来源。"""
    algo = state.get("algo_result") or {}
    cards = state.get("cards") or []

    expected_transfer = {
        f"{t.get('fromWarehouse')}->{t.get('toWarehouse')}:{t.get('model')}":
            int(t.get("quantity") or 0)
        for t in (algo.get("transfers") or [])}
    expected_procure = {p.get("model"): int(p.get("suggestedQty") or 0)
                        for p in (algo.get("procurements") or [])}

    violations: list[str] = []
    for card in cards:
        ctype = card.get("type")
        if ctype == "transfer_list":
            total = int(card.get("totalQuantity") or 0)
            expect_total = sum(expected_transfer.values())
            if total != expect_total:
                violations.append(f"调拨清单合计 {total} ≠ 算法合计 {expect_total}")
            for row in card.get("rows") or []:
                key = f"{row.get('fromWarehouse')}->{row.get('toWarehouse')}:{row.get('model')}"
                if key not in expected_transfer:
                    violations.append(f"调拨行 {key} 在算法输出里不存在")
                elif int(row.get("quantity") or 0) != expected_transfer[key]:
                    violations.append(
                        f"调拨行 {key} 数量 {row.get('quantity')} ≠ 算法 {expected_transfer[key]}")
        elif ctype == "procurement_list":
            total = int(card.get("totalQuantity") or 0)
            expect_total = sum(expected_procure.values())
            if total != expect_total:
                violations.append(f"采购清单合计 {total} ≠ 算法合计 {expect_total}")
            for row in card.get("rows") or []:
                model = row.get("model")
                if model not in expected_procure:
                    violations.append(f"采购行 {model} 在算法输出里不存在")
                elif int(row.get("suggestedQty") or 0) != expected_procure[model]:
                    violations.append(
                        f"采购行 {model} 数量 {row.get('suggestedQty')} ≠ 算法 {expected_procure[model]}")

    return {"meta": {"verify_violations": violations,
                     "verify_passed": not violations}}


# ---------------------------------------------------------------------------
# 节点 7/8：order —— 建单
# ---------------------------------------------------------------------------

def order(state: OrchestratorState) -> dict:
    """把一句话指令变成一张真实的调拨单。

    两条入口：
      - 确认建议后建单：用户点卡片上的「申请调拨」→ 带 suggestion_id 回来，
        按缓存建议建单
      - 直接建单：用户直接说"从 A 调 50 台 X 到 B"，由 LLM 解析话术

    两条路径最终都调用后端同一个 create 接口，四条硬规则的校验在后端 Service 里，
    绕不过去。
    """
    start = time.perf_counter()
    sid = (state.get("meta") or {}).get("suggestion_id") or state.get("suggestion_id") or ""
    suggestion = load_suggestion(sid) if sid else None

    cards = list(state.get("cards") or [])

    if suggestion and suggestion.get("transfers"):
        # --- 路径 A：按建议建单 ---
        # 按 (调出仓, 调入仓, 操作类型) 分组，一组一张单。
        # 操作类型取自建议（算法已按仓位层级方向定好 TRANSFER/RECALL），
        # 不能一律写 TRANSFER —— 下级→上级方向的建议会被后端规则拒绝
        groups: dict[tuple, list[dict]] = {}
        for t in suggestion["transfers"]:
            key = (t["fromWarehouse"], t["toWarehouse"], t.get("operationType") or "TRANSFER")
            groups.setdefault(key, []).append(t)

        created, errors = [], []
        for (src, dst, op), rows in groups.items():
            # 一行建议 = 仓对 + 型号，调拨量已按物料分摊在 materials 里
            # （算法在仓粒度比较，建单在物料编码粒度出 items —— 仓管按物料导 SN）
            items = []
            for r in rows:
                mats = [m for m in (r.get("materials") or [])
                        if int(m.get("quantity") or 0) > 0]
                if mats:
                    items.extend({
                        "materialCode": m["materialCode"],
                        "model": r["model"],
                        "materialName": m.get("materialName"),
                        "quantity": m["quantity"],
                    } for m in mats)
                else:
                    # 兼容：suggestion 缓存里还没过期、没有物料明细的旧建议
                    items.append({
                        "materialCode": r["materialCode"],
                        "model": r["model"],
                        "materialName": r.get("materialName"),
                        "quantity": r["quantity"],
                    })
            try:
                vo = create_transfer_order(
                    operation_type=op, from_warehouse=src, to_warehouse=dst,
                    items=items, creator="ai-agent", source="AI", suggestion_id=sid)
                created.append(vo)
            except TerminalApiError as exc:
                errors.append(f"{src}→{dst}({op}) 提交申请失败：{exc}")

        card = _order_card(created, "调拨申请已提交")
        answer = (f"已按建议提交 {len(created)} 张调拨申请，等待调出仓仓管导入 SN 并确认出库"
                  + (f"，{len(errors)} 张失败" if errors else ""))
        if errors:
            answer += "：" + "；".join(errors)
    else:
        # --- 路径 B：解析话术直接建单 ---
        wh_pairs, model_pairs = [], []
        try:
            rows = fetch_snapshot().get("inventory") or []
            wh_pairs = sorted({f"{r.get('warehouseCode')} {r.get('warehouseName')}"
                               for r in rows if r.get("warehouseCode")})
            model_pairs = sorted({f"{r.get('model')} → {r.get('materialCode')}"
                                  for r in rows if r.get("model")})
        except TerminalApiError:
            pass

        try:
            intent: OrderIntent = get_llm(temperature=0, user_id=(state.get("meta") or {}).get("user_id"),
                                          agent_code="order_parser").with_structured_output(
                OrderIntent, method="function_calling").invoke(
                prompt_for("order_parse", ORDER_PARSE_PROMPT).format(
                    warehouses="\n".join(wh_pairs) or "（暂不可用）",
                    models="\n".join(model_pairs) or "（暂不可用）",
                    query=state["query"]))

            if intent.needMoreInfo or not intent.items \
                    or not intent.fromWarehouse or not intent.toWarehouse:
                message = intent.message or "请补充：调出仓位、调入仓位、型号与数量"
                card = {"type": "transfer_order", "title": "还差一点信息",
                        "rows": [], "message": message, "actions": []}
                created, answer = [], message
            else:
                vo = create_transfer_order(
                    operation_type=intent.operationType,
                    from_warehouse=intent.fromWarehouse,
                    to_warehouse=intent.toWarehouse,
                    items=[i.model_dump() for i in intent.items],
                    creator="ai-agent", source="AI")
                created = [vo]
                card = _order_card(created, "调拨申请已提交")
                answer = (f"已提交调拨申请 {vo.get('orderNo')}："
                          f"{vo.get('fromWarehouseName')} → {vo.get('toWarehouseName')}，"
                          f"共 {vo.get('totalQuantity')} 台；等待调出仓仓管确认出库")
        except Exception as exc:  # noqa: BLE001 —— 建单失败要明确告知，不能静默
            created = []
            card = {"type": "transfer_order", "title": "提交申请失败",
                    "rows": [], "message": str(exc), "actions": []}
            answer = f"提交调拨申请失败：{exc}"

    duration = int((time.perf_counter() - start) * 1000)
    get_stream_writer()({"seq": 1, "text": answer})
    cards.append(card)

    # route 覆写为 "order"：本节点不是由意图识别到达的（是建议卡按钮的确定性落点），
    # 终帧标成 order 让前端能显示「意图识别 → 调拨建单」而不是沿路透传的 replenish
    return {"order_info": {"orders": created, "card": card},
            "cards": cards,
            "route": "order",
            "agent_response": answer,
            "meta": {"order_duration_ms": duration, "channel": "order",
                     "order_count": len(created)}}


def _order_card(orders: list[dict], title: str) -> dict:
    """组装调拨单卡片（前端「调拨单确认卡」的数据形态）。"""
    return {
        "type": "transfer_order",
        "title": title,
        "rows": [{
            "orderNo": o.get("orderNo"),
            "operationType": o.get("operationType"),
            "fromWarehouse": o.get("fromWarehouse"),
            "fromWarehouseName": o.get("fromWarehouseName"),
            "toWarehouse": o.get("toWarehouse"),
            "toWarehouseName": o.get("toWarehouseName"),
            "totalQuantity": o.get("totalQuantity"),
            "status": o.get("status"),
            "needApproval": o.get("needApproval"),
            "createdAt": o.get("createdAt"),
        } for o in orders],
        "actions": ["确认", "修改"],
    }


# ---------------------------------------------------------------------------
# 节点 8/8 之前的质检：critic —— 双闸门
# ---------------------------------------------------------------------------

def critic(state: OrchestratorState) -> dict:
    """双闸门：

    ① 代码 diff（确定性）：卡片数字 === 算法输出 —— 直接消费 verify_numbers 的结论，
       有违例即判不合格。这是可判定的，所以不走 LLM。
    ② LLM 语义判分：回答是否切题、是否拒答。

    kb / order 通道只跑闸门①的"空回答"检查 ——
    它们的语义不合格没有可整改的检索产物，回炉收益低，按开发文档规定降级放行。
    """
    route = state.get("route")
    meta = state.get("meta") or {}

    # 取数失败时 report 已短路输出明确失败文案：既无数字可 diff，也无产物可整改，
    # 回炉只会重复产出同一句话。直接放行。
    if meta.get("report_skipped_reason") == "fetch_failed":
        return {"critic_feedback": "", "critic_gate": ""}

    # 闸门①：数字 diff（只有 replenish 通道有卡片数字）
    if route == "replenish":
        violations = meta.get("verify_violations") or []
        if violations:
            return {"critic_feedback": "；".join(violations[:3]),
                    "critic_gate": "number"}

    answer = state.get("agent_response") or ""
    if not answer.strip():
        return {"critic_feedback": "回答为空", "critic_gate": "semantic"}

    if route in ("kb", "order"):
        # 降级放行：留痕但不回炉（无检索产物可整改）
        return {"critic_feedback": "", "critic_gate": ""}

    # 闸门②：replenish 的总结句语义判分
    try:
        verdict: CriticVerdict = get_llm(temperature=0.2, user_id=(state.get("meta") or {}).get("user_id"),
                                         agent_code="quality_critic").with_structured_output(
            CriticVerdict, method="function_calling").invoke(
            prompt_for("critic", CRITIC_PROMPT).format(query=state["query"], answer=answer))
        if not verdict.passed:
            return {"critic_feedback": verdict.reason, "critic_gate": "semantic"}
    except Exception:  # noqa: BLE001 —— 判分服务不可用时不阻塞主流程
        pass

    return {"critic_feedback": "", "critic_gate": ""}


def route_after_critic(state: OrchestratorState) -> str:
    """条件边：按失败类型分流回炉。

    - gate=number  → 回 report 重装卡片（数字问题，改装配不改计算）
    - gate=data    → 回 fetch 重查（取数问题）
    - semantic / 轮次已满 → 降级放行
    """
    feedback = state.get("critic_feedback") or ""
    if not feedback:
        return "respond"
    if (state.get("revision_count") or 0) >= MAX_REVISIONS:
        return "respond"
    gate = state.get("critic_gate") or "semantic"
    if gate == "number":
        return "rework"
    if gate == "data":
        return "rework"
    return "respond"


def route_after_rework(state: OrchestratorState) -> str:
    """回炉桥之后再去哪：数字问题回 report，取数问题回 fetch。"""
    return "fetch" if (state.get("critic_gate") == "data") else "report"


def on_rework(state: OrchestratorState) -> dict:
    """回炉计数器（条件边的写侧动作）。"""
    return {"revision_count": (state.get("revision_count") or 0) + 1,
            "meta": {"critic_degraded": False,
                     "critic_reason": state.get("critic_feedback") or "",
                     "critic_gate": state.get("critic_gate") or ""}}


def on_degrade(state: OrchestratorState) -> dict:
    """放行桥节点：合格与降级共用，按 critic_feedback 有无区分是否带病放行。"""
    feedback = state.get("critic_feedback") or ""
    return {"meta": {"critic_degraded": bool(feedback),
                     "critic_reason": feedback,
                     "critic_gate": state.get("critic_gate") or ""}}


# ---------------------------------------------------------------------------
# 终端节点：respond —— 纯组装
# ---------------------------------------------------------------------------

def respond(state: OrchestratorState) -> dict:
    """SSE 终帧组装。

    result 载荷结构（前端照此渲染）：
        {"agent_response": "...", "cards": [...], "strategy": "...",
         "route": "...", "suggestion_id": "..."}
    """
    payload = {
        "agent_response": state.get("agent_response") or "",
        "cards": state.get("cards") or [],
        "strategy": state.get("strategy") or "",
        "route": state.get("route") or "",
        "suggestion_id": state.get("suggestion_id") or "",
    }
    get_stream_writer()({"__result__": payload})
    return {}


# ---------------------------------------------------------------------------
# 确定性补货预览（无 LLM）：门户「库存健康」等快查入口与聊天 replenish 共用同一口径
# ---------------------------------------------------------------------------

def run_replenish_preview(warehouse_code: str | None) -> dict:
    """跑一遍 取数 → 分布口径算法 → 按可见范围过滤，返回与聊天建议一致的数据。

    为什么要有这个函数（2026-10-07 口径统一）：
      门户的确定性快查此前直连 agent 的阈值接口（<50 台短缺 / ≥200 台可调），
      与编排层「同层均值」口径给出两套数字 —— 同一仓在两个入口看到不同建议。
      现在快查改走本函数：算法只在 tools/algorithms.py 一处，杜绝双实现。
    """
    scope = visible_scope(warehouse_code)
    snapshot = fetch_snapshot()
    rows = [InventoryRow.from_dict(r) for r in (snapshot.get("inventory") or [])]
    plan = plan_replenishment(rows)

    # 可见范围过滤：与 algorithm 节点完全同口径（任一端命中即保留）
    if scope is not None:
        plan.transfers = [t for t in plan.transfers
                          if t.fromWarehouse in scope or t.toWarehouse in scope]
        plan.procurements = [p for p in plan.procurements
                             if not p.toWarehouse or p.toWarehouse in scope]

    has_t, has_p = bool(plan.transfers), bool(plan.procurements)
    strategy = (STRATEGY_MIXED if (has_t and has_p)
                else STRATEGY_PROCURE_ONLY if has_p else STRATEGY_TRANSFER_ONLY)
    return {
        **plan.to_dict(),
        "strategy": strategy,
        "scope": sorted(scope) if scope is not None else None,
        "agingSummary": summarize_aging(snapshot.get("aging") or []),
        "totalAvailable": snapshot.get("totalAvailable", 0),
        "totalInTransit": snapshot.get("totalInTransit", 0),
        "longAgingCount": snapshot.get("longAgingCount", 0),
    }


# ---------------------------------------------------------------------------
# 装配
# ---------------------------------------------------------------------------

def build_graph(critic_enabled: bool = True, router_enabled: bool = True):
    """组装并编译三条业务路线的图。

    ⚠️⚠️ **这是「code 路径」的拓扑 —— 本项目的拓扑有两个事实来源，改一处必须改两处！** ⚠️⚠️

    运行期到底用哪一份，由 config.GRAPH_SOURCE 决定（server.py 的 _compiled_graph）：
      · GRAPH_SOURCE="dsl" → 用管理端画布发布的 DSL（canvas/loader.py 装配），
        失败或没发布过图时**回退到本函数**；
      · GRAPH_SOURCE="code" → 直接用本函数（**当前默认值**，见 config.py L21）。

    也就是说：在管理端画布上改了节点连线并"发布"之后，**本函数不会跟着变**；
    一旦发生回退（DSL 加载失败 / 从未发布），行为就与画布不一致 ——
    且这种漂移只在回退那一刻才暴露，极难排查。

    所以：**改拓扑时，画布 DSL 与本函数必须同步修改**。
    （节点实现只有一份：canvas/registry.py 原样复用本模块的节点函数，不重复实现；
      冗余的只是这几十行 add_node/add_edge 装配 + 拓扑描述本身。）

    - critic_enabled=False：verify_numbers 直接进 respond（评估基线用，
      与打开 critic 时共用同一份节点代码，只关质检 —— 对比才公平）
    - router_enabled=False：入口直接进 kb（兼容旧调用）
    """
    g = StateGraph(OrchestratorState)

    g.add_node("kb_node", kb_node)  # 保留未删：KB_CHANNEL 改回 "kb" 即可复用旧 RAG 通道
    g.add_node("digital_human", digital_human_node)
    g.add_node("chart_plan", chart_plan)
    g.add_node("chart_build", chart_build)
    g.add_node("fetch", fetch)
    g.add_node("algorithm", algorithm)
    g.add_node("report", report)
    g.add_node("verify_numbers", verify_numbers)
    g.add_node("order", order)
    g.add_node("respond", respond)

    if router_enabled:
        g.add_node("supervise", supervise)
        g.add_edge(START, "supervise")
        g.add_conditional_edges(
            "supervise", route_after_supervise,
            {"kb": "kb_node", "digital_human": "digital_human",
             "chart": "chart_plan", "replenish": "fetch", "order": "order"})
    else:
        g.add_edge(START, "kb_node")

    # 图表线（chart_plan → chart_build → respond）
    g.add_edge("chart_plan", "chart_build")
    g.add_edge("chart_build", "respond")

    # 补货主链
    g.add_edge("fetch", "algorithm")
    g.add_edge("algorithm", "report")
    g.add_edge("report", "verify_numbers")

    if critic_enabled:
        g.add_node("critic", critic)
        g.add_node("on_rework", on_rework)
        g.add_node("on_degrade", on_degrade)
        g.add_edge("verify_numbers", "critic")
        g.add_conditional_edges(
            "critic", route_after_critic,
            {"rework": "on_rework", "respond": "on_degrade"})
        g.add_conditional_edges(
            "on_rework", route_after_rework,
            {"report": "report", "fetch": "fetch"})
        g.add_edge("on_degrade", "respond")
    else:
        g.add_edge("verify_numbers", "respond")

    # 另外几条通道
    g.add_edge("kb_node", "respond")
    g.add_edge("digital_human", "respond")
    g.add_edge("order", "respond")
    g.add_edge("respond", END)

    return g.compile()
