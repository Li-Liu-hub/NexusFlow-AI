package com.aismp.digitalhuman.tool;

import com.aismp.digitalhuman.model.ToolDefPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 知识检索型工具：把「问知识库」变成一个可挂载的工具。
 *
 * <p><b>它解决什么问题</b>：数字员工接管问答后，若没有检索能力，
 * 流程类问题只能靠模型内置知识回答 —— 无法引用手册原文、口径不一致也发现不了，
 * 等于把「检索增强」降级成「裸模型问答」。
 *
 * <p><b>为什么复用 Java agent 的 RAG 而不是在内核重做检索</b>：
 * 那套 RAG 管线（文档加载 → embedding → pgvector HNSW → 查询重写+分类 filter → 增强问答）
 * 经过五轮迭代验证，知识库已有 16 篇手册。重写一遍既浪费又更差，
 * 与编排层 kb_node 当年的取舍一致 —— 只做协议适配。
 *
 * <p><b>调用链</b>：数字员工 → 网关（AK/SK 签名）→ aismp-agent 的 RAG 接口 → 返回基于手册的回答。
 * 走网关是为了复用平台的鉴权与计量，与其它服务调用保持一致。
 *
 * <p><b>config_json 结构</b>：
 * <pre>{"endpoint":"/oa/chat/sync","timeoutSeconds":120}</pre>
 */
public class KnowledgeRetrievalToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalToolCallback.class);

    private static final String DEFAULT_ENDPOINT = "/oa/chat/sync";
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private final ToolDefPO def;
    private final ObjectMapper json;
    private final String gatewayBaseUrl;
    private final String accessKey;
    private final String secretKey;
    private final String endpoint;
    private final Duration timeout;
    private final HttpClient httpClient;

    public KnowledgeRetrievalToolCallback(ToolDefPO def, ObjectMapper json,
                                          String gatewayBaseUrl, String accessKey, String secretKey) {
        this.def = def;
        this.json = json;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.accessKey = accessKey;
        this.secretKey = secretKey;

        // 从 config_json 读类型专属配置，缺失时用默认值（单靠默认值也能跑起来）
        String ep = DEFAULT_ENDPOINT;
        int ts = DEFAULT_TIMEOUT_SECONDS;
        if (def.getConfigJson() != null && !def.getConfigJson().isBlank()) {
            try {
                Map<?, ?> cfg = json.readValue(def.getConfigJson(), Map.class);
                if (cfg.get("endpoint") != null) {
                    ep = String.valueOf(cfg.get("endpoint"));
                }
                if (cfg.get("timeoutSeconds") != null) {
                    ts = Integer.parseInt(String.valueOf(cfg.get("timeoutSeconds")));
                }
            } catch (Exception e) {
                log.warn("工具 [{}] 的 config_json 解析失败，使用默认配置：{}", def.getToolName(), e.getMessage());
            }
        }
        this.endpoint = ep;
        this.timeout = Duration.ofSeconds(ts);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(def.getToolName())
                .description(def.getToolDesc())
                .inputSchema(def.getInputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        long start = System.currentTimeMillis();
        try {
            String query = extractQuery(toolInput);
            if (query == null || query.isBlank()) {
                return errorJson("缺少 query 参数");
            }

            // body 既要作为请求体，也要进 header 供网关验签 —— header 只允许 ASCII，
            // 所以中文必须先转成 Unicode 转义（转义是合法 JSON，服务端解析后内容不变）
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", query);
            // 必须带 conversationId：agent 侧 SelectiveMemoryAdvisor 的 param 不接受 null，
            // 缺了它会 500（10-01 联调坑 #5）。检索是独立子调用，用随机 id 不污染会话记忆。
            payload.put("conversationId", UUID.randomUUID().toString());
            String rawBody = json.writeValueAsString(payload);
            String asciiBody = toAsciiJson(rawBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayBaseUrl + endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("accessKey", accessKey)
                    .header("nonce", GatewaySigner.nonce())
                    .header("timestamp", GatewaySigner.timestamp())
                    .header("sign", GatewaySigner.sign(asciiBody, secretKey))
                    .header("body", asciiBody)
                    .POST(HttpRequest.BodyPublishers.ofString(asciiBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            long cost = System.currentTimeMillis() - start;
            if (response.statusCode() != 200) {
                // 不把响应体塞给模型：容器/网关的错误页是 HTML，塞进去既占 token 又干扰判断，
                // 模型看到一堆标签反而会"编"出失真的原因。只回状态码，让它如实转述即可。
                log.warn("知识检索工具 [{}] 下游异常 status={} cost={}ms",
                        def.getToolName(), response.statusCode(), cost);
                return errorJson("知识手册检索服务暂时不可用（status=" + response.statusCode() + "）");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", response.body());
            result.put("costMs", cost);
            return json.writeValueAsString(result);

        } catch (Exception e) {
            log.warn("知识检索工具 [{}] 执行失败：{}", def.getToolName(), e.getMessage());
            return errorJson(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 从工具入参里取 query。
     *
     * <p><b>必须容错</b>：工具入参格式取决于模型，实测它有时不按 JSON 传，
     * 而是直接把问题原文塞进来。首版严格按 JSON 解析，结果每次调用都在这里抛异常 ——
     * 更麻烦的是，模型会把这个失败"解释"成"服务端 HTTP 500"，
     * 让人误以为是下游服务的问题（实际连请求都没发出去）。
     */
    private String extractQuery(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> in = json.readValue(toolInput, Map.class);
            Object q = in.get("query");
            if (q != null) {
                return String.valueOf(q);
            }
            // 是 JSON 但没有 query 字段：取第一个字符串值兜底
            for (Object v : in.values()) {
                if (v instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
            return null;
        } catch (Exception e) {
            // 不是 JSON —— 模型直接传了问题原文，整体当作 query
            return toolInput.trim();
        }
    }

    private String errorJson(String message) {
        try {
            return json.writeValueAsString(Map.of("error", message));
        } catch (Exception ignored) {
            return "{\"error\":\"知识检索失败\"}";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 把 JSON 里的非 ASCII 字符转成 Unicode 转义。
     *
     * <p>注意：源码里不能直接出现「反斜杠 + u」的字面量 —— Java 词法分析阶段会把它当
     * Unicode 转义处理，即使写在注释里也会报「非法的 Unicode 转义」。
     * 所以这里必须拆成两个字符串字面量拼接。
     */
    private static String toAsciiJson(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 32);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x80) {
                sb.append(c);
            } else {
                sb.append("\\" + "u").append(String.format("%04x", (int) c));
            }
        }
        return sb.toString();
    }
}
