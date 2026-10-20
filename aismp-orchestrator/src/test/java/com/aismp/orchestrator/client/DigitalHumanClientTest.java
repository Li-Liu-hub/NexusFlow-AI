package com.aismp.orchestrator.client;

import com.aismp.orchestrator.config.OrchestratorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 数字员工内核客户端：令牌缓存、401 重登、业务错误上抛。
 *
 * <p>为什么值得单测：这段代码以前在 Python 侧踩过「内核连不上时裸抛 ConnectError，
 * 被外层兜成信息量最低的『编排异常』」；现在把它固定在测试里，错误必须是人话。
 */
class DigitalHumanClientTest {

    private static final String BASE = "http://127.0.0.1:8126";

    private static OrchestratorProperties props() {
        OrchestratorProperties p = new OrchestratorProperties();
        p.getDigitalHuman().setDirectUrl(BASE);
        p.getDigitalHuman().setServiceUser("svc_orchestrator");
        p.getDigitalHuman().setServicePassword("pwd");
        return p;
    }

    @Test
    void logsInOnceThenReusesToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DigitalHumanClient client = new DigitalHumanClient(props(), builder);

        server.expect(requestTo(BASE + "/auth/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\":\"tok-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/agent/terminal_manager/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("{\"answer\":\"第一次\",\"version\":7}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/agent/terminal_manager/chat"))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("{\"answer\":\"第二次\",\"version\":7}",
                        MediaType.APPLICATION_JSON));

        DigitalHumanClient.ChatResult first =
                client.chat("terminal_manager", "问一", List.of("9B00"), "conv-1");
        DigitalHumanClient.ChatResult second =
                client.chat("terminal_manager", "问二", null, "conv-1");

        assertThat(first.answer()).isEqualTo("第一次");
        assertThat(first.version()).isEqualTo(7);
        assertThat(second.answer()).isEqualTo("第二次");
        // 三次请求（1 次登录 + 2 次对话）全部命中期望 → 令牌确实被复用
        server.verify();
    }

    @Test
    void reLoginsAndRetriesOnceWhenTokenExpired() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DigitalHumanClient client = new DigitalHumanClient(props(), builder);

        server.expect(requestTo(BASE + "/auth/login"))
                .andRespond(withSuccess("{\"token\":\"old\"}", MediaType.APPLICATION_JSON));
        // 第一次对话 401（令牌被清）
        server.expect(requestTo(BASE + "/agent/terminal_manager/chat"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        // 重登 + 重试
        server.expect(requestTo(BASE + "/auth/login"))
                .andRespond(withSuccess("{\"token\":\"new\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/agent/terminal_manager/chat"))
                .andExpect(header("Authorization", "Bearer new"))
                .andRespond(withSuccess("{\"answer\":\"重试成功\"}", MediaType.APPLICATION_JSON));

        assertThat(client.chat("terminal_manager", "问", null, null).answer())
                .isEqualTo("重试成功");
        server.verify();
    }

    @Test
    void unwrapsCodeDataEnvelope() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DigitalHumanClient client = new DigitalHumanClient(props(), builder);

        server.expect(requestTo(BASE + "/auth/login"))
                .andRespond(withSuccess("{\"data\":{\"token\":\"t\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/agent/terminal_manager/chat"))
                .andRespond(withSuccess(
                        "{\"code\":0,\"data\":{\"answer\":\"包了一层\",\"version\":2}}",
                        MediaType.APPLICATION_JSON));

        DigitalHumanClient.ChatResult r = client.chat("terminal_manager", "问", null, null);
        assertThat(r.answer()).isEqualTo("包了一层");
        assertThat(r.version()).isEqualTo(2);
    }

    @Test
    void businessErrorCodeIsSurfacedWithMessage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DigitalHumanClient client = new DigitalHumanClient(props(), builder);

        server.expect(requestTo(BASE + "/auth/login"))
                .andRespond(withSuccess("{\"token\":\"t\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/agent/terminal_manager/chat"))
                .andRespond(withSuccess("{\"code\":500,\"message\":\"模型超时\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat("terminal_manager", "问", null, null))
                .isInstanceOf(DigitalHumanClient.DigitalHumanException.class)
                .hasMessageContaining("模型超时");
    }

    @Test
    void missingServiceAccountFailsWithActionableMessage() {
        OrchestratorProperties p = props();
        p.getDigitalHuman().setServiceUser("");
        DigitalHumanClient client = new DigitalHumanClient(p, RestClient.builder());

        assertThatThrownBy(() -> client.chat("terminal_manager", "问", null, null))
                .isInstanceOf(DigitalHumanClient.DigitalHumanException.class)
                .hasMessageContaining("未配置服务账号");
    }

    @Test
    void unreachableKernelDegradesToDomainExceptionNotConnectError() {
        OrchestratorProperties p = props();
        p.getDigitalHuman().setDirectUrl("http://127.0.0.1:1"); // 必然连不上
        DigitalHumanClient client = new DigitalHumanClient(p, RestClient.builder());

        // 关键：错误信息里必须带实际地址（排查时最有用的一条）
        assertThatThrownBy(() -> client.chat("terminal_manager", "问", null, null))
                .isInstanceOf(DigitalHumanClient.DigitalHumanException.class)
                .hasMessageContaining("内核不可达")
                .hasMessageContaining("127.0.0.1:1");
    }
}
