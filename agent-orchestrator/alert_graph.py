# 库存告警闭环的编排图：scan → judge → notify（三级，全程确定性，无 LLM）
#
# 背景（2026-10-07 与用户对齐；同日二次对齐"口径统一"）：
#   告警闭环的编排上移到编排层 —— agent（aismp-agent）退化为原子能力：
#     · 写通知：POST /transferOrder/notice（同 receiver+type 未读 → 只刷新不新增）
#   而"什么时候扫、扫哪些仓、判什么、怎么投递"都在编排层：
#     · 定时：默认每 10 分钟（ALERT_INTERVAL_SECONDS 可配，≤0 关闭）
#     · 手动：POST /orchestrator/v1/alert/scan（门户「扫描本仓」按钮走的入口）
#   2026-10-07 口径统一：scan 的"查 + 判定"改用编排层的 plan_replenishment
#   （与聊天「补货建议」/「库存健康」同一算法），不再调 agent 的阈值扫描 ——
#   否则同一仓的建议数字在告警与对话里会对不上。
#
# 为什么用图而不是一个函数：
#   与主图同一套骨架（状态契约 / 节点可测 / 留痕），
#   后续扩展（按优先级分流、多通道通知、失败重试）只需加边，不改调用方。
#
# 数字红线：全链路确定性 —— 需要通知哪些仓、通知文案，全部来自编排层的确定性算法
# （plan_replenishment 纯函数），LLM 不参与任何一步。
import time
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from graph import visible_scope
from tools.algorithms import InventoryRow, plan_replenishment
from tools.terminal_api import TerminalApiError, fetch_snapshot, upsert_notice


class AlertState(TypedDict, total=False):
    """告警闭环的共享状态契约。

    入口
      warehouse_code   入口写        → scan 读（None = 全量巡检）
    scan
      scanned          scan 写       → summary 读（扫描仓数）
      findings         scan 写       → judge 读（发现清单，含通知草稿）
      scan_error       scan 写       → summary 读（取数失败原因）
    judge
      plan             judge 写      → notify 读（待投递的通知草稿）
    notify
      results          notify 写     → summary 读（逐条投递结果）
      errors           notify 写     → summary 读（投递失败明细）
    终态
      summary          summarize 写  → 调用方（HTTP 应答 / 定时日志）
    """

    warehouse_code: str | None
    scanned: int
    findings: list
    scan_error: str
    plan: list
    results: list
    errors: list
    summary: dict


def scan_node(state: AlertState) -> dict:
    """编排层自查（2026-10-07 口径统一）：取数 → plan_replenishment → 按仓汇总发现。

    与聊天「补货建议」/「库存健康」共用同一算法（分布口径）——
    此前调 agent 的阈值扫描（<50/≥200），同一仓在告警与对话里数字会对不上。
    失败不抛给调度器：定时任务抛异常只会留下一行堆栈，
    这里转成 scan_error 进摘要，由调用方（日志 / HTTP 应答）显式呈现。
    """
    try:
        warehouse_code = state.get("warehouse_code")
        scope = visible_scope(warehouse_code)        # None = 全量巡检
        snapshot = fetch_snapshot()
        rows = [InventoryRow.from_dict(r) for r in (snapshot.get("inventory") or [])]
        plan = plan_replenishment(rows)
    except TerminalApiError as exc:
        return {"scanned": 0, "findings": [], "scan_error": str(exc)}
    findings = _findings_from_plan(plan, rows, scope)
    scanned = (len(scope) if scope is not None
               else len({r.warehouseCode for r in rows if r.warehouseCode}))
    return {"scanned": scanned, "findings": findings, "scan_error": ""}


