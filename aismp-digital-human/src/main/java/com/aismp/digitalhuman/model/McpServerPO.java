package com.aismp.digitalhuman.model;

import java.time.LocalDateTime;

/**
 * MCP 服务注册项（a_mcp_server，批 E 2026-10-06）。
 *
 * <p>内核「接入外部 MCP server」的最小配置：传输类型 + 地址 + 可选请求头。
 * 工具定义（a_llm_tool.tool_type='MCP'）通过 config_json 里的 {@code serverCode}
 * 引用本表，运行时由 McpToolBuilder 装配。
 */
public class McpServerPO {

    private Long id;
    /** 服务编码：工具定义引用它（唯一）。 */
    private String serverCode;
    private String serverName;
    /** 传输类型：SSE / HTTP（streamable http）。 */
    private String transport;
    /** SSE: http://host:port（端点默认 /sse）；HTTP: 完整端点地址。 */
    private String baseUrl;
    /** 自定义请求头（JSON 对象字符串），如鉴权头。 */
    private String headersJson;
    /** 鉴权类型：NONE / HEADER。 */
    private String authType;
    private String status;
    private LocalDateTime lastSyncTime;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getServerCode() { return serverCode; }
    public void setServerCode(String serverCode) { this.serverCode = serverCode; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getHeadersJson() { return headersJson; }
    public void setHeadersJson(String headersJson) { this.headersJson = headersJson; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLastSyncTime() { return lastSyncTime; }
    public void setLastSyncTime(LocalDateTime lastSyncTime) { this.lastSyncTime = lastSyncTime; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}