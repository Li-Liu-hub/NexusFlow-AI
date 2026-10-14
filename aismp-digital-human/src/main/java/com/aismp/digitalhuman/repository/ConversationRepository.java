package com.aismp.digitalhuman.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话与消息仓储（a_chat_conversation + a_chat_message）。
 *
 * <p><b>会话按数字员工隔离</b>：两张表都有 agent_code 列，所有读写都按它过滤 ——
 * 查数助手的历史不出现在运维助手的列表里，各数字员工的上下文也互不污染。
 */
@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一轮对话记录（只取回放需要的字段） */
    public record ChatTurn(String role, String content) {
    }

    // ------------------------------------------------------------------
    // 读：回放历史（按数字员工过滤）
    // ------------------------------------------------------------------

    /**
     * 取某数字员工某会话的最近 N 轮历史，按时间正序返回。
     *
     * <p>先按 id 倒序取 limit 条再反转 —— 拿到的是「最近 limit 条」而不是「最早 limit 条」。
     */
    public List<ChatTurn> loadHistory(String agentCode, String conversationId, int limit) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<ChatTurn> turns = jdbc.query("""
                SELECT role, content FROM a_chat_message
                 WHERE conversation_id = ? AND agent_code = ? AND status = 'success'
                 ORDER BY id DESC
                 LIMIT ?
                """,
                (rs, i) -> new ChatTurn(rs.getString("role"), rs.getString("content")),
                conversationId, agentCode, Math.max(limit, 1));
        java.util.Collections.reverse(turns);
        return turns;
    }

    public int countMessages(String conversationId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a_chat_message WHERE conversation_id = ?",
                Integer.class, conversationId);
        return n == null ? 0 : n;
    }

    /** 某数字员工的会话列表（按最后活跃时间倒序）。 */
    public List<Map<String, Object>> listConversations(String agentCode, String userId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT conversation_id, title, mode, message_count, total_tokens, status, created_at, updated_at
                  FROM a_chat_conversation
                 WHERE status <> 'deleted' AND agent_code = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(agentCode);
        if (userId != null && !userId.isBlank()) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ?");
        args.add(Math.max(limit, 1));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ------------------------------------------------------------------
    // 写：落库（带 agent_code）
    // ------------------------------------------------------------------

    /** 确保会话存在（幂等）；标题取首条用户消息前 50 字。 */
    public void ensureConversation(String agentCode, String conversationId, String entCode,
                                   String userId, String mode, String firstUserMessage) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a_chat_conversation WHERE conversation_id = ?",
                Integer.class, conversationId);
        if (exists != null && exists > 0) {
            return;
        }
        String title = firstUserMessage == null ? null
                : firstUserMessage.substring(0, Math.min(firstUserMessage.length(), 50));
        jdbc.update("""
                INSERT IGNORE INTO a_chat_conversation
                  (conversation_id, ent_code, user_id, agent_code, title, mode, message_count, total_tokens, status)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, 'active')
                """,
                conversationId,
                entCode == null ? "default" : entCode,
                userId == null ? "anonymous" : userId,
                agentCode,
                title,
                mode == null ? "auto" : mode);
    }

    /** 追加一条消息（带 agent_code），并同步会话的 message_count。 */
    public void appendMessage(String agentCode, String conversationId, String entCode, String userId,
                              String role, String content, String mode, String model,
                              Integer durationMs, String status, String errorMessage) {
        jdbc.update("""
                INSERT INTO a_chat_message
                  (message_id, conversation_id, ent_code, user_id, agent_code, role, content,
                   mode, model, duration_ms, status, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                conversationId,
                entCode == null ? "default" : entCode,
                userId == null ? "anonymous" : userId,
                agentCode,
                role,
                content,
                mode == null ? "auto" : mode,
                model,
                durationMs,
                status == null ? "success" : status,
                errorMessage == null ? null
                        : errorMessage.substring(0, Math.min(errorMessage.length(), 500)));

        if ("success".equals(status)) {
            jdbc.update("""
                    UPDATE a_chat_conversation
                       SET message_count = message_count + 1, updated_at = NOW()
                     WHERE conversation_id = ?
                    """, conversationId);
        }
    }

    /** 归档会话（软删除）。 */
    public int archive(String conversationId) {
        return jdbc.update(
                "UPDATE a_chat_conversation SET status = 'archived', updated_at = NOW() WHERE conversation_id = ?",
                conversationId);
    }

    /** 会话详情（含消息）。 */
    public Map<String, Object> conversationDetail(String conversationId, int messageLimit) {
        List<Map<String, Object>> conv = jdbc.queryForList(
                "SELECT * FROM a_chat_conversation WHERE conversation_id = ?", conversationId);
        if (conv.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>(conv.get(0));
        result.put("messages", jdbc.queryForList("""
                SELECT message_id, role, content, model, tool_calls_count,
                       duration_ms, status, created_at
                  FROM a_chat_message
                 WHERE conversation_id = ?
                 ORDER BY id ASC
                 LIMIT ?
                """, conversationId, Math.max(messageLimit, 1)));
        return result;
    }

    /** 会话统计（按数字员工分组，运维观察用）。 */
    public List<Map<String, Object>> statsByAgent() {
        return jdbc.queryForList("""
                SELECT agent_code, COUNT(*) AS conversations, SUM(message_count) AS messages
                  FROM a_chat_conversation WHERE status <> 'deleted'
                 GROUP BY agent_code ORDER BY agent_code
                """);
    }

    /** 全局统计。 */
    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("conversations", jdbc.queryForObject(
                "SELECT COUNT(*) FROM a_chat_conversation WHERE status <> 'deleted'", Integer.class));
        s.put("messages", jdbc.queryForObject(
                "SELECT COUNT(*) FROM a_chat_message", Integer.class));
        s.put("activeToday", jdbc.queryForObject(
                "SELECT COUNT(*) FROM a_chat_message WHERE created_at >= CURDATE()", Integer.class));
        s.put("generatedAt", LocalDateTime.now());
        return s;
    }
}
