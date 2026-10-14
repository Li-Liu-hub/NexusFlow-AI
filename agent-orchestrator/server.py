# FastAPI 编排服务：把 graph 包成可被网关调度的 SSE 端点，另挂告警闭环的定时与手动入口
#
# 链路: 客户端(签名/门户令牌) → 网关(鉴权+限流+计量+路由) → 本服务 → graph.stream()
#       逐节点推 progress 事件 → result 事件收尾
#
# 事件协议（详见 docs/contracts/03-SSE事件协议.md）：
#   start        {"conversation_id": "..."}
#   progress     {"node": "...", "produced": [...]}          节点级
#   agent_step   {"seq": N, "text": "..."}                  步骤级（对话流逐条显示）
#   result       {"agent_response": "...", "cards": [...],  终态（含富卡片/图表）
#                 "strategy": "...", "route": "...", "suggestion_id": "..."}
#   error        "错误信息"
#
# 除对话 SSE 外还有两个入口（2026-10-07 告警闭环上移）：
#   POST /orchestrator/v1/alert/scan   手动巡检（门户「扫描本仓」）→ 返回 JSON 摘要
#   定时任务                            每隔 ALERT_INTERVAL_SECONDS 秒自动巡检一轮
import asyncio
import contextlib
import json
import logging
import sys
import uuid
from collections.abc import Iterator
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse

sys.path.insert(0, str(Path(__file__).resolve().parent))

import conversation_store as store
from alert_graph import run_alert_scan
from config import settings
from graph import build_graph, run_replenish_preview
from canvas import store as canvas_store
from canvas import loader as canvas_loader
from canvas.default_dsl import DEFAULT_DSL
from tools.java_agent import JavaAgentError
from tools.terminal_api import TerminalApiError

# 让编排层自身的 INFO 日志（含定时巡检每轮结果）落到 uvicorn 的日志文件里 ——
# 不配置的话 root 是 WARNING，log.info 全部静默，定时任务等于"跑了但看不见"
logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("orchestrator")


async def _alert_loop(interval: int) -> None:
    """定时巡检循环（scan → 判定 → notify）。

    放在编排层而不是 agent 的 @Scheduled：巡检周期与投递策略属于"业务编排"决策，
    和 scan/judge/notify 的主逻辑放一起，调周期不用重编译 Java。
    单轮失败只记日志 —— 定时循环绝不能因一轮异常退出。
    """
    while True:
        await asyncio.sleep(interval)
        try:
            summary = await asyncio.to_thread(run_alert_scan)
            if summary.get("scanError"):
                log.error("库存告警巡检失败：%s", summary["scanError"])
            elif summary.get("notified") or summary.get("updated") or summary.get("failed"):
                log.info("库存告警巡检：扫描 %s 仓，新增 %s / 刷新 %s / 失败 %s",
                         summary.get("scanned"), summary.get("notified"),
                         summary.get("updated"), summary.get("failed"))
        except Exception:  # noqa: BLE001 —— 兜底：单轮异常不拖垮循环
            log.exception("库存告警巡检异常")


@asynccontextmanager
async def lifespan(_: FastAPI):
    """启动/关闭定时巡检（ALERT_INTERVAL_SECONDS ≤ 0 表示关闭）。"""
    task: asyncio.Task | None = None
    if settings.ALERT_INTERVAL_SECONDS > 0:
        task = asyncio.create_task(_alert_loop(settings.ALERT_INTERVAL_SECONDS))
        log.info("库存告警定时巡检已启动：每 %s 秒一轮", settings.ALERT_INTERVAL_SECONDS)
    else:
        log.info("库存告警定时巡检已关闭（ALERT_INTERVAL_SECONDS<=0）")
    yield
    if task:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task


app = FastAPI(title="agent-orchestrator", version="0.3.0", lifespan=lifespan)


