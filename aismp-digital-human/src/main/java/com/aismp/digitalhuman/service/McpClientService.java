package com.aismp.digitalhuman.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aismp.digitalhuman.model.McpServerPO;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端服务（批 E，2026-10-06）：连接外部 MCP server、探测工具、调用工具。
 *
 * <p><b>为什么用官方 MCP SDK 而不是手写 JSON-RPC</b>：SSE 传输要先建事件流、
 * 从 endpoint 事件里拿消息端点再回投 JSON-RPC —— 手写等于重新实现一遍协议层，
 * 且握手/会话/重连的边界情况极多。用 SDK（io.modelcontextprotocol.sdk:mcp，版本与
 * spring-ai-mcp 2.0.0-M1 依赖的 0.17.0 对齐）把这段不可见复杂度收掉，
 * 内核只关心「服务器配置 → 工具列表 / 调用结果」。
 *
 * <p><b>连接策略：每次调用现连现关</b>。MCP 客户端绑定 SSE 长连接与会话，
 * 而数字员工对话是低频行为（工具调用按次发生）；为每次调用建连虽然多一次握手，
 * 但完全无状态、没有连接泄漏与断线重连问题，也避免给内核引入后台连接管理。
 */
@Service
public class McpClientService {

    private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 建立连接并完成 initialize 握手。
     *
     * <p>返回的 client 由调用方负责 close（实现 AutoCloseable，用 try-with-resources）。
     */
    public McpSyncClient connect(McpServerPO server) {
        String transport = server.getTransport() == null ? "SSE" : server.getTransport().toUpperCase();
        Map<String, String> headers = parseHeaders(server.getHeadersJson());

        McpClientTransport mcpTransport = switch (transport) {
            case "SSE" -> {
                var builder = HttpClientSseClientTransport.builder(server.getBaseUrl())
                        .connectTimeout(CONNECT_TIMEOUT);
                if (!headers.isEmpty()) {
                    builder.customizeRequest(rb -> headers.forEach(rb::header));
                }
                yield builder.build();
            }
            case "HTTP" -> {
                var builder = HttpClientStreamableHttpTransport.builder(server.getBaseUrl())
                        .connectTimeout(CONNECT_TIMEOUT);
                if (!headers.isEmpty()) {
                    builder.customizeRequest(rb -> headers.forEach(rb::header));
                }
                yield builder.build();
            }
            default -> throw new IllegalArgumentException(
                    "暂不支持的传输类型 [" + transport + "]（仅 SSE / HTTP）");
        };

        McpSyncClient client = McpClient.sync(mcpTransport)
                .requestTimeout(REQUEST_TIMEOUT)
                .clientInfo(new McpSchema.Implementation("aismp-digital-human", "1.0.0"))
                .build();
        // 显式握手：失败要在这里抛出（地址错/服务未启动），而不是等到 listTools 才报
        client.initialize();
        return client;
    }

    /** 探测：返回远端 server 信息与工具清单（inputSchema 已序列化成 JSON 字符串，可直接存工具定义）。 */
    public Map<String, Object> probe(McpServerPO server) throws Exception {
        long start = System.currentTimeMillis();
        try (McpSyncClient client = connect(server)) {
            McpSchema.Implementation info = client.getServerInfo();
            List<McpSchema.Tool> tools = client.listTools().tools();

            List<Map<String, Object>> toolList = new ArrayList<>();
            for (McpSchema.Tool tool : tools) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", tool.name());
                item.put("description", tool.description());
                item.put("inputSchema", tool.inputSchema() == null
                        ? null : json.writeValueAsString(tool.inputSchema()));
                toolList.add(item);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("serverName", info == null ? null : info.name());
            result.put("serverVersion", info == null ? null : info.version());
            result.put("tools", toolList);
            result.put("elapsedMs", System.currentTimeMillis() - start);
            return result;
        }
    }

    /**
     * 调用远端工具。
     *
     * @param toolName 远端工具名（a_llm_tool.config_json 的 toolName）
     * @param args     工具入参（与远端 inputSchema 对应）
     * @return 远端返回的文本（MCP 的 TextContent 拼接；文本就是给模型读的，不再二次包装）
     */
    public String callTool(McpServerPO server, String toolName, Map<String, Object> args) {
        try (McpSyncClient client = connect(server)) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, args));
            String text = extractText(result);
            if (Boolean.TRUE.equals(result.isError())) {
                throw new IllegalStateException("远端工具返回错误: " + text);
            }
            return text;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("MCP 工具调用失败 server={} tool={}：{}", server.getServerCode(), toolName, e.getMessage());
            throw new IllegalStateException("MCP 调用失败（" + server.getServerCode() + "/" + toolName + "）："
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** 拼接 CallToolResult 的文本内容；非文本内容（图片等）只标注类型不传递（模型读不了二进制）。 */
    private String extractText(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return result.structuredContent() != null
                    ? String.valueOf(result.structuredContent()) : "(无内容)";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent t) {
                sb.append(t.text());
            } else {
                sb.append("[非文本内容: ").append(c.type()).append("]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** headers_json → Map（供传输层注入请求头）；解析失败直接抛错 —— 带错凭证的静默请求不如明确失败。 */
    private Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> raw = json.readValue(headersJson, Map.class);
            Map<String, String> headers = new LinkedHashMap<>();
            raw.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
            return headers;
        } catch (Exception e) {
            throw new IllegalArgumentException("headers_json 不是合法 JSON 对象: " + e.getMessage());
        }
    }
}