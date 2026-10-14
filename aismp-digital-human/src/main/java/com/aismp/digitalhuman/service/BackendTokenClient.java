package com.aismp.digitalhuman.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台账本客户端（→ backend 内部接口，2026-10-06 批 A）。
 *
 * <p>配额与用量账本迁到 backend 后，内核在对话入口/出口通过这里完成：
 * <pre>
 *   GET  /api/inner/token/check?userId=  → 配额闸门（返回拒绝原因或空）
 *   POST /api/inner/token/charge         → 用量落账
 * </pre>
 *
 * <p>用 JDK HttpClient（无额外依赖）；超时 3s，失败由调用方兜底
 * （配额失败放行、落账失败忽略 —— 计量是旁路）。
 */
@Component
public class BackendTokenClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;

    public BackendTokenClient(@Value("${backend.base-url:http://127.0.0.1:7529}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 配额闸门：返回拒绝原因（null = 放行）；调用异常由上层兜底放行。 */
    public String checkQuota(String userId) throws Exception {
        String url = baseUrl + "/api/inner/token/check?userId="
                + URLEncoder.encode(userId == null ? "" : userId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        JsonNode node = mapper.readTree(resp.body());
        String denied = node.path("denied").asText("");
        return denied.isEmpty() ? null : denied;
    }

    /** 用量落账（一次性上报本次请求的累计用量）。 */
    public void charge(String userId, String agentCode, String model, int requests,
                       long promptTokens, long completionTokens, long totalTokens, int toolCalls)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("agentCode", agentCode);
        body.put("model", model);
        body.put("requests", requests);
        body.put("promptTokens", promptTokens);
        body.put("completionTokens", completionTokens);
        body.put("totalTokens", totalTokens);
        body.put("toolCalls", toolCalls);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/inner/token/charge"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        http.send(request, HttpResponse.BodyHandlers.discarding());
    }
}