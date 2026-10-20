package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.graph.StateKeys;
import com.aismp.orchestrator.graph.StateSupport;
import com.aismp.orchestrator.llm.LlmRouter;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.HashMap;
import java.util.Map;

/**
 * 路由节点：决定这次请求走哪条路线。
 *
 * <p>迁移对照 {@code graph.py:398 supervise()}，两条保留的语义：
 * <ol>
 *   <li><b>两段式第二段短路</b>：请求带 {@code suggestion_id} 说明是用户点了建议卡上的
 *       「申请调拨」按钮 —— 这是协议动作不是自然语言，直接去 order，不烧 LLM。
 *       原版注释记录过：这一步曾是「申请调拨」提交慢的唯一元凶（glm 路由一次 8~17s）。</li>
 *   <li><b>纯 LLM 判断</b>：不用关键词表兜底/覆盖（用户明确要求「意图识别用 AI」）。</li>
 * </ol>
 */
public class SuperviseNode implements NodeAction {

    private final LlmRouter router;

    public SuperviseNode(LlmRouter router) {
        this.router = router;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        long start = System.currentTimeMillis();
        Map<String, Object> data = state.data();
        Map<String, Object> meta = StateSupport.asMap(data.get(StateKeys.META));

        String suggestionId = StateSupport.text(meta.get(StateKeys.SUGGESTION_ID));
        if (suggestionId.isEmpty()) {
            suggestionId = StateSupport.text(data.get(StateKeys.SUGGESTION_ID));
        }
        if (!suggestionId.isEmpty()) {
            Map<String, Object> out = new HashMap<>();
            out.put(StateKeys.ROUTE, StateKeys.ROUTE_ORDER);
            out.put(StateKeys.META, Map.of(
                    "route", StateKeys.ROUTE_ORDER,
                    "route_reason", "suggestion_id 直达建单（免 LLM）",
                    "router_duration_ms", System.currentTimeMillis() - start));
            return out;
        }

        LlmRouter.RouteDecision decision =
                router.route(StateSupport.text(data.get(StateKeys.QUERY)), StateSupport.text(meta.get("user_id")));

        Map<String, Object> out = new HashMap<>();
        out.put(StateKeys.ROUTE, decision.route());
        out.put(StateKeys.META, Map.of(
                "route", decision.route(),
                "route_reason", decision.reason(),
                "router_duration_ms", decision.durationMs()));
        return out;
    }


}