def sse(event: str, data) -> str:
    """SSE 帧格式：event 行 + data 行 + 空行。

    data 可以是 str 或 dict —— 统一在这里 json.dumps 一次，调用方不要预先编码，
    避免"字符串被二次编码"变成一堆转义引号。
    """
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _compiled_graph() -> tuple:
    """按 GRAPH_SOURCE 开关选择图来源（画布方案，docs/Dify编排迁移-工程量评估.md 第十二节）。

    返回 (graph, graph_version)：version 供「会话粘版本」——
    新会话创建时绑定当时的图版本，中途发布不打断进行中的确认流。
    code = 现网硬编码图（默认：行为与改造前完全一致，version=None）；
    dsl  = 最新 published 画布图（管理端发布后，新对话立即生效，无需重启）。
    dsl 链路任何故障都回退 code —— 画布故障绝不阻塞对话。
    """
    if settings.GRAPH_SOURCE == "dsl":
        dsl = canvas_store.load_published()
        if dsl is not None:
            return (canvas_loader.build_graph_from_dsl(dsl),
                    canvas_store.latest_published_version())
        log.warning("GRAPH_SOURCE=dsl 但没有已发布图，回退 code 图")
    return build_graph(), None


def gateway_identity(request: Request) -> tuple[str | None, str]:
    """读取网关注入的门户身份头，返回 (user_id, warehouse_code)。

    信任模型（2026-10-07 [数据权限信任根] 修复）：
      门户请求经网关鉴权后，网关会**先剥离**客户端自带的 X-User-* / X-Area-Code，
      再注入权威身份（CustomGlobalFilter#injectPortalIdentity）—— 所以这些头一旦出现，
      就是网关照写过的，优先于 query 参数（浏览器可随意改写 query，不可信）。

    user_id 返回 None 表示"非门户通道"（本机脚本 / 服务间直连）：此时调用方回退到
    query 参数，兼容既有联调脚本与演示账号。服务只绑 127.0.0.1，回退面仅限本机。
    """
    user_id = (request.headers.get("X-User-Id") or "").strip()
    if not user_id:
        return None, ""
    return user_id, (request.headers.get("X-User-Warehouse") or "").strip()


@app.get("/health")
def health() -> dict:
    """健康检查：部署脚本与运维直接用"""
    return {"status": "ok", "service": "agent-orchestrator"}


@app.get("/orchestrator/v1/conversation/list")
def conversation_list(request: Request, user_id: str = "", limit: int = 20) -> list:
    """历史会话列表（门户「最近对话」，按人隔离）——读 orch_conversation。

    身份优先取网关注入头：query 里的 user_id 由浏览器自报，可被改写（改个参数就能看别人的会话列表）。
    """
    header_user, _ = gateway_identity(request)
    return store.list_conversations(header_user or user_id, limit)


@app.get("/orchestrator/v1/conversation/{conversation_id}")
def conversation_detail(conversation_id: str, request: Request) -> dict:
    """会话详情（消息按时间正序）——门户回放用。

    归属校验：门户通道下，会话必须属于当前登录用户，否则 404（用"不存在"而不是"无权限"，
    不泄露会话是否存在）。**无归属的会话（历史/内部调用产生的 user_id 为空）对门户用户同样 404** ——
    不给"猜 id"留口子。非门户通道（本机脚本）不做校验 —— 兼容既有联调脚本。
    """
    data = store.get_conversation(conversation_id)
    header_user, _ = gateway_identity(request)
    owner = (data.get("user_id") or "").strip()
    if header_user and owner != header_user:
        raise HTTPException(status_code=404, detail="会话不存在")
    return data


@app.post("/orchestrator/v1/alert/scan")
def alert_scan(request: Request, warehouse_code: str = "") -> dict:
    """手动触发一轮库存告警巡检（编排层 scan → 判定 → notify）。

    门户「扫描本仓」（带 warehouse_code）与全量巡检（不传）共用本入口；
    返回 JSON 摘要（非 SSE —— 一轮巡检是短平快的确定性流程，没有需要逐帧推送的中间产物）：
        {"scanned","findings","notified","updated","failed","scanError","warehouses","ranAt"}

    门户通道以注入头的仓位为准（用户只能扫自己可见的范围）；无仓库归属的账号（演示账号）
    注入头为空 → 全量巡检，与改造前"不带 warehouse_code"的行为一致。
    """
    header_user, header_warehouse = gateway_identity(request)
    if header_user is not None:
        warehouse_code = header_warehouse
    return run_alert_scan(warehouse_code or None)


