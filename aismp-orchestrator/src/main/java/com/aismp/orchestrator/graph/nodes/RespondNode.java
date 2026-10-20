package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.graph.StateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收口节点：把终态整理成与门户约定的 result 载荷。
 *
 * <p>迁移对照 {@code graph.py:1385 respond()} 的「兜底载荷」分支（原版还负责把
 * cards / suggestion_id 拼成 __result__，Java 版由控制器统一从终态取，逻辑等价）。
 *
 * <p>载荷字段名 <b>必须</b>与前端消费的一致：
 * {@code agent_response / cards / strategy / route / suggestion_id}
 * —— 网关与门户都按这几个 key 取值，改名等于改契约。
 */
public class RespondNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> data = state.data();
        String route = text(data.get(StateKeys.ROUTE));
        String answer = text(data.get(StateKeys.AGENT_RESPONSE));

        // 兜底话术：任何路线给出空回答时，都要让用户收到一句人话，
        // 而不是一片空白（前端拿到空字符串只会渲染出一个空气泡）
        if (answer.isEmpty() && !StateKeys.ROUTE_ORDER.equals(route)) {
            answer = "抱歉，暂时没能处理这个请求，请换个问法或稍后再试。";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(StateKeys.AGENT_RESPONSE, answer);
        out.put(StateKeys.CARDS, data.getOrDefault(StateKeys.CARDS, List.of()));
        out.put(StateKeys.STRATEGY, text(data.get(StateKeys.STRATEGY)));
        out.put(StateKeys.ROUTE, route);
        out.put(StateKeys.SUGGESTION_ID, text(data.get(StateKeys.SUGGESTION_ID)));
        return out;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
