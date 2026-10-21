package com.aismp.orchestrator;

import com.aismp.orchestrator.client.DigitalHumanClient;
import com.aismp.orchestrator.client.PortalTokenProvider;
import com.aismp.orchestrator.client.TerminalApiClient;
import com.aismp.orchestrator.config.OrchestratorProperties;
import com.aismp.orchestrator.graph.GraphStateConfig;
import com.aismp.orchestrator.graph.OrchestratorGraphConfig;
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
import com.alibaba.cloud.ai.graph.CompiledGraph;
import org.springframework.web.client.RestClient;

/**
 * 测试用的图装配工厂。
 *
 * <p>为什么要有它：图装配的方法签名会随着路线补齐而变（这轮从 3 个节点变成 9 个），
 * 如果每个测试各自 new 一遍，每加一条路线就要改一堆测试。集中一处之后，
 * 加节点只改这里。
 *
 * <p>默认参数刻意选成"外部依赖全挂"：没有 LLM Key、没有服务账号、
 * 终端接口指向 127.0.0.1（测试机上没有），用来验证降级路径。
 */
public final class TestGraphs {

    private TestGraphs() {
    }

    public static CompiledGraph build(OrchestratorProperties props) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        LlmRouter router = new LlmRouter(props, query -> {
            throw new IllegalStateException("测试不应调用路由 LLM");
        });
        return build(props, router, query -> {
            throw new IllegalStateException("测试不应调用选型 LLM");
        }, builder);
    }

    public static CompiledGraph build(OrchestratorProperties props, LlmRouter router,
                                      RouteLlmClient chartLlm) throws Exception {
        return build(props, router, chartLlm, RestClient.builder());
    }

    private static CompiledGraph build(OrchestratorProperties props, LlmRouter router,
                                       RouteLlmClient chartLlm, RestClient.Builder builder) throws Exception {
        DigitalHumanClient digitalHuman = new DigitalHumanClient(props, builder);
        TerminalApiClient terminal = new TerminalApiClient(props, builder,
                new PortalTokenProvider(props, builder));
        ChartBuilder chartBuilder = new ChartBuilder(terminal, props);
        return new OrchestratorGraphConfig().orchestratorGraph(
                new GraphStateConfig().keyStrategyFactory(),
                new SuperviseNode(router),
                new KbNode(digitalHuman, props),
                new FetchNode(terminal, props),
                new AlgorithmNode(),
                new ReportNode(new SuggestionStore()),
                new VerifyNumbersNode(),
                new CriticNode(),
                new ChartPlanNode(chartLlm),
                new ChartBuildNode(chartBuilder, props),
                new RespondNode());
    }
}
