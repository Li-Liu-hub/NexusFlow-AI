package com.aismp.orchestrator.llm;

import com.aismp.orchestrator.config.OrchestratorProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 {@code /chat/completions} 的路由通道实现。
 *
 * <p>不引入 Spring AI 的 model 抽象：迁移期最重要的是**行为等价** ——
 * 原 Python 版（langchain 的 openai client）发的就是这个请求，
 * 这里逐字对齐 model / temperature / messages 结构，路由结果才可比。
 *
 * <p>用 {@code body(Map.class)} 交给 Spring 转换器，规避 Spring Boot 4
 * 从 Jackson 2 切到 Jackson 3 的包名差异。
 */
@Component
public class HttpRouteLlmClient implements RouteLlmClient {

    /**
     * 路由提示词。要求只输出一个词，本地再做包含匹配（防模型自由发挥）——
     * 与原版 graph.py:426 一致：模型输出宽松，解析严格。
     */
    private static final String ROUTE_PROMPT = """
            你是意图路由器，只做分类，不回答用户问题。
            把用户问题归入下列三类之一，只输出一个英文单词，不要标点、不要解释：
            replenish —— 涉及补货、补仓、库存缺口、水位线、该补多少、库存健康、周转
            chart     —— 涉及画图、趋势、分布、占比、统计、对比、走势
            kb        —— 其他一切（操作手册、流程规则、故障排查、闲聊）
            用户问题：%s
            """;

    private final OrchestratorProperties properties;
    private final RestClient restClient;

    public HttpRouteLlmClient(OrchestratorProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    @Override
    public String complete(String query) {
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
        if (!(list.get(0) instanceof Map<?, ?> choice)) {
            throw new IllegalStateException("choices[0] 结构异常");
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)) {
            throw new IllegalStateException("choices[0].message 结构异常");
        }
        return String.valueOf(message.get("content"));
    }
}