def _findings_from_plan(plan, rows: list[InventoryRow], scope: set | None) -> list[dict]:
    """把补货方案按「缺口仓」汇总为发现清单（每条含通知草稿）。

    口径与旧版一致：只通知"本仓有调入/采购缺口"的仓（有建议才通知）；
    但建议的算法与数字与聊天完全同源（plan_replenishment 的产出原值搬运）。
    """
    grouped: dict[str, dict] = {}
    for t in plan.transfers:
        if scope is not None and t.toWarehouse not in scope:
            continue
        grouped.setdefault(t.toWarehouse, {"transfers": [], "procurements": []})["transfers"].append(t)
    for p in plan.procurements:
        if not p.toWarehouse or (scope is not None and p.toWarehouse not in scope):
            continue
        grouped.setdefault(p.toWarehouse, {"transfers": [], "procurements": []})["procurements"].append(p)

    names = {r.warehouseCode: r.warehouseName for r in rows if r.warehouseCode}
    avail: dict[str, int] = {}
    models: dict[str, set] = {}
    for r in rows:
        avail[r.warehouseCode] = avail.get(r.warehouseCode, 0) + r.availableQty
        models.setdefault(r.warehouseCode, set()).add(r.model)

    findings: list[dict] = []
    for wh, g in sorted(grouped.items()):
        transfers, procurements = g["transfers"], g["procurements"]
        wh_name = names.get(wh) or wh
        total = len(transfers) + len(procurements)
        lines = [f"{wh_name}：可用 {avail.get(wh, 0)} 台、型号 {len(models.get(wh) or [])} 个；"
                 f"有 {len(transfers)} 条调拨建议、{len(procurements)} 条采购建议待处理"]
        if transfers:
            t = transfers[0]
            from_avail = next((r.availableQty for r in rows
                               if r.warehouseCode == t.fromWarehouse and r.model == t.model), None)
            lines.append(f"首要建议：{t.model} 从 {t.fromWarehouseName or t.fromWarehouse} 调入 {t.quantity} 台"
                         + (f"（对方可用 {from_avail} 台）" if from_avail is not None else ""))
        elif procurements:
            p = procurements[0]
            lines.append(f"首要建议：{p.model} 需采购 {p.suggestedQty} 台（省内可调拨库存不足）")
        lines.append("点此通知可直接查看补货建议并一键建单。")
        findings.append({
            "warehouseCode": wh,
            "warehouseName": wh_name,
            "transferCount": len(transfers),
            "procurementCount": len(procurements),
            "notice": {
                "receiver": f"{wh}:ACCOUNT_MANAGER",
                "noticeType": "STOCK_ALERT",
                "title": f"库存告警：{wh_name} 有 {total} 条补货建议待处理",
                "content": "\n".join(lines),
            },
        })
    return findings


def judge_node(state: AlertState) -> dict:
    """判定：哪些发现需要通知、通知什么内容。

    判定规则（当前）：agent 只返回"有调拨建议"的仓，均需通知；
    但必须有完整草稿（receiver/title/content 齐全）才进入投递 ——
    缺字段的发现宁可丢弃并留痕，也不写出一条空白通知。
    """
    plan: list[dict] = []
    dropped: list[str] = []
    for finding in state.get("findings") or []:
        notice = finding.get("notice") or {}
        if notice.get("receiver") and notice.get("noticeType") and notice.get("title"):
            plan.append(notice)
        else:
            dropped.append(str(finding.get("warehouseCode") or "?"))
    return {"plan": plan, "errors": ([] if not dropped
                                     else [f"草稿不完整已跳过：{','.join(dropped)}"])}


def notify_node(state: AlertState) -> dict:
    """投递：逐条调 agent 的 notice 原子接口（幂等由落库端保证）。

    单条失败不中断整轮 —— 一个仓写失败不该让其它仓的告警收不到。
    """
    results: list[dict] = []
    errors: list[str] = list(state.get("errors") or [])
    for draft in state.get("plan") or []:
        try:
            result = upsert_notice(
                receiver=draft.get("receiver") or "",
                notice_type=draft.get("noticeType") or "",
                title=draft.get("title") or "",
                content=draft.get("content") or "")
            results.append({"receiver": draft.get("receiver"),
                            "action": result.get("action") or "",
                            "id": result.get("id")})
        except TerminalApiError as exc:
            errors.append(f"{draft.get('receiver')}: {exc}")
    return {"results": results, "errors": errors}


def summarize_node(state: AlertState) -> dict:
    """汇总：给 HTTP 应答 / 定时日志一个稳定的摘要结构。"""
    results = state.get("results") or []
    errors = state.get("errors") or []
    notified = sum(1 for r in results if r.get("action") == "inserted")
    updated = sum(1 for r in results if r.get("action") == "updated")
    return {"summary": {
        "scanned": int(state.get("scanned") or 0),
        "findings": len(state.get("findings") or []),
        "notified": notified,
        "updated": updated,
        "failed": len(errors),
        "scanError": state.get("scan_error") or "",
        "warehouses": [f.get("warehouseCode") for f in state.get("findings") or []],
        "errors": errors,
    }}


def build_alert_graph():
    """组装 scan → judge → notify → summarize 的直线图（确定性，无分支）。"""
    g = StateGraph(AlertState)
    g.add_node("scan", scan_node)
    g.add_node("judge", judge_node)
    g.add_node("notify", notify_node)
    g.add_node("summarize", summarize_node)

    g.add_edge(START, "scan")
    g.add_edge("scan", "judge")
    g.add_edge("judge", "notify")
    g.add_edge("notify", "summarize")
    g.add_edge("summarize", END)
    return g.compile()


def run_alert_scan(warehouse_code: str | None = None) -> dict:
    """跑一轮告警巡检（手动端点与定时任务共用）。返回摘要；异常已在节点内兜住。"""
    final = build_alert_graph().invoke({"warehouse_code": warehouse_code or None})
    summary = final.get("summary") or {}
    summary["ranAt"] = time.strftime("%Y-%m-%d %H:%M:%S")
    return summary