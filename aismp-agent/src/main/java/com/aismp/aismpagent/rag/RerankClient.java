package com.aismp.aismpagent.rag;

import com.aismp.aismpagent.config.KbHybridProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rerank 客户端（硅基流动 {@code /v1/rerank}，模型 {@code BAAI/bge-reranker-v2-m3}）。
 *
 * <p>为什么值得多一次外部调用：向量检索是"双塔"——问题和片段各自独立编码，
 * 只有粗粒度的语义接近；rerank 是"交叉编码"，问题和片段一起过模型，能分辨
 * 「哪条规定适用于这个场景」这类细粒度差别。代价是延迟（实测 200~400ms）。
 *
 * <p><b>失败必须降级而不是报错</b>：rerank 是增强项，挂了就退回 RRF 顺序，
 * 用户得到的答案质量略降，但不会拿不到答案。超时设 3 秒，宁可放弃也不拖住整条链路。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private final KbHybridProperties properties;
    private final RestClient restClient;

    @Autowired
    public RerankClient(KbHybridProperties properties) {
        this(properties, buildDefaultClient(properties));
    }

    /** 供测试注入（绑 MockRestServiceServer 用） */
    RerankClient(KbHybridProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient buildDefaultClient(KbHybridProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(properties.getRerank().getTimeoutSeconds()));
        return RestClient.builder().requestFactory(factory).build();
    }

    public boolean enabled() {
        KbHybridProperties.Rerank cfg = properties.getRerank();
        return cfg.isEnabled() && cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    /**
     * 对候选片段重排。
     *
     * @param query     用户问题
     * @param documents 候选片段（顺序即 RRF 结果）
     * @param topN      期望保留条数
     * @return 重排后的片段；未启用或调用失败时返回 {@code null}（调用方沿用原顺序）
     */
    @SuppressWarnings("unchecked")
    public List<String> rerank(String query, List<String> documents, int topN) {
        if (!enabled() || documents == null || documents.isEmpty()) {
            return null;
        }
        KbHybridProperties.Rerank cfg = properties.getRerank();
        Map<String, Object> body = new HashMap<>();
        body.put("model", cfg.getModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", Math.min(topN, documents.size()));
        body.put("return_documents", false);

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> resp = restClient.post()
                    .uri(cfg.getBaseUrl() + "/v1/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null || !(resp.get("results") instanceof List<?> results) || results.isEmpty()) {
                log.warn("rerank 返回结构异常，沿用 RRF 顺序");
                return null;
            }
            List<String> ordered = new ArrayList<>();
            for (Object item : results) {
                if (item instanceof Map<?, ?> m) {
                    Object idx = m.get("index");
                    if (idx instanceof Number n) {
                        int i = n.intValue();
                        if (i >= 0 && i < documents.size()) {
                            ordered.add(documents.get(i));
                        }
                    }
                }
            }
            if (ordered.isEmpty()) {
                return null;
            }
            log.info("rerank 完成：{} 条候选 → {} 条，耗时 {}ms",
                    documents.size(), ordered.size(), System.currentTimeMillis() - start);
            return ordered;
        } catch (RuntimeException e) {
            log.warn("rerank 调用失败（沿用 RRF 顺序，耗时 {}ms）：{}",
                    System.currentTimeMillis() - start, e.getMessage());
            return null;
        }
    }
}
