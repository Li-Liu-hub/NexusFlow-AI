package com.aismp.orchestrator.api;

import com.aismp.orchestrator.client.DigitalHumanClient;
import com.aismp.orchestrator.config.OrchestratorProperties;
import com.aismp.orchestrator.graph.GraphStateConfig;
import com.aismp.orchestrator.graph.OrchestratorGraphConfig;
import com.aismp.orchestrator.graph.nodes.KbNode;
import com.aismp.orchestrator.graph.nodes.RespondNode;
import com.aismp.orchestrator.graph.nodes.SuperviseNode;
import com.aismp.orchestrator.llm.LlmRouter;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 帧契约测试（不启动容器、不连网络）。
 *
 * <p>前端是按 event 名和 data 字段取值的，**帧名改一个字就是改协议** ——
 * 这类事故在浏览器里表现成「页面转圈没反应」，排查成本极高。
 * 所以这里直接断言原始报文里五个关键帧都在、且 result 里带 conversation_id。
 */
class ChatControllerTest {

    private static CompiledGraph graph() throws Exception {
        OrchestratorProperties props = new OrchestratorProperties(); // 无 Key、无服务账号 → 全降级
        RestClient.Builder builder = RestClient.builder();
        LlmRouter router = new LlmRouter(props, query -> {
            throw new IllegalStateException("本测试不应调用 LLM");
        });
        DigitalHumanClient client = new DigitalHumanClient(props, builder);
        return new OrchestratorGraphConfig().orchestratorGraph(
                new GraphStateConfig().keyStrategyFactory(),
                new SuperviseNode(router),
                new KbNode(client, props),
                new RespondNode());
    }

    @Test
    void healthEndpointReportsImpl() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(graph())).build();
        String body = mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("aismp-orchestrator").contains("spring-ai-alibaba-graph");
    }

    @Test
    void chatStreamEmitsStartProgressAndResultFrames() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(graph())).build();

        MvcResult pending = mvc.perform(post("/orchestrator/v1/chat/stream")
                        .param("query", "终端故障返厂怎么登记？"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // SseEmitter(0L) = 不超时，MockMvc 会按 0ms 等待而误判「异步未完成」——先给异步上下文一个真实超时
        pending.getRequest().getAsyncContext().setTimeout(10_000L);
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());

        String body = pending.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 五个帧里 error 是异常路径，正常流程应出现 start / progress / agent_step / result
        assertThat(body).contains("event:start");
        assertThat(body).contains("event:progress");
        assertThat(body).contains("event:agent_step");
        assertThat(body).contains("event:result");
        assertThat(body).doesNotContain("event:error");

        // 节点名要能被前端/Harness 识别
        assertThat(body).contains("supervise").contains("respond");
        // 会话 id 必须回传（门户续聊依赖它）
        assertThat(body).contains("conversation_id");
        // 降级话术走 result，而不是 error
        assertThat(body).contains("数字员工暂时不可用");
    }

    @Test
    void suppliedConversationIdIsEchoedBack() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(graph())).build();

        MvcResult pending = mvc.perform(post("/orchestrator/v1/chat/stream")
                        .param("query", "你好")
                        .param("conversation_id", "conv-fixed-1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // SseEmitter(0L) = 不超时，MockMvc 会按 0ms 等待而误判「异步未完成」——先给异步上下文一个真实超时
        pending.getRequest().getAsyncContext().setTimeout(10_000L);
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
        assertThat(pending.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("conv-fixed-1");
    }

    @Test
    void missingQueryReturnsClientError() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(graph())).build();
        mvc.perform(post("/orchestrator/v1/chat/stream")).andExpect(status().isBadRequest());
    }
}