@app.post("/orchestrator/v1/replenish/preview")
def replenish_preview(request: Request, warehouse_code: str = "") -> dict:
    """补货建议预览（确定性、无 LLM）：与聊天「补货建议」路线**同一算法**（分布口径）。

    2026-10-07 口径统一：门户「库存健康」等快查入口改走本端点 ——
    此前直连 agent 的阈值接口（<50/≥200），同一仓两个入口给出两套建议；
    现在算法只保留 tools/algorithms.plan_replenishment 一处实现。
    身份与入参口径同 chat/stream：门户通道以网关注入头为准，本机脚本回退 query。
    """
    header_user, header_wh = gateway_identity(request)
    if header_user is not None:
        warehouse_code = header_wh
    try:
        return run_replenish_preview(warehouse_code or None)
    except TerminalApiError as exc:
        raise HTTPException(status_code=503, detail=f"库存服务不可达：{exc}")


@app.post("/orchestrator/v1/chat/stream")
def chat_stream(request: Request, query: str, user_id: str = "anonymous", suggestion_id: str = "",
                warehouse_code: str = "", conversation_id: str = "") -> StreamingResponse:
    """编排入口：POST，参数走查询串（无请求体 → 网关按 'null.' 验签，与 GET 同语义）。

    三条业务路线（chart / replenish + order 落点 / kb）在 graph.py 里定义；
    本文件只负责"把 LangGraph 的流翻译成 SSE 帧"，不含任何业务判断。

    suggestion_id：两段式人工确认的凭据。第一轮 replenish 在 result 里返回它，
    用户点「申请调拨」后带同一个 id 再请求一次，order 通道据此按建议建单。
    不传则走"解析一句话直接建单"路径。

    user_id / warehouse_code：**门户通道以网关注入头为准**（X-User-Id / X-User-Warehouse），
    query 参数仅在非门户通道（本机脚本/服务间直连）作为回退 —— 浏览器自报的参数不可信，
    这是 2026-10-07 [数据权限信任根] 修复的核心：取数与产出的可见范围
    （本仓 + 所有下级仓）不再能被改参数放大。无仓库归属的账号 → 不限制（演示账号）。

    conversation_id：会话 id（门户续聊时回传）。不传则新建 —— 每轮问答都落
    orch_conversation / orch_message（历史可查；kb 路线同时把 id 透传给内核做记忆）。
    """
    header_user, header_warehouse = gateway_identity(request)
    if header_user is not None:
        user_id, warehouse_code = header_user, header_warehouse

    def gen() -> Iterator[str]:
        conv_id = conversation_id or f"orch-{uuid.uuid4().hex[:8]}"
        # 会话与消息落库（失败不阻塞对话：store 内部吞异常只记日志）
        store.append_message(conv_id, "user", query)
        yield sse("start", {"conversation_id": conv_id})

        # 入口状态：suggestion_id 同时写进 meta（图内 order 节点读 meta）和顶层（便于留痕）
        graph_app, graph_version = _compiled_graph()
        # 会话粘版本：仅新建会话时记录（老会话保持其创建时版本，见 ensure_conversation）
        store.ensure_conversation(conv_id, user_id, warehouse_code or None, query,
                                  graph_version=graph_version)
        initial: dict = {
            "query": query,
            "revision_count": 0,
            "meta": {"conversation_id": conv_id, "user_id": user_id,
                     "warehouse_code": warehouse_code or None},
        }
        if suggestion_id:
            initial["suggestion_id"] = suggestion_id
            initial["meta"]["suggestion_id"] = suggestion_id

        # updates（节点级）累加出终态；custom（节点旁路推流）逐条转 agent_step
        final: dict = {}
        result_payload: dict | None = None
        try:
            # recursion_limit 显式兜底：自纠环上限 2 回炉 ≈ 12 步内，20 是双保险
            for mode, chunk in graph_app.stream(
                initial,
                config={"recursion_limit": 20},
                stream_mode=["updates", "custom"],
            ):
                if mode == "updates":
                    for node, update in chunk.items():
                        if update:
                            final.update(update)
                        yield sse("progress", {
                            "node": node,
                            "produced": list(update.keys()) if update else []})
                else:  # custom：节点的旁路推流
                    if isinstance(chunk, dict) and "__result__" in chunk:
                        # respond 节点组装的终态载荷，单独收起来
                        result_payload = chunk["__result__"]
                    else:
                        yield sse("agent_step", chunk)
        except JavaAgentError as e:
            yield sse("error", f"Java agent 调用失败：{e}")
            return
        except TerminalApiError as e:
            yield sse("error", f"终端业务接口调用失败：{e}")
            return
        except Exception as e:  # noqa: BLE001 —— 兜底：任何异常都要让客户端收到 error 帧
            yield sse("error", f"编排异常：{type(e).__name__}: {e}")
            return

        payload = result_payload or {
            "agent_response": final.get("agent_response", ""),
            "cards": final.get("cards", []),
            "strategy": final.get("strategy", ""),
            "route": final.get("route", ""),
            "suggestion_id": final.get("suggestion_id", ""),
        }
        payload["conversation_id"] = conv_id
        store.append_message(conv_id, "assistant", payload.get("agent_response") or "",
                             route=payload.get("route"), cards=payload.get("cards"))
        yield sse("result", payload)

    return StreamingResponse(gen(), media_type="text/event-stream")


