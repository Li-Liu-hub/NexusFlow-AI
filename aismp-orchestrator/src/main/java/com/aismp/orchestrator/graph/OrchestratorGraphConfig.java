package com.aismp.orchestrator.graph;

import com.aismp.orchestrator.client.DigitalHumanClient;
import com.aismp.orchestrator.client.TerminalApiClient;
import com.aismp.orchestrator.config.OrchestratorProperties;
import com.aismp.orchestrator.graph.nodes.AlgorithmNode;
import com.aismp.orchestrator.graph.nodes.ChartBuildNode;
import com.aismp.orchestrator.graph.nodes.ChartPlanNode;
import com.aismp.orchestrator.graph.nodes.CriticNode;
import com.aismp.orchestrator.graph.nodes.FetchNode;
import com.aismp.orchestrator.graph.nodes.KbNode;
import com.aismp.orchestrator.graph.nodes.ReportNode;
import com.aismp.orchestrator.graph.nodes.RespondNode;
import com.aismp.orchestrator.graph.nodes.SuperviseNode;
import com.aismp.orchestrator.graph.nodes.VerifyNumbersNode;
import com.aismp.orchestrator.llm.LlmRouter;
import com.aismp.orchestrator.llm.RouteLlmClient;
import com.aismp.orchestrator.service.ChartBuilder;
import com.aismp.orchestrator.service.SuggestionStore;
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
 * 图装配（迁移对照原 Python 版 {@code graph.py:1445 build_graph()}）。
 *
 * <p>拓扑：
 * <pre>
 *   START → supervise ─┬─(kb)────────→ kb ─────────────→ respond → END
 *                      ├─(replenish)→ fetch → algorithm → report → verify_numbers → critic
 *                      │                                          ↑                    │
 *                      │                                          └──(rework，上限 1 次)┘
 *                      │                                                               │(approve)
 *                      ├─(chart)───→ chart_plan → chart_build ────────────────────────→┤
 *                      └─(order)──────────────────────────────────────────────────────→┘
 * </pre>
 *
 * <p>recursionLimit=20 与原版一致：回炉环最多 1 次重算约 12 步，20 是双保险。
 */
@Configuration
public class OrchestratorGraphConfig {

    // 节点必须是 Spring Bean —— 图装配方法按参数注入它们。
    // 教训：这些类曾经都没注册成 Bean，而单测是手工 new 的，所以"编译过 + 单测绿"
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
    public FetchNode fetchNode(TerminalApiClient api, OrchestratorProperties properties) {
        return new FetchNode(api, properties);
    }

    @Bean
    public AlgorithmNode algorithmNode() {
        return new AlgorithmNode();
    }

    @Bean
    public ReportNode reportNode(SuggestionStore suggestions) {
        return new ReportNode(suggestions);
    }

    @Bean
    public VerifyNumbersNode verifyNumbersNode() {
        return new VerifyNumbersNode();
    }

    @Bean
    public CriticNode criticNode() {
        return new CriticNode();
    }

    @Bean
    public ChartPlanNode chartPlanNode(RouteLlmClient llm) {
        return new ChartPlanNode(llm);
    }

    @Bean
    public ChartBuildNode chartBuildNode(ChartBuilder builder, OrchestratorProperties properties) {
        return new ChartBuildNode(builder, properties);
    }

    @Bean
    public CompiledGraph orchestratorGraph(KeyStrategyFactory keyStrategyFactory,
                                           SuperviseNode superviseNode,
                                           KbNode kbNode,
                                           FetchNode fetchNode,
                                           AlgorithmNode algorithmNode,
                                           ReportNode reportNode,
                                           VerifyNumbersNode verifyNumbersNode,
                                           CriticNode criticNode,
                                           ChartPlanNode chartPlanNode,
                                           ChartBuildNode chartBuildNode,
                                           RespondNode respondNode) throws GraphStateException {

        AsyncEdgeAction routeEdge = state -> {
            Object route = state.data().get(StateKeys.ROUTE);
            String value = route == null ? StateKeys.ROUTE_KB : String.valueOf(route);
            String target = switch (value) {
                case StateKeys.ROUTE_KB -> StateKeys.NODE_KB;
                case StateKeys.ROUTE_REPLENISH -> StateKeys.NODE_FETCH;
                case StateKeys.ROUTE_CHART -> StateKeys.NODE_CHART_PLAN;
                default -> StateKeys.NODE_RESPOND;   // order 与未知路线：收口到 respond
            };
            return CompletableFuture.completedFuture(target);
        };

        // 质检闸门：数字自检不通过 → 回炉重算（CriticNode 自己保证只回一次）
        AsyncEdgeAction criticEdge = state -> {
            Object decision = state.data().get(StateKeys.META) instanceof Map<?, ?> meta
                    ? meta.get("critic_decision") : null;
            String target = "rework".equals(String.valueOf(decision))
                    ? StateKeys.NODE_ALGORITHM : StateKeys.NODE_RESPOND;
            return CompletableFuture.completedFuture(target);
        };

        StateGraph graph = new StateGraph("nexusflow-orchestrator", keyStrategyFactory)
                .addNode(StateKeys.NODE_SUPERVISE, node_async(superviseNode))
                .addNode(StateKeys.NODE_KB, node_async(kbNode))
                .addNode(StateKeys.NODE_FETCH, node_async(fetchNode))
                .addNode(StateKeys.NODE_ALGORITHM, node_async(algorithmNode))
                .addNode(StateKeys.NODE_REPORT, node_async(reportNode))
                .addNode(StateKeys.NODE_VERIFY, node_async(verifyNumbersNode))
                .addNode(StateKeys.NODE_CRITIC, node_async(criticNode))
                .addNode(StateKeys.NODE_CHART_PLAN, node_async(chartPlanNode))
                .addNode(StateKeys.NODE_CHART_BUILD, node_async(chartBuildNode))
                .addNode(StateKeys.NODE_RESPOND, node_async(respondNode))
                .addEdge(StateGraph.START, StateKeys.NODE_SUPERVISE)
                .addConditionalEdges(StateKeys.NODE_SUPERVISE, routeEdge, Map.of(
                        StateKeys.NODE_KB, StateKeys.NODE_KB,
                        StateKeys.NODE_FETCH, StateKeys.NODE_FETCH,
                        StateKeys.NODE_CHART_PLAN, StateKeys.NODE_CHART_PLAN,
                        StateKeys.NODE_RESPOND, StateKeys.NODE_RESPOND))
                .addEdge(StateKeys.NODE_KB, StateKeys.NODE_RESPOND)
                .addEdge(StateKeys.NODE_FETCH, StateKeys.NODE_ALGORITHM)
                .addEdge(StateKeys.NODE_ALGORITHM, StateKeys.NODE_REPORT)
                .addEdge(StateKeys.NODE_REPORT, StateKeys.NODE_VERIFY)
                .addEdge(StateKeys.NODE_VERIFY, StateKeys.NODE_CRITIC)
                .addConditionalEdges(StateKeys.NODE_CRITIC, criticEdge, Map.of(
                        StateKeys.NODE_ALGORITHM, StateKeys.NODE_ALGORITHM,
                        StateKeys.NODE_RESPOND, StateKeys.NODE_RESPOND))
                .addEdge(StateKeys.NODE_CHART_PLAN, StateKeys.NODE_CHART_BUILD)
                .addEdge(StateKeys.NODE_CHART_BUILD, StateKeys.NODE_RESPOND)
                .addEdge(StateKeys.NODE_RESPOND, StateGraph.END);

        return graph.compile(CompileConfig.builder().recursionLimit(20).build());
    }
}
