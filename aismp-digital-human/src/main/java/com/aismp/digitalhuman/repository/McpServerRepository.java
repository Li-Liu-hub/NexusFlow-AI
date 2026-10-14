package com.aismp.digitalhuman.repository;

import com.aismp.digitalhuman.model.McpServerPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MCP 服务注册表仓储（a_mcp_server，批 E 2026-10-06）。
 */
@Repository
public class McpServerRepository {

    private final JdbcTemplate jdbc;

    public McpServerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS =
            "id, server_code, server_name, transport, base_url, headers_json, auth_type, status, "
                    + "last_sync_time, last_error, created_at, updated_at";

    private static final RowMapper<McpServerPO> MAPPER = (rs, i) -> {
        McpServerPO po = new McpServerPO();
        po.setId(rs.getLong("id"));
        po.setServerCode(rs.getString("server_code"));
        po.setServerName(rs.getString("server_name"));
        po.setTransport(rs.getString("transport"));
        po.setBaseUrl(rs.getString("base_url"));
        po.setHeadersJson(rs.getString("headers_json"));
        po.setAuthType(rs.getString("auth_type"));
        po.setStatus(rs.getString("status"));
        po.setLastSyncTime(rs.getTimestamp("last_sync_time") == null
                ? null : rs.getTimestamp("last_sync_time").toLocalDateTime());
        po.setLastError(rs.getString("last_error"));
        po.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        po.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return po;
    };

    public List<McpServerPO> list() {
        return jdbc.query("SELECT " + COLUMNS + " FROM a_mcp_server ORDER BY id", MAPPER);
    }

    public McpServerPO findById(Long id) {
        List<McpServerPO> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM a_mcp_server WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public McpServerPO findByCode(String serverCode) {
        List<McpServerPO> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM a_mcp_server WHERE server_code = ?", MAPPER, serverCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insert(McpServerPO po) {
        jdbc.update("""
                INSERT INTO a_mcp_server (server_code, server_name, transport, base_url,
                       headers_json, auth_type, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, po.getServerCode(), po.getServerName(), po.getTransport(), po.getBaseUrl(),
                po.getHeadersJson(), po.getAuthType(), po.getStatus());
    }

    public int update(McpServerPO po) {
        return jdbc.update("""
                UPDATE a_mcp_server
                   SET server_name = ?, transport = ?, base_url = ?, headers_json = ?, auth_type = ?, status = ?
                 WHERE id = ?
                """, po.getServerName(), po.getTransport(), po.getBaseUrl(), po.getHeadersJson(),
                po.getAuthType(), po.getStatus(), po.getId());
    }

    public int delete(Long id) {
        return jdbc.update("DELETE FROM a_mcp_server WHERE id = ?", id);
    }

    /** 探测结果落账：成功时 error 传 null（清掉旧错误），失败时写错误摘要。 */
    public void markProbe(Long id, String error) {
        if (error == null) {
            jdbc.update("UPDATE a_mcp_server SET last_sync_time = NOW(), last_error = NULL WHERE id = ?", id);
        } else {
            String safe = error.length() > 480 ? error.substring(0, 480) : error;
            jdbc.update("UPDATE a_mcp_server SET last_error = ? WHERE id = ?", safe, id);
        }
    }

    /**
     * 引用该服务的工具定义数（config_json 里出现 serverCode）。
     * 删除服务前的占用检查 —— 留着悬空引用会在装配时才报错。
     */
    public int countToolsReferencing(String serverCode) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a_llm_tool WHERE config_json LIKE CONCAT('%', ?, '%')",
                Integer.class, serverCode);
        return n == null ? 0 : n;
    }
}