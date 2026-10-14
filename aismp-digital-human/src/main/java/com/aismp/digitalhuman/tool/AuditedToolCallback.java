package com.aismp.digitalhuman.tool;

import com.aismp.digitalhuman.model.ToolDefPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 审计装饰器：给任意 ToolCallback 统一加上调用记录。
 *
 * <p><b>为什么用装饰器而不是在各实现里各写一遍</b>：
 * 首版把审计逻辑写死在 {@code SqlTemplateToolCallback} 里，结果新增的
 * {@code KnowledgeRetrievalToolCallback} 就没有审计 —— 这类「横切关注点」
 * 一旦散落到各实现里，必然漏掉后来新增的类型。
 *
 * <p>装饰器把审计收成一处：工厂在构造出任何类型的工具后统一包一层，
 * **新增工具类型自动获得审计**，实现类不必也不该关心这件事。
 *
 * <p>它同时保证：审计永远不改变工具行为（delegate 抛异常就原样抛出，
 * 只是记录后再抛），审计自身失败也绝不影响调用结果。
 */
public class AuditedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(AuditedToolCallback.class);

    private final ToolDefPO def;
    private final ToolCallback delegate;
    private final ToolCallAuditor auditor;
    private final ObjectMapper json;
    /** 审批单出口；为 null 时（异常装配场景）退化为仅提示、不落单 */
    private final ToolApprovalService approvalService;

    public AuditedToolCallback(ToolDefPO def, ToolCallback delegate,
                               ToolCallAuditor auditor, ObjectMapper json,
                               ToolApprovalService approvalService) {
        this.def = def;
        this.delegate = delegate;
        this.auditor = auditor;
        this.json = json;
        this.approvalService = approvalService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        // ---- 审批拦截门：敏感工具在拿到人工放行之前不执行 ----
        // 拦下后落一张 PENDING 审批单（存住入参），把单号回给模型；
        // 模型会转告用户去界面点「放行」。放行后控制器 grant(toolName) 再重放本次调用。
        if (isApprovalRequired() && !ToolCallContext.isGranted(def.getToolName())) {
            String approvalId = null;
            try {
                ToolCallContext.Ctx ctx = ToolCallContext.get();
                if (approvalService != null) {
                    approvalId = approvalService.createPending(
                            ctx == null ? null : ctx.agentCode(),
                            def.getToolName(),
                            ctx == null ? null : ctx.conversationId(),
                            ctx == null ? null : ctx.userId(),
                            toolInput,
                            def.getRiskLevel());
                }
            } catch (Exception e) {
                log.warn("审批单落单失败（本次调用仍按需审批处理）: {}", e.getMessage());
            }
            record(toolInput, 0, 0, "approval_required", null);
            return approvalRequiredJson(approvalId);
        }

        long start = System.currentTimeMillis();
        String output;
        String status = "success";
        String errorMessage = null;

        try {
            output = delegate.call(toolInput);
        } catch (Exception e) {
            status = "error";
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            record(toolInput, 0, System.currentTimeMillis() - start, status, errorMessage);
            // 装饰器不吞异常：它只负责记录，行为由被装饰者决定
            throw e;
        }

        // 从返回体里推断执行结果（各类型统一返回 JSON）：
        // 含 error 字段视为失败，含 count 字段取其值作为结果条数
        int resultCount = 0;
        try {
            Map<?, ?> out = json.readValue(output, Map.class);
            if (out.get("error") != null) {
                status = "error";
                errorMessage = String.valueOf(out.get("error"));
            }
            if (out.get("count") != null) {
                resultCount = Integer.parseInt(String.valueOf(out.get("count")));
            }
        } catch (Exception ignored) {
            // 返回体不是 JSON（如知识检索返回纯文本）—— 不解析，按成功记录
        }

        record(toolInput, resultCount, System.currentTimeMillis() - start, status, errorMessage);
        return output;
    }

    private boolean isApprovalRequired() {
        return def.getApprovalRequired() != null && def.getApprovalRequired() == 1;
    }

    /**
     * 拦截时回给模型的 JSON。明确告诉模型「不要重复调用」，避免它把
     * 审批当成工具故障而反复重试烧 token。
     */
    private String approvalRequiredJson(String approvalId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "APPROVAL_REQUIRED");
        body.put("approval_id", approvalId);
        body.put("tool", def.getToolName());
        body.put("risk_level", def.getRiskLevel() == null ? "LOW" : def.getRiskLevel());
        body.put("message", "该工具涉及敏感操作，已生成审批单"
                + (approvalId == null ? "" : " " + approvalId)
                + "。请告知用户：需在界面上对该审批单点击「放行」后才能拿到数据，"
                + "在放行前不要再次调用本工具。");
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            // JSON 序列化都不该失败；真失败也要给模型一个可读文本
            return "{\"status\":\"APPROVAL_REQUIRED\",\"message\":\"该工具需要人工审批放行\"}";
        }
    }

    private void record(String toolInput, int resultCount, long durationMs,
                        String status, String errorMessage) {
        if (auditor == null) {
            return;
        }
        try {
            ToolCallContext.Ctx ctx = ToolCallContext.get();
            String traceId = (ctx == null || ctx.traceId() == null)
                    ? UUID.randomUUID().toString()
                    : ctx.traceId();
            auditor.audit(new ToolCallAuditor.AuditEntry(
                    traceId,
                    ctx == null ? null : ctx.conversationId(),
                    ctx == null ? null : ctx.userId(),
                    ctx == null ? null : ctx.entCode(),
                    def.getToolName(),
                    toolInput,
                    resultCount,
                    durationMs,
                    status,
                    errorMessage));
        } catch (Exception e) {
            log.warn("工具调用审计写入失败（不影响本次调用结果）: {}", e.getMessage());
        }
    }
}
