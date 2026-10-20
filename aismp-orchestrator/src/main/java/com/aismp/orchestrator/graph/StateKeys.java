package com.aismp.orchestrator.graph;

/**
 * 图的公共数据契约：所有节点读写同一份 OverAllState。
 *
 * <p>键名与原 Python 版 {@code state.py:OrchestratorState} **逐字对齐**，
 * 原因有两个：
 * <ol>
 *   <li>门户/网关消费的 SSE result 载荷字段名不变 —— 前端不用改；</li>
 *   <li>两版可以对照同一份会话数据排查问题（迁移期最有价值的东西）。</li>
 * </ol>
 */
public final class StateKeys {

    private StateKeys() {
    }

    // ---- 入口 ----
    public static final String QUERY = "query";

    // ---- 路由 ----
    public static final String ROUTE = "route";

    // ---- kb 通道 ----
    public static final String REWRITTEN_QUERY = "rewritten_query";
    public static final String AGENT_RESPONSE = "agent_response";

    // ---- replenish 通道 ----
    public static final String STRATEGY = "strategy";
    public static final String CARDS = "cards";
    public static final String SUGGESTION_ID = "suggestion_id";

    // ---- 质检（当前未使用，等 critic 迁入后再启用）----
    public static final String REVISION_COUNT = "revision_count";

    // ---- 元信息（MergeStrategy：多节点写不丢 key）----
    public static final String META = "meta";

    // ---- 节点 id（与画布 DSL / orch_node_def 的 node id 保持一致）----
    public static final String NODE_SUPERVISE = "supervise";
    public static final String NODE_KB = "kb";
    public static final String NODE_RESPOND = "respond";

    // ---- 路线名 ----
    public static final String ROUTE_KB = "kb";
    public static final String ROUTE_CHART = "chart";
    public static final String ROUTE_REPLENISH = "replenish";
    public static final String ROUTE_ORDER = "order";
}
