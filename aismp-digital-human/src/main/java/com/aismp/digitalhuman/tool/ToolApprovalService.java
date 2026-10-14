package com.aismp.digitalhuman.tool;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具调用审批单仓储（a_tool_approval）。
 *
 * <p><b>闭环怎么走</b>：
 * <ol>
 *   <li>模型调用带 {@code approval_required=1} 的工具 → {@link AuditedToolCallback}
 *       拦下，调 {@link #createPending} 落一张 PENDING 单（把当时的入参存下来），
 *       并把单号回给模型 —— 模型会转告用户「需要审批」；</li>
 *   <li>前端弹出审批卡片，用户点「放行」/「拒绝」→
 *       {@code ToolApprovalController} 按单号取回入参，原样重放执行；</li>
 *   <li>执行结果回填 result_preview，同时补写一条会话消息，
 *       下一轮模型能看到放行后的真实数据。</li>
 * </ol>
 *
 * <p>为什么在 tool 包而不是 repository 包：它和 {@link AuditedToolCallback}
 * 是同一个横切机制的两半（拦截 + 落单），放一起才不会被当成普通 CRUD 忘掉。
 */
@Service
public class ToolApprovalService {

    private final NamedParameterJdbcTemplate jdbc;

    public ToolApprovalService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 生成一张 PENDING 审批单，返回单号。审计是旁路 —— 这里失败也绝不能炸主链路。 */
    public String createPending(String agentCode, String toolName, String conversationId,
                                String userId, String argumentsJson, String riskLevel) {
        String approvalId = "ap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.update("""
                INSERT INTO a_tool_approval
                  (approval_id, agent_code, tool_name, conversation_id, user_id,
                   arguments_json, risk_level, status)
                VALUES (:aid, :ac, :tn, :cid, :uid, :args, :risk, 'PENDING')
                """,
                new MapSqlParameterSource()
                        .addValue("aid", approvalId)
                        .addValue("ac", agentCode == null ? "unknown" : agentCode)
                        .addValue("tn", toolName)
                        .addValue("cid", conversationId)
                        .addValue("uid", userId)
                        .addValue("args", argumentsJson)
                        .addValue("risk", riskLevel == null ? "LOW" : riskLevel));
        return approvalId;
    }

    public Map<String, Object> find(String approvalId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT approval_id AS approvalId, agent_code AS agentCode, tool_name AS toolName,
                       conversation_id AS conversationId, user_id AS userId,
                       arguments_json AS argumentsJson, risk_level AS riskLevel,
                       status, result_preview AS resultPreview, created_at AS createdAt,
                       decided_at AS decidedAt
                  FROM a_tool_approval WHERE approval_id = :aid
                """, new MapSqlParameterSource("aid", approvalId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listByStatus(String status, int limit) {
        return jdbc.queryForList("""
                SELECT approval_id AS approvalId, agent_code AS agentCode, tool_name AS toolName,
                       conversation_id AS conversationId, user_id AS userId,
                       arguments_json AS argumentsJson, risk_level AS riskLevel,
                       status, result_preview AS resultPreview, created_at AS createdAt,
                       decided_at AS decidedAt
                  FROM a_tool_approval
                 WHERE status = :st
                 ORDER BY id DESC
                 LIMIT :lim
                """, new MapSqlParameterSource()
                .addValue("st", status)
                .addValue("lim", Math.min(Math.max(limit, 1), 100)));
    }

    public List<Map<String, Object>> listRecent(int limit) {
        return jdbc.queryForList("""
                SELECT approval_id AS approvalId, agent_code AS agentCode, tool_name AS toolName,
                       risk_level AS riskLevel, status, result_preview AS resultPreview,
                       created_at AS createdAt, decided_at AS decidedAt
                  FROM a_tool_approval
                 ORDER BY id DESC
                 LIMIT :lim
                """, new MapSqlParameterSource("lim", Math.min(Math.max(limit, 1), 100)));
    }

    public int markStatus(String approvalId, String status, String resultPreview) {
        return jdbc.update("""
                UPDATE a_tool_approval
                   SET status = :st, result_preview = :rp, decided_at = NOW()
                 WHERE approval_id = :aid
                """, new MapSqlParameterSource()
                .addValue("st", status)
                .addValue("rp", resultPreview)
                .addValue("aid", approvalId));
    }

    /** 结果摘要：入库前截断，避免一条大结果把 TEXT 撑爆。 */
    public static String preview(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…(截断)";
    }
}
