package com.aismp.orchestrator.graph;

import com.aismp.orchestrator.client.DigitalHumanClient;
import com.aismp.orchestrator.config.OrchestratorProperties;
import com.aismp.orchestrator.graph.nodes.KbNode;
import com.aismp.orchestrator.graph.nodes.RespondNode;
import com.aismp.orchestrator.graph.nodes.SuperviseNode;
import com.aismp.orchestrator.llm.LlmRouter;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图行为测试：不起 Spring 容器、不连网络、不连数据库。
 *
 * <p>为什么这样测：编排层最容易坏的从来不是「编译过不过」，而是
 * <b>拓扑对不对</b>（谁连谁）和 <b>降级对不对</b>（外部依赖挂了会不会把异常甩给用户）。
 * 这两件事都能在纯本地验证，不需要部署。
 *
 * <p>本测试用「没有 LLM Key + 没有服务账号」的配置跑，等价于两个外部依赖全挂：
 * 期望 supervise 降级 kb、kb 节点给出人话降级、respond 仍产出完整 result 载荷。
 */
class OrchestratorGraphTest {

    private CompiledGraph buildGraph() throws Exception {
        OrchestratorProperties props = new OrchestratorProperties(); // 默认值：无 Key、无服务账号
        RestClient.Builder builder = RestClient.builder();

        // 路由通道用桩子：本测试只关心图拓扑与降级，不碰网络
        LlmRouter router = new LlmRouter(props, query -> { throw new IllegalStateException("不该被调用"); });
        DigitalHumanClient client = new DigitalHumanClient(props, builder);

        return new OrchestratorGraphConfig().orchestratorGraph(
                new GraphStateConfig().keyStrategyFactory(),
                new SuperviseNode(router),
                new KbNode(client, props),
                new RespondNode());
    }

    @Test
    void kbRouteDegradesGracefullyWhenExternalDepsAreDown() throws Exception {
        CompiledGraph graph = buildGraph();

        Map<String, Object> meta = new HashMap<>();
        meta.put("conversation_id", "test-conv-1");
        meta.put("user_id", "tester");

        Map<String, Object> input = new HashMap<>();
        input.put(StateKeys.QUERY, "终端故障返厂维修要怎么登记？");
        input.put(StateKeys.REVISION_COUNT, 0);
        input.put(StateKeys.META, meta);

        List<NodeOutput> outputs = graph
                .stream(input, RunnableConfig.builder().threadId("test-conv-1").build())
                .collectList()
                .block();

        assertThat(outputs).isNotNull();
        List<String> nodes = outputs.stream()
                .map(NodeOutput::node)
                .filter(n -> n != null
                        && !StateGraph.START.equals(n) && !StateGraph.END.equals(n))
                .toList();

        // 拓扑断言：supervise → kb → respond，一步不落
        assertThat(nodes).containsExactly("supervise", "kb", "respond");

        Map<String, Object> finalState = outputs.get(outputs.size() - 1).state().data();

        // 路由：无 LLM Key 时降级 kb（不是抛异常）
        assertThat(finalState.get(StateKeys.ROUTE)).isEqualTo(StateKeys.ROUTE_KB);

        // 降级话术：内核不可用也要给人话，且结果是完整载荷
        String answer = String.valueOf(finalState.get(StateKeys.AGENT_RESPONSE));
        assertThat(answer).contains("数字员工暂时不可用");
        assertThat(finalState.get(StateKeys.CARDS)).isInstanceOf(List.class);
        assertThat(finalState).containsKeys(StateKeys.STRATEGY, StateKeys.SUGGESTION_ID);

        // meta 用 MergeStrategy：supervise 与 kb 写的 key 都要在（覆盖策略会只剩最后一个）
        @SuppressWarnings("unchecked")
        Map<String, Object> mergedMeta = (Map<String, Object>) finalState.get(StateKeys.META);
        assertThat(mergedMeta).containsKeys("route", "channel", "digital_human_duration_ms");
    }

    @Test
    void suggestionIdShortCircuitsToOrderWithoutCallingLlm() throws Exception {
        CompiledGraph graph = buildGraph();

        Map<String, Object> meta = new HashMap<>();
        meta.put("conversation_id", "test-conv-2");
        meta.put("suggestion_id", "sug-123");

        Map<String, Object> input = new HashMap<>();
        input.put(StateKeys.QUERY, "就按这个建议申请调拨");
        input.put(StateKeys.META, meta);
        input.put(StateKeys.SUGGESTION_ID, "sug-123");

        List<NodeOutput> outputs = graph
                .stream(input, RunnableConfig.builder().threadId("test-conv-2").build())
                .collectList()
                .block();

        assertThat(outputs).isNotNull();
        Map<String, Object> finalState = outputs.get(outputs.size() - 1).state().data();

        // 两段式第二段：凭据直达 order，不走 LLM、不去 kb 节点
        assertThat(finalState.get(StateKeys.ROUTE)).isEqualTo(StateKeys.ROUTE_ORDER);
        assertThat(outputs.stream().map(NodeOutput::node).toList())
                .doesNotContain("kb");
    }
}
