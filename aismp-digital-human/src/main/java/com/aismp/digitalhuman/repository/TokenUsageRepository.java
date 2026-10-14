package com.aismp.digitalhuman.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 工具调用次数统计（a_tool_call_log）。
 *
 * <p><b>2026-10-06 批 A 后的边界</b>：用量账本与配额 CRUD（a_token_usage_daily /
 * a_token_quota）已迁到 backend（api_db），统一由 {@code /api/inner/token/charge} 落账、
 * {@code /api/inner/token/check} 校验。内核本地只剩这一项查询 ——
 * 上报用量时把本次对话的工具调用次数一并带上（次数从内核自己的调用日志里数）。
 */
@Repository
public class TokenUsageRepository {

    private final JdbcTemplate jdbc;

    public TokenUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 某次对话（按 traceId）的工具调用次数 —— 上报 backend 落账时一并写进日汇总。 */
    public int countToolCalls(String traceId) {
        List<Integer> list = jdbc.queryForList(
                "SELECT COUNT(*) FROM a_tool_call_log WHERE trace_id = ?", Integer.class, traceId);
        return list.isEmpty() || list.get(0) == null ? 0 : list.get(0);
    }
}