package com.aismp.orchestrator.llm;

import com.aismp.orchestrator.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 意图路由用的小 LLM 客户端。
 *
 * <p>只走 OpenAI 兼容的 {@code /chat/completions}，不引入 Spring AI 的 model 抽象：
 * 迁移期最重要的是**行为等价**——原 Python 版（langchain 的 openai client）发的就是这个请求，
 * 这里逐字对齐 model / temperature / messages 结构，路由结果才可比。
 *
 * <p>为什么用 RestClient 而不是 Jackson 手动反序列化：直接 {@code body(Map.class)} 交给
 * Spring 的转换器，规避 Spring Boot 4 从 Jackson 2 切到 Jackson 3 的包名差异。
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    /**
     * 路由提示词。要求只输出一个词，本地再做包含匹配（防模型自由发挥）——
     * 与原版 graph.py:426 的处理方式一致：模型输出宽松，解析严格。
     */
    private static final String ROUTE_PROMPT = """
            你是意图路由器，只做分类，不回答用户问题。
            把用户问题归入下列三类之一，只输出一个英文单词，不要标点、不要解释：
            replenish —— 涉及补货、补仓、库存缺口、水位线、该补多少、库存健康、周转
            chart     —— 涉及画图、趋势、分布、占比、统计、对比、走势
            kb        —— 其他一切（操作手册、流程规则、故障排查、闲聊）
            用户问题：%s
            """;

    private static final List<String> ROUTES_IN_PRIORITY = List.of("replenish", "chart", "kb");

    private final OrchestratorProperties properties;
    private final RestClient restClient;

    public LlmRouter(OrchestratorProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    /**
     * 给出一句话的路线判断。
     *
     * <p>失败语义与原版一致：最多试 3 次，仍解析不出来就降级 kb（并带上原因）。
     * 宁可回答得「泛」，也不让用户收到一个异常帧。
     *
     * @param query  用户问题原文
     * @param userId 发起人（计量留痕用，可为空）
     */
    public RouteDecision route(String query, String userId) {
        long start = System.currentTimeMillis();
        OrchestratorProperties.Llm cfg = properties.getLlm();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            return new RouteDecision("kb", "未配置 LLM Key，降级知识问答",
                    System.currentTimeMillis() - start);
        }

        String lastError = "";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String raw = callOnce(query);
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

    @SuppressWarnings("unchecked")
    private String callOnce(String query) {
        OrchestratorProperties.Llm cfg = properties.getLlm();
        Map<String, Object> body = Map.of(
                "model", cfg.getModel(),
                "temperature", 0,
                "stream", false,
                "messages", List.of(Map.of("role", "user",
                        "content", ROUTE_PROMPT.formatted(query))));

        Map<String, Object> response = restClient.post()
                .uri(cfg.getBaseUrl() + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new IllegalStateException("空响应");
        }
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("响应缺少 choices");
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            throw new IllegalStateException("choices[0] 结构异常");
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> msg)) {
            throw new IllegalStateException("choices[0].message 结构异常");
        }
        return String.valueOf(msg.get("content"));
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
