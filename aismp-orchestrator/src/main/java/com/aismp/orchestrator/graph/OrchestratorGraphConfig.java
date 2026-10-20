package com.aismp.orchestrator.graph;

import com.aismp.orchestrator.client.DigitalHumanClient;
import com.aismp.orchestrator.config.OrchestratorProperties;
import com.aismp.orchestrator.graph.nodes.KbNode;
import com.aismp.orchestrator.graph.nodes.RespondNode;
import com.aismp.orchestrator.graph.nodes.SuperviseNode;
import com.aismp.orchestrator.llm.LlmRouter;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 图装配（迁移对照 {@code graph.py:1445 build_graph()}）。
 *
 * <p>当前图结构（阶段 1，kb 路线）：
 * <pre>
 *   START → supervise ─┬─(kb)────→ kb → respond → END
 *                      └─(其余)──→ respond → END
 * </pre>
 *
 * <p>阶段 2 补齐 chart / replenish / order 三条路线时，只改这里 ——
 * 节点实现已经按 {@link com.aismp.orchestrator.graph.StateKeys} 的契约写好，
 * 加节点 + 加条件边不触碰已有节点，这也是原版「一个 build_graph 就是全部拓扑」的好处。
 *
 * <p>recursionLimit=20 与原版一致：自纠环上限 2 次回炉约 12 步，20 是双保险。
 */
@Configuration
public class OrchestratorGraphConfig {

    // 三个节点必须是 Spring Bean —— 图装配方法按参数注入它们。
    // 教训：这些类一直没被注册成 Bean，而单测是手工 new 的，所以"编译过 + 单测绿"
    // 掩盖了容器启动不了的洞，直到真正部署才炸（/health 500）。
    // 现在由 OrchestratorContextTest 兜住：上下文起不来，测试就红。
    @Bean
    public SuperviseNode superviseNode(LlmRouter router) {
        return new SuperviseNode(router);
    }

    @Bean
    public KbNode kbNode(DigitalHumanClient client, OrchestratorProperties properties) {
        return new KbNode(client, properties);
    }

    @Bean
    public RespondNode respondNode() {
        return new RespondNode();
    }

    @Bean
    public CompiledGraph orchestratorGraph(KeyStrategyFactory keyStrategyFactory,
                                           SuperviseNode superviseNode,
                                           KbNode kbNode,
                                           RespondNode respondNode) throws GraphStateException {

        // 条件边：只有 kb 有独立节点，其余路线暂时直接收口（RespondNode 会写明未迁移）
        AsyncEdgeAction routeEdge = state -> {
            Object route = state.data().get(StateKeys.ROUTE);
            String value = route == null ? StateKeys.ROUTE_KB : String.valueOf(route);
            return CompletableFuture.completedFuture(
                    StateKeys.ROUTE_KB.equals(value) ? StateKeys.NODE_KB : StateKeys.NODE_RESPOND);
        };

        StateGraph graph = new StateGraph("nexusflow-orchestrator", keyStrategyFactory)
                .addNode(StateKeys.NODE_SUPERVISE, node_async(superviseNode))
                .addNode(StateKeys.NODE_KB, node_async(kbNode))
                .addNode(StateKeys.NODE_RESPOND, node_async(respondNode))
                .addEdge(StateGraph.START, StateKeys.NODE_SUPERVISE)
                .addConditionalEdges(StateKeys.NODE_SUPERVISE, routeEdge, Map.of(
                        StateKeys.NODE_KB, StateKeys.NODE_KB,
                        StateKeys.NODE_RESPOND, StateKeys.NODE_RESPOND))
                .addEdge(StateKeys.NODE_KB, StateKeys.NODE_RESPOND)
                .addEdge(StateKeys.NODE_RESPOND, StateGraph.END);

        return graph.compile(CompileConfig.builder().recursionLimit(20).build());
    }
}
