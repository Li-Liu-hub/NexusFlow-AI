package com.aismp.digitalhuman.tool;

import org.springframework.ai.chat.metadata.Usage;

/**
 * 单次对话请求内的模型用量累计器（ThreadLocal）。
 *
 * <p><b>为什么是 ThreadLocal 而不是显式传参</b>：模型调用发生在 ReactAgent 内部深处，
 * 用量信息只出现在每一跳的 ChatResponse 元数据里，没有任何出口把它带给调用方。
 * 与 {@link ToolCallContext} 同款思路 —— 控制器在请求入口 install，出口 collect+clear。
 *
 * <p><b>线程边界</b>：SSE 通道在 sseExecutor 的单线程里跑完整个对话
 * （模型调用 + 工具执行都在这个线程上，与 AuditedToolCallback 的既有验证一致），
 * 所以 ThreadLocal 累计是安全的。
 */
public final class ModelUsageContext {

    /** 一次请求的用量合计（嵌套类型取名 Totals，避免与 Spring 的 Usage 混淆）。 */
    public record Totals(long promptTokens, long completionTokens, int modelCalls) {
    }

    private static final ThreadLocal<Acc> ACC = new ThreadLocal<>();

    private ModelUsageContext() {
    }

    /** 请求入口调用（幂等：重复 install 会清零重新累计）。 */
    public static void install() {
        ACC.set(new Acc());
    }

    public static boolean installed() {
        return ACC.get() != null;
    }

    /** 取走累计结果并清理（请求出口调用）。未 install 时返回零值。 */
    public static Totals collectAndClear() {
        Acc acc = ACC.get();
        ACC.remove();
        return acc == null ? new Totals(0, 0, 0) : acc.toTotals();
    }

    /** 由 ChatModel 装饰器在每一跳上累加。未 install 时静默丢弃（旁路语义）。 */
    public static void accumulate(Usage usage) {
        Acc acc = ACC.get();
        if (acc == null || usage == null) {
            return;
        }
        acc.prompt += safe(usage.getPromptTokens());
        acc.completion += safe(usage.getCompletionTokens());
        acc.calls++;
    }

    private static long safe(Number n) {
        return n == null ? 0L : n.longValue();
    }

    private static final class Acc {
        long prompt;
        long completion;
        int calls;

        Totals toTotals() {
            return new Totals(prompt, completion, calls);
        }
    }
}
