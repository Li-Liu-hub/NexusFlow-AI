package com.aismp.digitalhuman.tool;

import com.aismp.digitalhuman.model.McpServerPO;
import com.aismp.digitalhuman.model.ToolDefPO;
import com.aismp.digitalhuman.service.McpClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 型工具：把外部 MCP server 的一个远端工具接成平台可挂载的 {@link ToolCallback}。
 *
 * <p><b>调用链</b>：数字员工 → 本回调 → {@link McpClientService}（按 a_mcp_server 的连接配置
 * 现连现关）→ 远端 MCP server 的 CallTool。
 *
 * <p><b>config_json 结构</b>（在 {@link com.aismp.digitalhuman.tool.builder.McpToolBuilder} 解析）：
 * <pre>{"serverCode":"aismp-agent-mcp","toolName":"queryOrderStatus"}</pre>
 * 工具自身的 name/description/inputSchema 存 a_llm_tool 的列（由探测结果的「一键生成」填好）——
 * 运行时不再要求远端在线，远端掉线只影响调用，不影响装配。
 */
public class McpToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(McpToolCallback.class);

    private final ToolDefPO def;
    private final McpServerPO server;
    /** 远端工具名（与平台工具名可以不同：平台上可能重命名过）。 */
    private final String remoteToolName;
    private final McpClientService clientService;
    private final ObjectMapper json;

    public McpToolCallback(ToolDefPO def, McpServerPO server, String remoteToolName,
                           McpClientService clientService, ObjectMapper json) {
        this.def = def;
        this.server = server;
        this.remoteToolName = remoteToolName;
        this.clientService = clientService;
        this.json = json;
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
        Map<String, Object> args = parseArgs(toolInput);
        if (args == null) {
            return errorJson("工具入参解析失败（既不是 JSON 对象，也无法按 schema 兜底）: "
                    + truncate(toolInput, 120));
        }
        try {
            String text = clientService.callTool(server, remoteToolName, args);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("result", text);
            result.put("costMs", System.currentTimeMillis() - start);
            return json.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("MCP 工具 [{}] 执行失败：{}", def.getToolName(), e.getMessage());
            return errorJson(e.getMessage());
        }
    }

    /**
     * 解析工具入参。
     *
     * <p><b>必须容错</b>：与知识检索工具踩过同一个坑 —— 模型有时不按 JSON 传，
     * 而是把问题原文直接塞进来。这里先按 JSON 对象解析；失败则按 inputSchema 的
     * 第一个属性名兜底包一层（MCP 远端工具大多是单参数），仍失败才报错。
     */
    private Map<String, Object> parseArgs(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> in = json.readValue(toolInput, Map.class);
            Map<String, Object> args = new LinkedHashMap<>();
            in.forEach((k, v) -> args.put(String.valueOf(k), v));
            return args;
        } catch (Exception e) {
            String fallbackKey = firstSchemaProperty();
            if (fallbackKey != null) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put(fallbackKey, toolInput.trim());
                log.info("MCP 工具 [{}] 入参非 JSON，按 schema 首参 [{}] 兜底",
                        def.getToolName(), fallbackKey);
                return args;
            }
            return null;
        }
    }

    /** 从 inputSchema 里取第一个属性名（兜底用）。 */
    private String firstSchemaProperty() {
        try {
            Map<?, ?> schema = json.readValue(def.getInputSchema(), Map.class);
            Object props = schema.get("properties");
            if (props instanceof Map<?, ?> p && !p.isEmpty()) {
                return String.valueOf(p.keySet().iterator().next());
            }
        } catch (Exception ignored) {
            // schema 不合法时没有兜底可言
        }
        return null;
    }

    private String errorJson(String message) {
        try {
            return json.writeValueAsString(Map.of("error", message == null ? "MCP 调用失败" : message));
        } catch (Exception ignored) {
            return "{\"error\":\"MCP 调用失败\"}";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 供单测/排查用：当前绑定的远端工具名。 */
    public String getRemoteToolName() {
        return remoteToolName;
    }
}