package com.aismp.orchestrator.llm;

import com.aismp.orchestrator.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 意图路由：把模型的一次输出变成一条确定的路线。
 *
 * <p>这里只管<b>解析与降级规则</b>，HTTP 由 {@link RouteLlmClient} 负责 —— 好处是
 * 「模型吐脏数据」「连续失败」「没配 Key」这些分支全部可以脱网测（见 LlmRouterTest）。
 *
 * <p>失败语义与原版一致：最多试 3 次，仍解析不出来就降级 kb（并带上原因）。
 * 宁可回答得「泛」，也不让用户收到一个异常帧。
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    /** 长词优先匹配，避免输出里带杂质时误判（如 "kbb"）——与原版 graph.py:429 一致 */
    private static final List<String> ROUTES_IN_PRIORITY = List.of("replenish", "chart", "kb");

    private final OrchestratorProperties properties;
    private final RouteLlmClient client;

    public LlmRouter(OrchestratorProperties properties, RouteLlmClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * 给出一句话的路线判断。
     *
     * @param query  用户问题原文
     * @param userId 发起人（计量留痕用，可为空）
     */
    public RouteDecision route(String query, String userId) {
        long start = System.currentTimeMillis();
        String apiKey = properties.getLlm().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return new RouteDecision("kb", "未配置 LLM Key，降级知识问答",
                    System.currentTimeMillis() - start);
        }

        String lastError = "";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String raw = client.complete(query);
                String lowered = raw == null ? "" : raw.toLowerCase();
                String matched = ROUTES_IN_PRIORITY.stream()
                        .filter(lowered::contains)
                        .findFirst()
                        .orElse(null);
                if (matched == null && lowered.contains("order")) {
                    // 模型偶尔仍吐旧标签 order —— 建单不是意图，归入补货线
                    matched = "replenish";
                    lowered = "order（归一为 replenish）";
                }
                if (matched != null) {
                    return new RouteDecision(matched, trim(lowered, 60),
                            System.currentTimeMillis() - start);
                }
                lastError = "第" + attempt + "次无法解析：" + trim(raw, 40);
            } catch (RuntimeException e) {
                lastError = "第" + attempt + "次异常：" + e.getClass().getSimpleName();
                log.warn("路由 LLM 调用失败（attempt={}, user={}）", attempt, userId, e);
            }
        }
        return new RouteDecision("kb", "路由降级（" + lastError + "）",
                System.currentTimeMillis() - start);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max);
    }

    /** 路由结论：路线 + 依据（原样透出，便于观察模型判断质量）+ 耗时。 */
    public record RouteDecision(String route, String reason, long durationMs) {
    }
}
