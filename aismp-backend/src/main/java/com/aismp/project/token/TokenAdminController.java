package com.aismp.project.token;

import com.aismp.project.auth.AuthRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * token 用量分配接口（2026-10-06 批 A：从内核迁至 backend）。
 *
 * <p>
 * 用量总览 = 本月每用户实际用量 + 配额 + 余量状态；配额 CRUD 直接写 a_token_quota，
 * 下一轮对话入口即生效（内核在对话入口调 /inner/token/check）。
 *
 * <p>
 * 守卫：网关门户通道已校验「令牌已登记」，本控制器再做一道角色检查
 * （ADMIN/SERVICE 才能改配额）—— 与内核原先的拦截器规则一致。
 */
@RestController
@RequestMapping("/admin/token")
public class TokenAdminController {

    private final TokenUsageService service;
    private final AuthRepository auth;

    public TokenAdminController(TokenUsageService service, AuthRepository auth) {
        this.service = service;
        this.auth = auth;
    }

    /** 用量总览（每用户：请求数 / token 用量 / 配额 / 状态）；month 缺省当月。 */
    @GetMapping("/usage/overview")
    public List<Map<String, Object>> overview(HttpServletRequest request,
            @RequestParam(required = false) String month) {
        guard(request);
        return service.overview(month);
    }

    /** 某用户最近 n 天的逐日用量。 */
    @GetMapping("/usage/daily")
    public List<Map<String, Object>> daily(HttpServletRequest request,
            @RequestParam(defaultValue = "portal_user") String userId,
            @RequestParam(defaultValue = "30") int days) {
        guard(request);
        return service.dailyUsage(userId, days);
    }

    /** 智能体 token 用量排行（管理台「智能体 token 用量」卡）；month 缺省当月。 */
    @GetMapping("/usage/agents")
    public List<Map<String, Object>> agentUsage(HttpServletRequest request,
            @RequestParam(required = false) String month) {
        guard(request);
        return service.agentOverview(month);
    }

    @GetMapping("/quota/list")
    public List<QuotaPO> listQuotas(HttpServletRequest request) {
        guard(request);
        return service.listQuotas();
    }

    /** 配额对象候选（按范围）：USER → 平台账号列表，供管理台「对象编码」下拉选择。 */
    @GetMapping("/target-codes")
    public List<Map<String, Object>> targetCodes(HttpServletRequest request,
            @RequestParam(defaultValue = "USER") String scope) {
        guard(request);
        return service.targetCodes(scope);
    }

    @PostMapping("/quota")
    public Map<String, Object> createQuota(HttpServletRequest request, @RequestBody QuotaPO po) {
        guard(request);
        return service.saveQuota(po);
    }

    @PutMapping("/quota/{id}")
    public Map<String, Object> updateQuota(HttpServletRequest request,
            @PathVariable Long id, @RequestBody QuotaPO po) {
        guard(request);
        po.setId(id);
        return service.saveQuota(po);
    }

    @DeleteMapping("/quota/{id}")
    public Map<String, Object> deleteQuota(HttpServletRequest request, @PathVariable Long id) {
        guard(request);
        return service.deleteQuota(id);
    }

    /** 某月用量汇总（按用户 + 按智能体）——配额表「本月已使用」列（2026-10-09）。 */
    @GetMapping("/usage/monthly")
    public Map<String, Object> monthlyUsage(HttpServletRequest request,
            @RequestParam(required = false) String month) {
        guard(request);
        return service.monthlyUsage(month);
    }

    /** 某对象某月逐日 × 智能体用量（调整弹窗：日柱状图 + 智能体分布饼图）。 */
    @GetMapping("/usage/daily-agents")
    public List<Map<String, Object>> dailyAgents(HttpServletRequest request,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String agentCode) {
        guard(request);
        return service.dailyAgents(month, userId, agentCode);
    }

    /** 功能点调用次数（用户端·终端管理功能入口）；month 缺省当月。 */
    @GetMapping("/feature-usage")
    public List<Map<String, Object>> featureUsage(HttpServletRequest request,
            @RequestParam(required = false) String month) {
        guard(request);
        return service.featureUsage(month);
    }

    private void guard(HttpServletRequest request) {
        String reason = auth.checkAdmin(request.getHeader("Authorization"));
        if (reason != null) {
            auth.warnDenied(request.getRequestURI(), reason);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
        }
    }
}