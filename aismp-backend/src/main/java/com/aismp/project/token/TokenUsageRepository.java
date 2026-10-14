package com.aismp.project.token;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * token 用量落账 + 配额仓储（a_token_usage_daily / a_token_quota，表在 api_db）。
 *
 * <p>2026-10-06 批 A：从内核迁来 —— 用量账本与配额属「平台用户账本」。
 * 落账粒度与既有表结构对齐：每天 × 每用户 × 每模型 × 每智能体一行，重复则累加
 * （INSERT ... ON DUPLICATE KEY UPDATE；2026-10-08 起 agent_code 纳入 uk_daily 唯一键，
 * 见 local-init/86 —— 此前同用户同天多智能体会被并成一行，管理台按智能体聚合时
 * 后到的智能体恒为 0，表现为计量盲区）。
 *
 * <p>内核的对话入口在请求前调 /inner/token/check（配额闸门）、出口调 /inner/token/charge（落账），
 * 计量失败不允许影响对话本身。
 */
@Repository
public class TokenUsageRepository {

    private final JdbcTemplate jdbc;

    public TokenUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 用量落账 / 查询
    // ------------------------------------------------------------------

    /** 累加一笔用量（同天同人同模型累加到同一行）。 */
    public int addUsage(String entCode, String userId, LocalDate date, String model,
                        String agentCode, int requests,
                        long promptTokens, long completionTokens, long totalTokens,
                        int toolCalls) {
        return jdbc.update("""
                INSERT INTO a_token_usage_daily
                  (ent_code, user_id, usage_date, model, agent_code,
                   request_count, total_prompt_tokens, total_completion_tokens,
                   total_tokens, total_tool_calls)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                   request_count            = request_count + VALUES(request_count),
                   total_prompt_tokens      = total_prompt_tokens + VALUES(total_prompt_tokens),
                   total_completion_tokens  = total_completion_tokens + VALUES(total_completion_tokens),
                   total_tokens             = total_tokens + VALUES(total_tokens),
                   total_tool_calls         = total_tool_calls + VALUES(total_tool_calls)
                """,
                entCode == null ? "default" : entCode,
                userId == null || userId.isBlank() ? "anonymous" : userId,
                date,
                model == null ? "unknown" : model,
                agentCode,
                requests, promptTokens, completionTokens, totalTokens, toolCalls);
    }

    /** 某用户当月累计 token（从月初到今天）。 */
    public long monthTokens(String userId, LocalDate monthStart) {
        List<Long> list = jdbc.queryForList("""
                SELECT COALESCE(SUM(total_tokens), 0) FROM a_token_usage_daily
                 WHERE user_id = ? AND usage_date >= ?
                """, Long.class, userId == null || userId.isBlank() ? "anonymous" : userId, monthStart);
        return list.isEmpty() || list.get(0) == null ? 0L : list.get(0);
    }

    /** 某用户最近 n 天的每日用量（总览图/明细用）。 */
    public List<Map<String, Object>> dailyUsage(String userId, int days) {
        return jdbc.queryForList("""
                SELECT usage_date, model, agent_code, request_count,
                       total_prompt_tokens, total_completion_tokens, total_tokens, total_tool_calls
                  FROM a_token_usage_daily
                 WHERE user_id = ?
                 ORDER BY usage_date DESC, model
                 LIMIT ?
                """, userId == null || userId.isBlank() ? "anonymous" : userId, days);
    }

