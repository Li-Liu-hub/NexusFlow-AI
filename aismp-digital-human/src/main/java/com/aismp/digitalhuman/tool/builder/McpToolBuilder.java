package com.aismp.digitalhuman.tool.builder;

import com.aismp.digitalhuman.model.McpServerPO;
import com.aismp.digitalhuman.model.ToolDefPO;
import com.aismp.digitalhuman.repository.McpServerRepository;
import com.aismp.digitalhuman.service.McpClientService;
import com.aismp.digitalhuman.tool.DigitalToolBuilder;
import com.aismp.digitalhuman.tool.McpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * MCP 型工具构造器（{@link DigitalToolBuilder} 的第 4 种实现，批 E 2026-10-06）。
 *
 * <p>「接入外部 MCP server」的装配侧：工具定义里只写
 * {@code config_json = {"serverCode":..,"toolName":..}}，这里把它绑定到注册表里的连接配置。
 *
 * <p><b>装配期不联网</b>：build() 只查注册表做静态绑定，连接与调用都推迟到模型真正调用工具时
 * （见 {@link McpToolCallback}）。这样远端 MCP server 掉线不会让数字员工整体装配失败 ——
 * 与 AgentAssembler「一个工具配错不该导致数字员工不可用」的容错口径一致。
 *
 * <p>验证方式：把平台自己的 MCP server（aismp-agent 的 /sse）注册进来探测，
 * 即可拿到 queryOrderStatus / searchKnowledgeBase 等工具做端到端自测。
 */
@Component
public class McpToolBuilder implements DigitalToolBuilder {

    public static final String TYPE = "MCP";

    private final McpServerRepository serverRepository;
    private final McpClientService clientService;

    public McpToolBuilder(McpServerRepository serverRepository, McpClientService clientService) {
        this.serverRepository = serverRepository;
        this.clientService = clientService;
    }

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    public ToolCallback build(ToolDefPO def, ToolBuildContext ctx) {
        Cfg cfg = parseCfg(def.getConfigJson(), ctx.json());
        McpServerPO server = requireActiveServer(cfg.serverCode());
        return new McpToolCallback(def, server, cfg.toolName(), clientService, ctx.json());
    }

    /**
     * MCP 类型的专属校验：配置可解析 + 引用的服务已注册且启用。
     *
     * <p>不校验 sqlTemplate（本类型不用它）；也不在创建时探测远端 ——
     * 探测是独立的运维动作（/admin/mcp/{id}/probe），创建工具不应该被远端可用性绑住。
     */
    @Override
    public void validate(ToolDefPO def) {
        Cfg cfg = parseCfg(def.getConfigJson(), new ObjectMapper());
        requireActiveServer(cfg.serverCode());
    }

    private McpServerPO requireActiveServer(String serverCode) {
        McpServerPO server = serverRepository.findByCode(serverCode);
        if (server == null) {
            throw new IllegalArgumentException(
                    "MCP 服务未注册: " + serverCode + "（先到「MCP 管理」注册并探测）");
        }
        if (!"active".equals(server.getStatus())) {
            throw new IllegalArgumentException("MCP 服务已停用: " + serverCode);
        }
        return server;
    }

    /** config_json → {serverCode, toolName}；两个字段都必填。 */
    private Cfg parseCfg(String configJson, ObjectMapper json) {
        if (configJson == null || configJson.isBlank()) {
            throw new IllegalArgumentException(
                    "MCP 类型需要 config_json，如 {\"serverCode\":\"aismp-agent-mcp\",\"toolName\":\"queryOrderStatus\"}");
        }
        try {
            Map<?, ?> cfg = json.readValue(configJson, Map.class);
            String serverCode = cfg.get("serverCode") == null ? null : String.valueOf(cfg.get("serverCode")).trim();
            String toolName = cfg.get("toolName") == null ? null : String.valueOf(cfg.get("toolName")).trim();
            if (serverCode == null || serverCode.isEmpty()) {
                throw new IllegalArgumentException("config_json 缺 serverCode");
            }
            if (toolName == null || toolName.isEmpty()) {
                throw new IllegalArgumentException("config_json 缺 toolName（远端工具名）");
            }
            return new Cfg(serverCode, toolName);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("config_json 不是合法 JSON: " + e.getMessage());
        }
    }

    private record Cfg(String serverCode, String toolName) {
    }
}