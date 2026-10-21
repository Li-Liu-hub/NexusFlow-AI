package com.aismp.aismpagent.rag;

import com.aismp.aismpagent.config.KbHybridProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

/**
 * Rerank 客户端的两个关键行为：**能重排**、**挂了不能拖垮链路**。
 *
 * <p>rerank 是额外的一次外部调用，它失败时如果抛异常，用户就拿不到答案了 ——
 * 而正确答案往往就在 RRF 结果里，只是顺序差一点。所以这里的核心断言是「失败返回 null」，
 * 由调用方沿用原顺序。
 */
class RerankClientTest {

    private static KbHybridProperties props(boolean enabled, String apiKey) {
        KbHybridProperties p = new KbHybridProperties();
        p.getRerank().setEnabled(enabled);
        p.getRerank().setApiKey(apiKey);
        p.getRerank().setBaseUrl("https://rerank.example.com");
        p.getRerank().setModel("BAAI/bge-reranker-v2-m3");
        return p;
    }

    @Test
    void disabledOrMissingKeySkipsTheCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThat(new RerankClient(props(false, "sk-x"), builder.build()).rerank("q", List.of("a"), 1)).isNull();
        assertThat(new RerankClient(props(true, ""), builder.build()).rerank("q", List.of("a"), 1)).isNull();
        // 一次请求都没发出去
        server.verify();
    }

    @Test
    void reordersAccordingToModelScores() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("https://rerank.example.com/v1/rerank"))
                .andExpect(content().string(containsString("BAAI/bge-reranker-v2-m3")))
                .andExpect(content().string(containsString("库龄分布")))
                .andRespond(withSuccess(
                        "{\"results\":[{\"index\":1,\"relevance_score\":0.93},"
                                + "{\"index\":0,\"relevance_score\":0.41}]}",
                        MediaType.APPLICATION_JSON));

        RerankClient client = new RerankClient(props(true, "sk-test"), builder.build());
        List<String> out = client.rerank("库龄分布", List.of("弱相关片段", "强相关片段"), 2);

        assertThat(out).containsExactly("强相关片段", "弱相关片段");
        server.verify();
    }

    @Test
    void apiErrorDegradesToNull() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("https://rerank.example.com/v1/rerank"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        RerankClient client = new RerankClient(props(true, "sk-test"), builder.build());
        assertThat(client.rerank("q", List.of("a", "b"), 2)).isNull();
    }

    @Test
    void malformedResponseDegradesToNull() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("https://rerank.example.com/v1/rerank"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        RerankClient client = new RerankClient(props(true, "sk-test"), builder.build());
        assertThat(client.rerank("q", List.of("a"), 1)).isNull();
    }

    @Test
    void outOfRangeIndexIsIgnored() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("https://rerank.example.com/v1/rerank"))
                .andRespond(withSuccess(
                        "{\"results\":[{\"index\":7,\"relevance_score\":0.9}]}", MediaType.APPLICATION_JSON));

        RerankClient client = new RerankClient(props(true, "sk-test"), builder.build());
        assertThat(client.rerank("q", List.of("a", "b"), 2)).isNull();
    }
}
