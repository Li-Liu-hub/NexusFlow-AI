package com.aismp.digitalhuman.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库向量化客户端（内核 → aismp-agent 内部接口，2026-10-06 批 D）。
 *
 * <p><b>为什么调 agent 而不是内核自己做</b>：向量库（PGVector）与嵌入模型（硅基流动 bge-m3）
 * 都归 aismp-agent；内核管账本（a_knowledge_base / a_knowledge_document）。
 * 上传文档时内核存文件 + 记账，把「切分 → 向量化」委托给 agent 的
 * {@code POST /inner/kb/ingest}，再把分片数回写文档表。
 *
 * <p>同机直连 8124（内网接口不经网关）；超时 120s —— 入库要调多次 embedding，
 * 大文档时慢是正常的。删除接口是尽力而为（失败只记日志，不让它卡住删除操作）。
 */
@Component
public class AgentKbClient {

    private static final Logger log = LoggerFactory.getLogger(AgentKbClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;

    public AgentKbClient(@Value("${agent.base-url:http://127.0.0.1:8124}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 入库结果：分片数与实际使用的嵌入模型名（回写文档表用）。 */
    public record IngestResult(int chunkCount, String embeddingModel) {
    }

    /**
     * 文本入库（幂等：agent 侧会先按 documentId 清旧分片）。
     *
     * @param chunkSize 切片目标长度（字符）：知识库级配置，null 时 agent 侧按默认 800 处理
     * @throws IllegalStateException 下游非 200 或返回 error 时抛出，由调用方标记文档 failed
     */
    public IngestResult ingest(String documentId, String knowledgeBaseId, String sourceName,
                               String category, String content, Integer chunkSize) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", documentId);
        body.put("knowledgeBaseId", knowledgeBaseId);
        body.put("sourceName", sourceName);
        body.put("category", category);
        body.put("content", content);
        if (chunkSize != null) {
            body.put("chunkSize", chunkSize);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/inner/kb/ingest"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("agent 入库接口 HTTP " + resp.statusCode());
        }
        JsonNode node = mapper.readTree(resp.body());
        if (node.has("error")) {
            throw new IllegalStateException(node.get("error").asText());
        }
        return new IngestResult(node.path("chunkCount").asInt(),
                node.path("embeddingModel").asText(""));
    }

    /** 删除某文档的全部向量（尽力而为：失败不影响管理面的删除结果）。 */
    public void delete(String documentId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/inner/kb/delete"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(Map.of("documentId", documentId)),
                            StandardCharsets.UTF_8))
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("删除文档向量失败（不影响管理面删除）：document={} 原因={}", documentId, e.getMessage());
        }
    }
}