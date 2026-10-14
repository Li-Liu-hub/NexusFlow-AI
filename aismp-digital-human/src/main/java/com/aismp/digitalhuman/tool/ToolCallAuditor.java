package com.aismp.digitalhuman.tool;

/**
 * 工具调用审计出口。
 *
 * <p>定义成接口而非直接用 AgentRepository，是为了让 {@code tool} 包不反向依赖 {@code repository} 包 ——
 * 工具执行只负责"上报事实"，至于落库/打日志/发消息由外层决定。
 *
 * <p>实现方**必须吞掉自己的异常**：审计是旁路，不能因为它失败而让工具调用失败。
 */
@FunctionalInterface
public interface ToolCallAuditor {

    /** 一次工具调用的完整事实 */
    record AuditEntry(
            String traceId,
            String conversationId,
            String userId,
            String entCode,
            String toolName,
            String argumentsJson,
            int resultCount,
            long durationMs,
            String status,
            String errorMessage
    ) {
    }

    void audit(AuditEntry entry);
}