# ---------------------------------------------------------------------------
# 会话 CRUD（2026-10-08）：门户「最近对话」支持重命名 / 删除
# 归属校验同 conversation_detail：门户通道下会话必须属于当前登录用户，否则 404
# （不泄露会话是否存在）；非门户通道（本机脚本）不做校验，兼容联调脚本。
# ---------------------------------------------------------------------------

@app.put("/orchestrator/v1/conversation/{conversation_id}")
def conversation_rename(conversation_id: str, request: Request, body: dict) -> dict:
    """重命名会话标题（body: {"title": "..."}）——空标题视为取消，直接返回 ok 不落库。"""
    header_user, _ = gateway_identity(request)
    title = str((body or {}).get("title") or "").strip()
    if not title:
        return {"ok": True, "skipped": "empty title"}
    data = store.get_conversation(conversation_id)
    owner = (data.get("user_id") or "").strip()
    if header_user and owner != header_user:
        raise HTTPException(status_code=404, detail="会话不存在")
    ok = store.rename_conversation(conversation_id, header_user or owner, title)
    if not ok:
        raise HTTPException(status_code=404, detail="会话不存在")
    return {"ok": True, "title": title[:200]}


@app.delete("/orchestrator/v1/conversation/{conversation_id}")
def conversation_delete(conversation_id: str, request: Request) -> dict:
    """删除会话及其全部消息。"""
    header_user, _ = gateway_identity(request)
    data = store.get_conversation(conversation_id)
    owner = (data.get("user_id") or "").strip()
    if header_user and owner != header_user:
        raise HTTPException(status_code=404, detail="会话不存在")
    if not store.delete_conversation(conversation_id, header_user or owner):
        raise HTTPException(status_code=404, detail="会话不存在")
    return {"ok": True}


# ---------------------------------------------------------------------------
# 画布管理端点（feature 分支第一期）：draft 读写 / 发布 / 版本列表
# 归属模型：管理面（后续按需挂管理端鉴权，与 TokenAdminController 同级的安全要求）
# ---------------------------------------------------------------------------

@app.get("/orchestrator/v1/graph/draft")
def graph_draft_get() -> dict:
    """读当前草稿；无草稿时回 DEFAULT_DSL（画布首开即见现网拓扑）。"""
    dsl = canvas_store.load_draft()
    return {"dsl": dsl if dsl is not None else DEFAULT_DSL, "isDefault": dsl is None}


