package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.config.OrchestratorProperties;
import com.aismp.orchestrator.graph.StateKeys;
import com.aismp.orchestrator.llm.LlmRouter;
import com.aismp.orchestrator.llm.RouteLlmClient;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * supervise 节点的两条硬规则：
 * <ol>
 *   <li><b>带 suggestion_id 就不烧 LLM</b> —— 这是「申请调拨」按钮的提交路径，
 *       原版注释记录过：这里曾因 LLM 路由慢 8~17s；</li>
 *   <li><b>不带凭据时必须问模型</b>，并把判断依据透出到 meta（便于观察模型质量）。</li>
 * </ol>
 */
class SuperviseNodeTest {

    private static OverAllState state(Map<String, Object> data) {
        return OverAllStateBuilder.builder().withData(new HashMap<>(data)).build();
    }

    private static LlmRouter router(String apiKey, AtomicInteger calls, String answer) {
        OrchestratorProperties props = new OrchestratorProperties();
        props.getLlm().setApiKey(apiKey);
        RouteLlmClient stub = query -> {
            calls.incrementAndGet();
            return answer;
        };
        return new LlmRouter(props, stub);
    }

    @Test
    void suggestionIdShortCircuitsAndNeverCallsLlm() {
        AtomicInteger calls = new AtomicInteger();
        SuperviseNode node = new SuperviseNode(router("sk-test", calls, "kb"));

        Map<String, Object> out = node.apply(state(Map.of(
                StateKeys.QUERY, "就按这个建议申请调拨",
                StateKeys.SUGGESTION_ID, "sug-1",
                StateKeys.META, Map.of("conversation_id", "c1"))));

        assertThat(out.get(StateKeys.ROUTE)).isEqualTo(StateKeys.ROUTE_ORDER);
        assertThat(calls.get()).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) out.get(StateKeys.META);
        assertThat(meta.get("route_reason")).isEqualTo("suggestion_id 直达建单（免 LLM）");
    }

    @Test
    void suggestionIdInsideMetaAlsoShortCircuits() {
        // 入口把 suggestion_id 同时写进 meta 与顶层，两处都要认
        AtomicInteger calls = new AtomicInteger();
        SuperviseNode node = new SuperviseNode(router("sk-test", calls, "kb"));

        Map<String, Object> out = node.apply(state(Map.of(
                StateKeys.QUERY, "确认建单",
                StateKeys.META, Map.of(StateKeys.SUGGESTION_ID, "sug-2"))));

        assertThat(out.get(StateKeys.ROUTE)).isEqualTo(StateKeys.ROUTE_ORDER);
        assertThat(calls.get()).isZero();
    }

    @Test
    void withoutCredentialItAsksTheModel() {
        AtomicInteger calls = new AtomicInteger();
        SuperviseNode node = new SuperviseNode(router("sk-test", calls, "replenish"));

        Map<String, Object> out = node.apply(state(Map.of(
                StateKeys.QUERY, "哪个仓该补货",
                StateKeys.META, Map.of("user_id", "u1"))));

        assertThat(out.get(StateKeys.ROUTE)).isEqualTo(StateKeys.ROUTE_REPLENISH);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void metaCarriesRouteReasonAndTiming() {
        AtomicInteger calls = new AtomicInteger();
        SuperviseNode node = new SuperviseNode(router("sk-test", calls, "chart"));

        Map<String, Object> out = node.apply(state(Map.of(StateKeys.QUERY, "画个饼图")));

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) out.get(StateKeys.META);
        assertThat(meta).containsKeys("route", "route_reason", "router_duration_ms");
        assertThat(meta.get("route")).isEqualTo("chart");
    }

    @Test
    void missingMetaDoesNotBlowUp() {
        // 入口异常/直连调用时可能没有 meta，节点必须能兜住
        AtomicInteger calls = new AtomicInteger();
        SuperviseNode node = new SuperviseNode(router("sk-test", calls, "kb"));

        Map<String, Object> out = node.apply(state(Map.of(StateKeys.QUERY, "你好")));

        assertThat(out.get(StateKeys.ROUTE)).isEqualTo(StateKeys.ROUTE_KB);
    }
}
