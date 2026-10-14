# 图的公共数据契约：所有节点读写同一个 OrchestratorState
# 设计取舍见 docs/contracts/ 与开发文档 M2
from typing import Annotated, TypedDict


def merge_dict(left: dict, right: dict) -> dict:
    """显式 reducer：meta 字段多节点写入时合并而非覆盖（默认 last-value 会丢先写的 key）。"""
    return {**left, **right}


class OrchestratorState(TypedDict, total=False):
    """三条业务路线共享状态契约。

    路线划分（supervise 节点决定走哪条，三条业务路线）：
      chart     — 生成图表：选图型 → 取数 → 图表卡（统计/分布/汇总）
      replenish — 补货建议：查库存 → 算缺口/水位线 → 生成双清单卡片（核心）
      order     — 建单：按建议（两段式第二段）/ 按一句话话术 → 创建调拨单，
                  是 replenish 路线的执行落点
      kb        — 知识问答：问操作手册、问流程、问故障排查（数字员工内核接管）

    字段 → 生产者 / 消费者：

    入口与路由
      query                 入口写           → supervise / 各路线节点读
      route                 supervise 写     → 条件边分流 / critic 决定回炉目标

    chart 路线
      chart_plan            chart_plan 写    → chart_build 读（含 kind / style 图型）
      cards                 chart_build 写   → respond 输出（type=chart）
      agent_response        chart_build 写   → respond 输出（确定性 note）

    kb 通道
      rewritten_query       kb_node 写       → 留痕（实际重写在 Java 侧 RAG 管线里做）
      agent_response        kb_node 写       → critic / respond 读

    replenish 通道
      inventory_snapshot    fetch 写         → algorithm 读（同一时刻的一致视图）
      algo_result           algorithm 写     → report 读（含 strategy / transfers / procurements）
      strategy              algorithm 写     → report 决定渲染哪几张卡片（三态驱动）
      cards                 report 写        → critic 数字比对 / respond 输出
      suggestion_id         report 写        → order 通道读（两段式人工确认的凭据）

    order 通道
      order_info            order 写         → respond 输出

    质检
      critic_feedback       critic 写（不合格时）→ 对应通道节点读
      critic_gate           critic 写         → 条件边决定回哪个节点（number / data / semantic）
      revision_count        回炉边写          → 条件边判上限

    元信息
      meta                  各节点按需合并写  → 附元信息（conversation_id / *_duration_ms /
                                                critic_degraded / critic_reason / trace）
    """

    # ---- 入口 ----
    query: str

    # ---- 路由 ----
    route: str

    # ---- chart 路线 ----
    chart_plan: dict

    # ---- kb 通道 ----
    rewritten_query: str
    agent_response: str

    # ---- replenish 通道 ----
    inventory_snapshot: dict
    algo_result: dict
    strategy: str
    cards: list
    suggestion_id: str

    # ---- order 通道 ----
    order_info: dict

    # ---- 质检 ----
    critic_feedback: str
    critic_gate: str
    revision_count: int

    # ---- 元信息 ----
    meta: Annotated[dict, merge_dict]
