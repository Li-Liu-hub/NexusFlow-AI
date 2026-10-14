package com.aismp.project.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * token 用量服务（backend 侧，2026-10-06 批 A 从内核迁来）。
 *
 * <p><b>计量链路</b>：内核在每轮对话出口把该次请求的用量（prompt/completion/total/toolCalls）
 * 通过 {@code POST /api/inner/token/charge} 上报到这里落账；下一轮对话入口内核先调
 * {@code GET /api/inner/token/check}，用当月累计值比对 a_token_quota。
 *
 * <p><b>失败语义</b>：落账失败只打日志不抛 —— 计量是旁路，不能因为记账失败让对话报错；
 * 配额校验同理由内核兜底（失败默认放行）。
 */
@Service
public class TokenUsageService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);

    private final TokenUsageRepository repository;

    public TokenUsageService(TokenUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * 对话入口的配额闸门（内核经 /inner/token/check 调用）。
     *
     * @return null = 放行；否则返回给前端的拒绝原因
     */
    public String checkQuota(String userId) {
        try {
            QuotaPO quota = repository.findActiveUserQuota(userId);
            if (quota == null) {
                return null;
            }
            long used = repository.monthTokens(userId, LocalDate.now().withDayOfMonth(1));
            if (used >= quota.getMonthlyTokens()) {
                return "本月 token 配额已用尽（已用 " + used + " / 配额 " + quota.getMonthlyTokens()
                        + "）。请联系管理员调整配额（管理端 → Token 用量分配）。";
            }
        } catch (Exception e) {
            log.warn("配额校验失败（默认放行）: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 落一笔用量（内核经 /inner/token/charge 调用）。
     * 幂等落账由仓储层的 ON DUPLICATE KEY UPDATE 保证。
     */
    public Map<String, Object> charge(String userId, String agentCode, String model,
                                      int requests, long promptTokens, long completionTokens,
                                      long totalTokens, int toolCalls) {
        try {
            repository.addUsage(null, userId, LocalDate.now(), model, agentCode,
                    Math.max(requests, 1), promptTokens, completionTokens, totalTokens, toolCalls);
        } catch (Exception e) {
            log.warn("token 用量落账失败（忽略）: {}", e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("charged", true);
        result.put("totalTokens", totalTokens);
        result.put("modelCalls", requests);
        return result;
    }

    // ------------------------------------------------------------------
    // 管理面
    // ------------------------------------------------------------------

    /** 用量总览：某月（缺省当月）每用户的用量 + 配额 + 余量状态（配额页表格直接消费）。 */
    public List<Map<String, Object>> overview(String month) {
        LocalDate from = monthStart(month);
        List<Map<String, Object>> rows = repository.monthOverview(from, from.plusMonths(1));
        for (Map<String, Object> row : rows) {
            String userId = String.valueOf(row.get("user_id"));
            QuotaPO quota = repository.findActiveUserQuota(userId);
            long used = row.get("total_tokens") == null ? 0 : ((Number) row.get("total_tokens")).longValue();
            row.put("quota_tokens", quota == null ? null : quota.getMonthlyTokens());
            row.put("quota_status", quota == null ? "不限"
                    : used >= quota.getMonthlyTokens() ? "已超限"
                    : used >= quota.getMonthlyTokens() * 0.8 ? "接近上限" : "正常");
            if (quota != null && quota.getMonthlyTokens() > 0) {
                row.put("usage_percent", Math.round(used * 1000.0 / quota.getMonthlyTokens()) / 10.0);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> dailyUsage(String userId, int days) {
        return repository.dailyUsage(userId, Math.min(Math.max(days, 1), 200));
    }

    /** 某月（缺省当月）每个智能体（数字员工/编排节点）的 token 用量排行。 */
    public List<Map<String, Object>> agentOverview(String month) {
        LocalDate from = monthStart(month);
        return repository.agentOverview(from, from.plusMonths(1));
    }

    public List<QuotaPO> listQuotas() {
        return repository.listQuotas();
    }

    /**
     * 配额对象候选（管理台「对象编码」下拉的数据源）。
     *
     * @param scope USER → 平台账号；其余范围（AGENT/TENANT 目录在内核库）返回空，
     *              由管理台从内核目录接口获取
     */
    public List<Map<String, Object>> targetCodes(String scope) {
        if ("USER".equalsIgnoreCase(scope)) {
            return repository.listAccounts();
        }
        return List.of();
    }

    public Map<String, Object> saveQuota(QuotaPO po) {
        if (po.getScope() == null || po.getTargetCode() == null || po.getTargetCode().isBlank()) {
            return Map.of("error", "scope 与 target_code 必填");
        }
        if (!"USER".equals(po.getScope()) && !"AGENT".equals(po.getScope()) && !"TENANT".equals(po.getScope())) {
            return Map.of("error", "scope 只支持 USER/AGENT/TENANT");
        }
        if (po.getId() == null) {
            return Map.of("id", repository.insertQuota(po), "saved", true);
        }
        // 更新：status 未传时保留原行的状态 —— status 列 NOT NULL，置 null 会 500
        // （2026-10-08 修复：管理台「调整配额」只回传 scope/targetCode/月配额/备注，此前必踩）
        if (po.getStatus() == null || po.getStatus().isBlank()) {
            QuotaPO existing = repository.findQuotaById(po.getId());
            po.setStatus(existing == null || existing.getStatus() == null ? "active" : existing.getStatus());
        }
        return Map.of("updated", repository.updateQuota(po), "saved", true);
    }

    public Map<String, Object> deleteQuota(Long id) {
        return Map.of("deleted", repository.deleteQuota(id));
    }

    // ------------------------------------------------------------------
    // 月度用量视图（2026-10-09）
    // ------------------------------------------------------------------

    /** 某月用量汇总（按用户 + 按智能体）——配额表「本月已使用」列消费。 */
    public Map<String, Object> monthlyUsage(String month) {
        LocalDate from = monthStart(month);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", from.toString().substring(0, 7));
        result.put("byUser", repository.monthUserUsage(from, from.plusMonths(1)));
        result.put("byAgent", repository.monthAgentUsage(from, from.plusMonths(1)));
        return result;
    }

    /** 某对象某月逐日 × 智能体用量（调整弹窗：日柱状图 + 智能体分布饼图）。 */
    public List<Map<String, Object>> dailyAgents(String month, String userId, String agentCode) {
        LocalDate from = monthStart(month);
        return repository.dailyAgentUsage(userId, agentCode, from, from.plusMonths(1));
    }

    /** 某月（缺省当月）功能点调用次数（用户端·终端管理功能入口）。 */
    public List<Map<String, Object>> featureUsage(String month) {
        return repository.featureUsage(monthStart(month).toString().substring(0, 7));
    }

    /** 解析 yyyy-MM；缺省/非法一律回退到本月月初。 */
    private LocalDate monthStart(String month) {
        if (month == null || month.isBlank()) {
            return LocalDate.now().withDayOfMonth(1);
        }
        try {
            return LocalDate.parse(month.trim() + "-01");
        } catch (Exception e) {
            return LocalDate.now().withDayOfMonth(1);
        }
    }
}