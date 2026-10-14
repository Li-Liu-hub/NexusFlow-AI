package com.aismp.digitalhuman.controller;

import com.aismp.digitalhuman.repository.ConversationRepository;
import com.aismp.digitalhuman.service.AgentAssembler;
import com.aismp.digitalhuman.tool.ToolApprovalService;
import com.aismp.digitalhuman.tool.ToolCallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具调用审批接口 —— 敏感工具的人工放行闭环。
 *
 * <p>链路：模型调用敏感工具 → {@link com.aismp.digitalhuman.tool.AuditedToolCallback}
 * 拦截并落 PENDING 审批单 → 前端弹审批卡片 → 用户点「放行/拒绝」→ 本控制器决策。
 *
 * <p><b>放行为什么不经过模型</b>：审批单里已存住当时的入参，放行后原样重放即可 ——
 * 确定性执行，不依赖模型听不听话，也不会再烧一次 token。
 * 执行结果补写进会话（role=tool），下一轮模型自然能看到放行后的真实数据。
 */
@RestController
@RequestMapping("/agent/tool-approval")
public class ToolApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalController.class);

    private final ToolApprovalService approvals;
    private final AgentAssembler assembler;
    private final ConversationRepository conversations;
    private final ObjectMapper json;

    public ToolApprovalController(ToolApprovalService approvals,
                                  AgentAssembler assembler,
                                  ConversationRepository conversations,
                                  ObjectMapper json) {
        this.approvals = approvals;
        this.assembler = assembler;
        this.conversations = conversations;
        this.json = json;
    }

    public record DecideRequest(Boolean approve, String userId) {
    }

    /** 待审批列表（前端审批卡片 / 运维界面用）。 */
    @GetMapping("/pending")
    public List<Map<String, Object>> pending(@RequestParam(defaultValue = "20") int limit) {
        return approvals.listByStatus("PENDING", limit);
    }

    /** 全部审批单（最近优先）。 */
    @GetMapping("/recent")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "50") int limit) {
        return approvals.listRecent(limit);
    }

    @GetMapping("/{approvalId}")
    public Map<String, Object> detail(@PathVariable String approvalId) {
        Map<String, Object> row = approvals.find(approvalId);
        return row == null ? Map.of("error", "审批单不存在: " + approvalId) : row;
    }

    /**
     * 审批决策：approve=true → 按原参重放执行并回填结果；false → 仅拒绝。
     */
    @PostMapping("/{approvalId}/decide")
    public Map<String, Object> decide(@PathVariable String approvalId,
                                      @RequestBody DecideRequest request,
                                      jakarta.servlet.http.HttpServletRequest httpRequest) {
        Map<String, Object> row = approvals.find(approvalId);
        if (row == null) {
            return Map.of("error", "审批单不存在: " + approvalId);
        }
        if (!"PENDING".equals(row.get("status"))) {
            return Map.of("error", "审批单已处理（" + row.get("status") + "）", "status", row.get("status"));
        }
        boolean approve = request != null && Boolean.TRUE.equals(request.approve());
        // 决策人以登录身份为准（SERVICE 账号可代传）
        com.aismp.digitalhuman.auth.UserPrincipal principal =
                com.aismp.digitalhuman.auth.AuthInterceptor.current(httpRequest);
        String userId = principal == null ? null
                : (principal.isService() && request != null && request.userId() != null
                        && !request.userId().isBlank() ? request.userId() : principal.userCode());
        String agentCode = String.valueOf(row.get("agentCode"));
        String toolName = String.valueOf(row.get("toolName"));
        String conversationId = row.get("conversationId") == null ? null
                : String.valueOf(row.get("conversationId"));

        if (!approve) {
            approvals.markStatus(approvalId, "REJECTED", null);
            appendToolMsg(agentCode, conversationId, userId,
                    "工具 [" + toolName + "] 的调用已被拒绝（审批单 " + approvalId + "），本次不执行。");
            return Map.of("approvalId", approvalId, "status", "REJECTED", "tool", toolName);
        }

        // ---- 放行：原参重放 ----
        long start = System.currentTimeMillis();
        ToolCallContext.set(ToolCallContext.Ctx.of(
                UUID.randomUUID().toString(), conversationId, userId, null, agentCode));
        ToolCallContext.grant(toolName);
        try {
            ToolCallback callback = assembler.buildToolCallback(agentCode, toolName);
            String args = String.valueOf(row.get("argumentsJson"));
            String output = callback.call("null".equals(args) ? "{}" : args);
            long elapsed = System.currentTimeMillis() - start;

            approvals.markStatus(approvalId, "EXECUTED", ToolApprovalService.preview(output, 2000));
            appendToolMsg(agentCode, conversationId, userId,
                    "工具 [" + toolName + "] 已审批放行并执行（审批单 " + approvalId + "），结果：\n"
                            + ToolApprovalService.preview(output, 1500));

            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("approvalId", approvalId);
            result.put("status", "EXECUTED");
            result.put("tool", toolName);
            result.put("elapsedMs", elapsed);
            result.put("result", safeParse(output));
            return result;
        } catch (Exception e) {
            log.warn("审批放行执行失败: {} {}", toolName, e.getMessage());
            approvals.markStatus(approvalId, "FAILED", e.getMessage());
            return Map.of("approvalId", approvalId, "status", "FAILED",
                    "tool", toolName, "error", String.valueOf(e.getMessage()));
        } finally {
            ToolCallContext.clear();
        }
    }

    private void appendToolMsg(String agentCode, String conversationId, String userId, String content) {
        if (conversationId == null || conversationId.isBlank() || "null".equals(conversationId)) {
            return;
        }
        try {
            conversations.appendMessage(agentCode, conversationId, null, userId,
                    "tool", content, "auto", null, null, "success", null);
        } catch (Exception e) {
            log.warn("审批结果补写会话失败（不影响放行结果返回）: {}", e.getMessage());
        }
    }

    /** 结果尽量解析成对象返回给前端；解析不了就原文返回。 */
    private Object safeParse(String output) {
        if (output == null) {
            return null;
        }
        String t = output.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                return json.readValue(t, Object.class);
            } catch (Exception ignored) {
                // 落到原文返回
            }
        }
        return output;
    }
}