    /** 某月（[from, to) 区间）全体用户用量总览（管理台配额页的数据源）。 */
    public List<Map<String, Object>> monthOverview(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT user_id,
                       SUM(request_count)              AS requests,
                       SUM(total_prompt_tokens)        AS prompt_tokens,
                       SUM(total_completion_tokens)    AS completion_tokens,
                       SUM(total_tokens)               AS total_tokens,
                       MAX(usage_date)                 AS last_active,
                       SUBSTRING_INDEX(GROUP_CONCAT(DISTINCT model), ',', 1) AS main_model
                  FROM a_token_usage_daily
                 WHERE usage_date >= ? AND usage_date < ?
                 GROUP BY user_id
                 ORDER BY total_tokens DESC
                """, from, to);
    }

    /** 某月（[from, to) 区间）智能体用量总览（「智能体 token 用量」排行）：未带 agent_code 的落账行不计入。 */
    public List<Map<String, Object>> agentOverview(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT agent_code,
                       SUM(request_count)           AS requests,
                       SUM(total_tokens)            AS total_tokens,
                       COUNT(DISTINCT user_id)      AS users,
                       MAX(usage_date)              AS last_active
                  FROM a_token_usage_daily
                 WHERE usage_date >= ? AND usage_date < ?
                   AND agent_code IS NOT NULL AND agent_code <> ''
                 GROUP BY agent_code
                 ORDER BY total_tokens DESC
                """, from, to);
    }

    // ------------------------------------------------------------------
    // 配额对象候选（管理台「对象编码」下拉）
    // ------------------------------------------------------------------

    /**
     * 平台账号列表（a_user，平台账本库）——供配额页 USER 维度的对象编码下拉使用。
     *
     * <p>数字员工 / 租户目录在 AI 内核库（terminal_db），backend 不跨库读取，
     * 那两类候选由管理台从内核目录接口获取。
     */
    public List<Map<String, Object>> listAccounts() {
        return jdbc.queryForList("""
                SELECT user_code, user_name, role
                  FROM a_user
                 WHERE status = 'active'
                 ORDER BY role, user_code
                """);
    }

    // ------------------------------------------------------------------
    // 配额 CRUD
    // ------------------------------------------------------------------

    private static final RowMapper<QuotaPO> QUOTA_MAPPER = (rs, i) -> {
        QuotaPO po = new QuotaPO();
        po.setId(rs.getLong("id"));
        po.setScope(rs.getString("scope"));
        po.setTargetCode(rs.getString("target_code"));
        po.setMonthlyTokens(rs.getLong("monthly_tokens"));
        po.setStatus(rs.getString("status"));
        po.setRemark(rs.getString("remark"));
        po.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        po.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
        return po;
    };

    public List<QuotaPO> listQuotas() {
        return jdbc.query("SELECT * FROM a_token_quota ORDER BY scope, target_code", QUOTA_MAPPER);
    }

    /** 按主键查配额行（更新前补齐缺省字段用）；不存在返回 null。 */
    public QuotaPO findQuotaById(Long id) {
        List<QuotaPO> list = jdbc.query("SELECT * FROM a_token_quota WHERE id = ?", QUOTA_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查某用户生效中的 USER 维度配额；没有配额行返回 null（=不限）。 */
    public QuotaPO findActiveUserQuota(String userId) {
        List<QuotaPO> list = jdbc.query(
                "SELECT * FROM a_token_quota WHERE scope='USER' AND target_code=? AND status='active'",
                QUOTA_MAPPER, userId == null || userId.isBlank() ? "anonymous" : userId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Long insertQuota(QuotaPO po) {
        org.springframework.jdbc.support.KeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO a_token_quota (scope, target_code, monthly_tokens, status, remark) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.getScope());
            ps.setString(2, po.getTargetCode());
            ps.setLong(3, po.getMonthlyTokens() == null ? 0L : po.getMonthlyTokens());
            ps.setString(4, po.getStatus() == null ? "active" : po.getStatus());
            if (po.getRemark() == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, po.getRemark());
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public int updateQuota(QuotaPO po) {
        return jdbc.update("""
                UPDATE a_token_quota
                   SET scope = ?, target_code = ?, monthly_tokens = ?, status = ?, remark = ?
                 WHERE id = ?
                """, po.getScope(), po.getTargetCode(),
                po.getMonthlyTokens() == null ? 0L : po.getMonthlyTokens(),
                po.getStatus(), po.getRemark(), po.getId());
    }

    public int deleteQuota(Long id) {
        return jdbc.update("DELETE FROM a_token_quota WHERE id = ?", id);
    }

    // ------------------------------------------------------------------
    // 月度用量视图（2026-10-09：配额表「本月已使用」列 + 调整弹窗图表）
    // ------------------------------------------------------------------

    /** 某月每用户用量（区间为 [from, to)）。 */
    public List<Map<String, Object>> monthUserUsage(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT user_id,
                       SUM(request_count) AS requests,
                       SUM(total_tokens)   AS total_tokens
                  FROM a_token_usage_daily
                 WHERE usage_date >= ? AND usage_date < ?
                 GROUP BY user_id
                """, from, to);
    }

    /** 某月每智能体用量（区间为 [from, to)；未带 agent_code 的落账行不计入）。 */
    public List<Map<String, Object>> monthAgentUsage(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT agent_code,
                       SUM(request_count) AS requests,
                       SUM(total_tokens)   AS total_tokens
                  FROM a_token_usage_daily
                 WHERE usage_date >= ? AND usage_date < ?
                   AND agent_code IS NOT NULL AND agent_code <> ''
                 GROUP BY agent_code
                """, from, to);
    }

    /**
     * 某对象某月逐日 × 智能体用量（调整弹窗的「日柱状图 + 智能体分布饼图」一次取足）。
     * userId / agentCode 为空表示不限该维度；usage_date 用 DATE_FORMAT 输出字符串，
     * 避免 java.sql.Date 被 Jackson 序列化成时间戳/带时区形态，前端还要二次猜格式。
     */
    public List<Map<String, Object>> dailyAgentUsage(String userId, String agentCode,
                                                     LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                SELECT DATE_FORMAT(usage_date, '%Y-%m-%d') AS usage_date, agent_code,
                       SUM(request_count) AS requests,
                       SUM(total_tokens)   AS total_tokens
                  FROM a_token_usage_daily
                 WHERE usage_date >= ? AND usage_date < ?
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(from);
        args.add(to);
        if (userId != null && !userId.isBlank()) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        if (agentCode != null && !agentCode.isBlank()) {
            sql.append(" AND agent_code = ?");
            args.add(agentCode);
        }
        sql.append(" GROUP BY usage_date, agent_code ORDER BY usage_date");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 某月功能点调用次数（用户端·终端管理功能入口；a_feature_usage 演示账本，按 usage_month 过滤）。 */
    public List<Map<String, Object>> featureUsage(String usageMonth) {
        return jdbc.queryForList("""
                SELECT feature_code, feature_name, usage_month, call_count, last_called_at
                  FROM a_feature_usage
                 WHERE usage_month = ?
                 ORDER BY call_count DESC, feature_code
                """, usageMonth);
    }
}