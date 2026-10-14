package com.aismp.digitalhuman.tool;

import java.util.HashSet;
import java.util.Set;

/**
 * 工具调用上下文（请求级）。
 *
 * <p><b>为什么需要它</b>：{@code ToolCallback.call()} 只能拿到工具入参，
 * 拿不到「这次调用属于哪个会话、哪个用户、哪个数字员工」—— 而那些正是
 * 审计日志和审批单要记的字段。工具回调由框架在调用链内部触发，
 * 我们无法在方法签名上追加参数，所以用 ThreadLocal 把请求级上下文带进去。
 *
 * <p><b>局限性要说清楚</b>：ThreadLocal 只在同线程调用时有效。
 * 若将来开启 SAA 的 {@code parallelToolExecution}，工具可能跑在别的线程上而丢失上下文。
 * 因此审计实现里对 context 缺失是容忍的 —— 拿不到就记为 null，
 * 而不是因此让工具调用失败。审计是旁路，绝不能影响主链路。
 *
 * <p><b>审批放行标记（GRANTED）</b>：需要审批的工具被模型调用时不会执行，
 * 而是生成审批单返回 APPROVAL_REQUIRED。用户在前端点「放行」后，
 * 审批控制器重新调用该工具 —— 正常链路又会被拦，死循环。
 * 所以放行执行前先 {@link #grant}，拦截器看到标记就放行本次调用。
 */
public final class ToolCallContext {

    /**
     * 单次问答链路的上下文。
     *
     * <p><b>dataScope（数据可见范围，2026-10-07）</b>：编排层代终端用户调用时透传的
     * 「可见仓位编码集合」（本仓 + 全部下级仓）。工具回调据此收口：
     * 越权仓位直接拒绝、单仓用户未限定时自动补上 warehouseCode。
     * null/空 = 不限制（服务间调用、管理员链路）。
     */
    public record Ctx(String traceId, String conversationId, String userId,
                      String entCode, String agentCode, Set<String> dataScope) {

        /** 不带数据范围的常用构造（审批放行、流式链路等暂不需要收口的场景）。 */
        public static Ctx of(String traceId, String conversationId, String userId,
                             String entCode, String agentCode) {
            return new Ctx(traceId, conversationId, userId, entCode, agentCode, null);
        }

        /** 数据可见范围：null/空 = 不限制。 */
        public Set<String> scope() {
            return dataScope == null ? Set.of() : dataScope;
        }
    }

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Set<String>> GRANTED = ThreadLocal.withInitial(HashSet::new);

    private ToolCallContext() {
    }

    public static void set(Ctx ctx) {
        HOLDER.set(ctx);
    }

    public static Ctx get() {
        return HOLDER.get();
    }

    /** 标记本线程本次调用中，指定工具已获得放行（只对审批拦截器生效一次语义内）。 */
    public static void grant(String toolName) {
        if (toolName != null) {
            GRANTED.get().add(toolName);
        }
    }

    public static boolean isGranted(String toolName) {
        return toolName != null && GRANTED.get().contains(toolName);
    }

    /** 必须在请求结束时调用，否则线程池复用会导致上下文串场。 */
    public static void clear() {
        HOLDER.remove();
        GRANTED.remove();
    }
}
