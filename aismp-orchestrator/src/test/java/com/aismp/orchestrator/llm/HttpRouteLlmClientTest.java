package com.aismp.orchestrator.llm;

import com.aismp.orchestrator.config.OrchestratorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * LLM 路由通道（HTTP 层）的单测。
 *
 * <p>为什么单独测这一层：路由的「解析规则」由 {@link LlmRouter} 负责、已脱网覆盖；
 * 但真正把请求发出去的那部分（URL、鉴权头、请求体形状、响应结构）一直没人测 ——
 * 而它最容易在换模型供应商时静默坏掉（body 字段名一变，服务就开始永远降级 kb）。
 *
 * <p>用 MockRestServiceServer 拦在 RestClient 的请求工厂上，不发真实网络请求。
 */
class HttpRouteLlmClientTest {

    private static final String BASE = "https://llm.example.com/v1";

    private static OrchestratorProperties props() {
        OrchestratorProperties p = new OrchestratorProperties();
        p.getLlm().setBaseUrl(BASE);
        p.getLlm().setApiKey("sk-unit-test");
        p.getLlm().setModel("glm-5.2");
        return p;
    }

    @Test
    void sendsOpenAiCompatibleRequestAndParsesContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpRouteLlmClient client = new HttpRouteLlmClient(props(), builder);

        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-unit-test"))
                // 请求体里的三处关键契约：模型名、温度 0（路由要确定性）、原问题要进 prompt
                .andExpect(content().string(containsString("glm-5.2")))
                .andExpect(content().string(containsString("\"temperature\":0")))
                .andExpect(content().string(containsString("哪个仓该补货")))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"replenish\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.complete("哪个仓该补货")).isEqualTo("replenish");
        server.verify();
    }

    @Test
    void toleratesExtraWhitespaceInContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpRouteLlmClient client = new HttpRouteLlmClient(props(), builder);

        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"  chart \\n\"}}]}",
                        MediaType.APPLICATION_JSON));

        // 清洗留给 LlmRouter（它做 trim/小写/包含匹配），这层只保证原样取到
        assertThat(client.complete("画个饼图")).contains("chart");
    }

    @Test
    void malformedResponseIsReportedNotSwallowed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpRouteLlmClient client = new HttpRouteLlmClient(props(), builder);

        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("choices");
    }

    @Test
    void missingMessageNodeIsReported() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpRouteLlmClient client = new HttpRouteLlmClient(props(), builder);

        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"text\":\"hi\"}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message");
    }
}