@app.put("/orchestrator/v1/graph/draft")
def graph_draft_put(request: Request, body: dict) -> dict:
    """自动保存草稿：允许暂存编辑中间态（不强制合法），但把校验结果一并返回。"""
    dsl = (body or {}).get("dsl")
    if not isinstance(dsl, dict):
        raise HTTPException(status_code=400, detail="body.dsl 缺失")
    errs = canvas_loader.validate_dsl(dsl)
    ok = canvas_store.save_draft(dsl)
    return {"ok": ok, "errors": errs}


@app.post("/orchestrator/v1/graph/publish")
def graph_publish(request: Request, body: dict) -> dict:
    """发布：校验（数字红线/白名单/引用完整）全过才固化为 published v{N}。

    发布后新对话立即用新图（无需重启）；进行中对话粘旧版本（会话粘版本语义）。
    """
    dsl = (body or {}).get("dsl") or canvas_store.load_draft() or DEFAULT_DSL
    errs = canvas_loader.validate_dsl(dsl)
    if errs:
        raise HTTPException(status_code=422, detail={"errors": errs})
    version = canvas_store.publish(dsl, note=str((body or {}).get("note", ""))[:200])
    if version is None:
        raise HTTPException(status_code=503, detail="图存储不可用（PG）")
    return {"ok": True, "version": version}


@app.get("/orchestrator/v1/graph/versions")
def graph_versions() -> dict:
    return {"versions": canvas_store.list_versions()}


@app.get("/orchestrator/v1/graph/interfaces")
def graph_interfaces() -> dict:
    """接口清单 + 预定义节点集：画布页「写节点」的数据源（真实登记接口，非虚构）。"""
    from canvas.interfaces import INTERFACES, PRESET_NODES
    return {"interfaces": INTERFACES, "presets": PRESET_NODES}


@app.get("/orchestrator/v1/graph/node-types")
def graph_node_types() -> dict:
    """节点库（Dify 式节点面板的数据源）：分类分组的节点定义。

    含三类：内置节点（实现来自代码注册表）、接口节点（13 个已登记接口，预填 path）、
    自定义节点（用户固化「接口 + 参数预设」，impl_type=http）。
    """
    return canvas_store.list_node_defs()


@app.post("/orchestrator/v1/graph/node-types")
def graph_node_types_create(request: Request, body: dict) -> dict:
    """创建自定义节点（http 形态，不写代码）。"""
    label = str((body or {}).get("label", "")).strip()
    path = str((body or {}).get("path", "")).strip()
    if not label or not path:
        raise HTTPException(status_code=400, detail="label 与 path 必填")
    nid = canvas_store.create_node_def(
        label, str((body or {}).get("description", "")), path,
        str((body or {}).get("method", "GET")).upper(),
        (body or {}).get("body") or {})
    if nid is None:
        raise HTTPException(status_code=503, detail="节点库存储不可用（PG）")
    return {"ok": True, "id": nid}


@app.delete("/orchestrator/v1/graph/node-types/{nid}")
def graph_node_types_delete(nid: str) -> dict:
    """删除自定义节点（内置节点不可删）。"""
    if not canvas_store.delete_node_def(nid):
        raise HTTPException(status_code=400, detail="删除失败（内置节点不可删或不存在）")
    return {"ok": True}


@app.post("/orchestrator/v1/graph/rollback")
def graph_rollback(request: Request, body: dict) -> dict:
    """回滚 = 把历史版本复制为新 published（版本链不破坏），新对话立即生效。"""
    version = (body or {}).get("version")
    if not isinstance(version, int):
        raise HTTPException(status_code=400, detail="body.version 必须是整数")
    new_version = canvas_store.rollback(version)
    if new_version is None:
        raise HTTPException(status_code=404, detail=f"版本 v{version} 不存在")
    return {"ok": True, "rolledBackFrom": version, "version": new_version}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8125)
