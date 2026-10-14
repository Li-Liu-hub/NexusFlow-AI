package com.aismp.aismpagent.advisor;

// ChatMemory 接口，用于读写对话记忆
import org.springframework.ai.chat.memory.ChatMemory;
// 消息类型枚举（USER / ASSISTANT / SYSTEM）
import org.springframework.ai.chat.messages.MessageType;
// 用户消息类
import org.springframework.ai.chat.messages.UserMessage;
// AI 助手消息类
import org.springframework.ai.chat.messages.AssistantMessage;
// 消息接口
import org.springframework.ai.chat.messages.Message;
// Prompt 类，封装发给 LLM 的完整请求
import org.springframework.ai.chat.prompt.Prompt;
// ChatClientRequest，Advisor 链中的请求对象
import org.springframework.ai.chat.client.ChatClientRequest;
// ChatClientResponse，Advisor 链中的响应对象
import org.springframework.ai.chat.client.ChatClientResponse;
// BaseAdvisor 接口，Spring AI Advisor 体系的基础接口
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
// AdvisorChain，Advisor 链上下文
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
// StreamAdvisorChain，流式 Advisor 链
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
// EmbeddingModel，用于计算文本向量
import org.springframework.ai.embedding.EmbeddingModel;
// Reactor Flux（流式返回）
import reactor.core.publisher.Flux;
// Lombok 日志注解
import lombok.extern.slf4j.Slf4j;

// Java 集合类
import java.util.*;

/**
 * 选择性记忆 Advisor（替代 MessageChatMemoryAdvisor）
 *
 * 核心区别：
 * - MessageChatMemoryAdvisor：全量加载历史 → 全部塞给 LLM
 * - SelectiveMemoryAdvisor：全量加载历史 → embedding 相似度筛选 → 只塞相关的给 LLM
 *
 * 解决的问题：多轮对话中的跨话题污染
 * 用户先问密码重置，再问天气，历史中 4 条密码消息会干扰天气问题的回答
 *
 * 实现方式：
 * 1. before：加载全部历史，对每条 USER 消息算 embedding 相似度，只保留相关的（USER + 配对的 ASSISTANT）
 * 2. after：全量保存当前轮 Q&A 到 ChatMemory（不影响后续检索）
 *
 * 性能：embedding 计算是毫秒级，几十条历史消息约几十毫秒
 */
// 启用日志
@Slf4j
public class SelectiveMemoryAdvisor implements BaseAdvisor {

    // 对话记忆存储（和 MessageChatMemoryAdvisor 用的是同一个 ChatMemory Bean）
    private final ChatMemory chatMemory;

    // Embedding 模型，用于计算文本向量
    private final EmbeddingModel embeddingModel;

    // 相似度阈值，高于此值才认为是对历史问题的追问
    private final double similarityThreshold;

    // Advisor 执行顺序（和 MessageChatMemoryAdvisor 一致，默认 0）
    private final int order;

    // context 中存储原始用户消息的 key（由 Controller 通过 spec.param 注入，绕过 Advisor 顺序问题）
    private static final String ORIGINAL_USER_TEXT_KEY = "ORIGINAL_USER_TEXT";

    /**
     * 私有构造函数，通过 Builder 创建
     */
    private SelectiveMemoryAdvisor(Builder builder) {
        // 注入 ChatMemory
        this.chatMemory = builder.chatMemory;
        // 注入 EmbeddingModel
        this.embeddingModel = builder.embeddingModel;
        // 设置相似度阈值
        this.similarityThreshold = builder.similarityThreshold;
        // 设置执行顺序
        this.order = builder.order;
    }

    /**
     * Advisor 执行顺序
     * 必须在 RAG Advisor 之前执行（先加载记忆，再做 RAG 检索）
     */
    @Override
    public int getOrder() {
        // 返回配置的顺序值
        return order;
    }

    /**
     * ★ 同步模式专用：覆写 adviseCall
     *
     * 从 advisor context 读取原始用户文本（由 Controller 通过 spec.param 注入）
     * 避免 RAG Advisor 增强后的文本被误存到 ChatMemory
     */
    @Override
    public org.springframework.ai.chat.client.ChatClientResponse adviseCall(
            ChatClientRequest request, org.springframework.ai.chat.client.advisor.api.CallAdvisorChain chain) {
        // 从 context 读取 Controller 注入的原始用户文本（绕过 Advisor 顺序问题）
        String originalUserText = getOriginalUserText(request.context());
        // 捕获 RAG 增强后的用户消息文本，仅供日志检查
        String augmentedUserText = "";
        if (request.prompt().getUserMessage() != null) {
            augmentedUserText = request.prompt().getUserMessage().getText();
        }
        // 调用 before()：加载筛选后的历史消息
        ChatClientRequest advisedRequest = this.before(request, chain);
        // 调用下游 Advisor 链（最终调 LLM）
        org.springframework.ai.chat.client.ChatClientResponse response = chain.nextCall(advisedRequest);

        // 获取 conversationId
        String conversationId = getConversationId(request.context());
        // 使用从 context 读取的原始用户文本
        String userText = originalUserText;
        // 获取助手回复
        String assistantText = "";
        if (response.chatResponse() != null
                && response.chatResponse().getResult() != null
                && response.chatResponse().getResult().getOutput() != null) {
            assistantText = response.chatResponse().getResult().getOutput().getText();
        }
        // 保存到 ChatMemory
        if (userText != null && !userText.isEmpty()
                && assistantText != null && !assistantText.isEmpty()) {
            chatMemory.add(conversationId, List.of(
                    new UserMessage(userText),
                    new AssistantMessage(assistantText)
            ));
            log.info("选择性记忆（同步）：已保存对话到 conversationId={}", conversationId);
            log.info("选择性记忆（同步）：原始问题='{}'", userText);
            log.info("选择性记忆（同步）：增强后文本前200字='{}'",
                    augmentedUserText != null ? augmentedUserText.substring(0, Math.min(200, augmentedUserText.length())) : "null");
        }

        // 返回响应
        return response;
    }

    /**
     * ★ 流式模式专用：覆写 adviseStream
     *
     * 默认的 BaseAdvisor.adviseStream 会对每个 chunk 调用 after()，导致记忆保存失败
     * （每个 chunk 只有部分文本，不是完整回复）
     *
     * 这里手动处理：
     * 1. before()：加载筛选后的历史
     * 2. 流式传输给下游 Advisor
     * 3. doOnNext：逐 chunk 累积助手回复文本
     * 4. doFinally：流结束后，一次性保存完整的 Q&A 到 ChatMemory
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // 从 context 读取 Controller 注入的原始用户文本（绕过 Advisor 顺序问题）
        final String originalUserText = getOriginalUserText(request.context());
        // 捕获 RAG 增强后的用户消息文本（此时 prompt 可能已被 RAG 修改），仅供日志检查
        final String augmentedUserText;
        if (request.prompt().getUserMessage() != null) {
            augmentedUserText = request.prompt().getUserMessage().getText();
        } else {
            augmentedUserText = "";
        }

        // 调用 before()：加载筛选后的历史消息
        ChatClientRequest advisedRequest = this.before(request, chain);

        // 创建 StringBuilder 累积助手回复文本（流式模式下每个 chunk 只有部分文本）
        StringBuilder assistantText = new StringBuilder();

        // 传递给下游 Advisor 并处理流式响应
        return chain.nextStream(advisedRequest)
                // doOnNext：每个 chunk 到达时，累积文本
                .doOnNext(response -> {
                    // 检查响应是否有效
                    if (response.chatResponse() != null
                            && response.chatResponse().getResult() != null
                            && response.chatResponse().getResult().getOutput() != null) {
                        // 获取当前 chunk 的文本
                        String chunk = response.chatResponse().getResult().getOutput().getText();
                        // 累积非空文本
                        if (chunk != null) {
                            assistantText.append(chunk);
                        }
                    }
                })
                // doFinally：流结束后（无论成功、取消、错误），保存完整 Q&A 到 ChatMemory
                .doFinally(signal -> {
                    // 获取 conversationId
                    String conversationId = getConversationId(request.context());
                    // 使用在 adviseStream 开头捕获的原始用户文本（不依赖 request.prompt()，因为可能已被下游修改）
                    String userText = originalUserText;
                    // 只有用户消息和助手回复都有效时才保存
                    if (userText != null && !userText.isEmpty()
                            && assistantText.length() > 0) {
                        // 创建消息列表：用户消息 + 完整助手回复
                        List<Message> messages = List.of(
                                // 用户消息
                                new UserMessage(userText),
                                // 完整助手回复（累积后的全文）
                                new AssistantMessage(assistantText.toString())
                        );
                        // 全量保存到 ChatMemory
                        chatMemory.add(conversationId, messages);
                        // 打印保存日志，同时输出原始问题和增强后文本便于检查
                        log.info("选择性记忆（流式）：已保存对话到 conversationId={}", conversationId);
                        log.info("选择性记忆（流式）：原始问题='{}'", userText);
                        log.info("选择性记忆（流式）：增强后文本前200字='{}'",
                                augmentedUserText != null ? augmentedUserText.substring(0, Math.min(200, augmentedUserText.length())) : "null");
                    }
                });
    }

    /**
     * 从 context 中获取原始用户文本
     * Controller 通过 .advisors(spec -> spec.param("ORIGINAL_USER_TEXT", message)) 注入
     * 绕过 Advisor 顺序问题，确保读到的是用户原始问题而非 RAG 增强后的文本
     */
    private String getOriginalUserText(Map<String, Object> context) {
        // 从 context 中取原始用户文本
        Object text = context.get(ORIGINAL_USER_TEXT_KEY);
        // 如果有值则返回，否则返回空字符串
        return text != null ? text.toString() : "";
    }

    /**
     * 从 context 中获取 conversationId
     * Controller 通过 .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId)) 传入
     */
    private String getConversationId(Map<String, Object> context) {
        // 从 context 中取 CONVERSATION_ID
        Object id = context.get(ChatMemory.CONVERSATION_ID);
        // 如果有值则返回，否则返回默认 ID
        return id != null ? id.toString() : ChatMemory.DEFAULT_CONVERSATION_ID;
    }

    /**
     * ★ 核心方法一：请求阶段（调 LLM 之前）
     *
     * 1. 加载全部历史消息
     * 2. 对当前问题和每条历史 USER 消息算 embedding 相似度
     * 3. 只保留相关的历史消息（USER + 配对的 ASSISTANT）
     * 4. 将筛选后的历史插入 prompt
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        // 获取 conversationId
        String conversationId = getConversationId(request.context());

        // 获取当前 prompt
        Prompt prompt = request.prompt();
        // 获取当前用户消息
        UserMessage currentUserMessage = prompt.getUserMessage();
        // 如果没有用户消息（异常情况），直接返回
        if (currentUserMessage == null) {
            return request;
        }

        // 获取当前用户消息文本：优先从 context 读取原始文本（避免 RAG 增强后的文本影响 embedding 准确性）
        String currentText = getOriginalUserText(request.context());
        // 如果 context 中没有（非标准入口调用），兜底从 prompt 取
        if (currentText == null || currentText.isEmpty()) {
            currentText = currentUserMessage.getText();
        }

        // 从 ChatMemory 加载该会话的全部历史消息
        List<Message> allHistory = chatMemory.get(conversationId);

        // 如果没有历史，直接返回（首轮对话无需筛选）
        if (allHistory == null || allHistory.isEmpty()) {
            // 打印日志
            log.info("选择性记忆：无历史消息，直接返回");
            return request;
        }

        // 对当前问题计算 embedding 向量
        float[] currentEmbedding = embeddingModel.embed(currentText);

        // 调用筛选方法，选出与当前问题语义相关的历史消息
        List<Message> selectedHistory = selectRelevantMessages(allHistory, currentEmbedding);

        // 构建新的消息列表：SystemMessage → 筛选后的历史 → 当前消息（非 SYSTEM 的原始消息）
        List<Message> newMessages = new ArrayList<>();

        // 遍历原始 prompt 中的消息
        for (Message msg : prompt.getInstructions()) {
            // 先把 SYSTEM 消息放最前面
            if (msg.getMessageType() == MessageType.SYSTEM) {
                newMessages.add(msg);
            }
        }

        // 插入筛选后的历史消息（在 SYSTEM 和当前用户消息之间）
        newMessages.addAll(selectedHistory);

        // 遍历原始 prompt 中的消息
        for (Message msg : prompt.getInstructions()) {
            // 再把非 SYSTEM 的消息放最后（即当前用户消息）
            if (msg.getMessageType() != MessageType.SYSTEM) {
                newMessages.add(msg);
            }
        }

        // 构建新的 Prompt（保留原始 ChatOptions）
        Prompt newPrompt = new Prompt(newMessages, prompt.getOptions());

        // 返回修改后的请求
        return request.mutate().prompt(newPrompt).build();
    }

    /**
     * ★ 核心筛选逻辑
     *
     * 遍历历史消息中的每条 USER 消息，算与当前问题的余弦相似度
     * 相似度 ≥ 阈值的 USER 消息 + 其紧跟的 ASSISTANT 回复（成对保留）
     * 最终按时间顺序返回筛选后的的消息列表
     *
     * 性能优化：批量 embedding（1 次 API 调用替代 N 次）
     */
    private List<Message> selectRelevantMessages(List<Message> allHistory, float[] currentEmbedding) {
        // 需要保留的消息索引集合（TreeSet 自动排序，保持时间顺序）
        Set<Integer> indicesToInclude = new TreeSet<>();

        // 第一步：收集所有 USER 类型消息的文本和索引
        List<String> userTexts = new ArrayList<>();
        // 记录每条 USER 消息在 allHistory 中的原始索引
        List<Integer> userIndices = new ArrayList<>();
        // 遍历所有历史消息
        for (int i = 0; i < allHistory.size(); i++) {
            // 获取第 i 条消息
            Message msg = allHistory.get(i);
            // 只收集 USER 类型的消息
            if (msg.getMessageType() == MessageType.USER) {
                // 收集文本
                userTexts.add(msg.getText());
                // 记录原始索引
                userIndices.add(i);
            }
        }

        // 如果没有 USER 消息，直接返回空列表
        if (userTexts.isEmpty()) {
            log.info("选择性记忆：无 USER 类型历史消息");
            return List.of();
        }

        // 第二步：批量计算 embedding（1 次 API 调用替代 N 次）
        List<float[]> userEmbeddings = embeddingModel.embed(userTexts);

        // 第三步：逐条算相似度，标记需要保留的消息
        // 遍历每条 USER 消息
        for (int j = 0; j < userIndices.size(); j++) {
            // 获取该 USER 消息在 allHistory 中的原始索引
            int i = userIndices.get(j);
            // 获取对应的 embedding 向量
            float[] msgEmbedding = userEmbeddings.get(j);
            // 计算余弦相似度
            double similarity = cosineSimilarity(currentEmbedding, msgEmbedding);
            // 截取消息前 30 个字符用于日志显示
            String preview = userTexts.get(j).length() > 30
                    ? userTexts.get(j).substring(0, 30) + "..." : userTexts.get(j);
            // 打印每条历史消息的相似度
            log.info("选择性记忆：历史消息[{}] 相似度={} '{}'", i, String.format("%.4f", similarity), preview);

            // 如果相似度 ≥ 阈值，保留这条 USER 消息
            if (similarity >= similarityThreshold) {
                // 标记当前 USER 消息索引
                indicesToInclude.add(i);
                // 如果下一条是 ASSISTANT 回复，也一起保留（成对保留，光有问题没有回答没用）
                if (i + 1 < allHistory.size()
                        && allHistory.get(i + 1).getMessageType() == MessageType.ASSISTANT) {
                    // 标记配对的 ASSISTANT 消息索引
                    indicesToInclude.add(i + 1);
                }
            }
        }

        // 构建筛选后的消息列表（按索引顺序，即时间顺序）
        List<Message> result = new ArrayList<>();
        // 遍历所有需要保留的索引
        for (int idx : indicesToInclude) {
            // 从历史中取出对应消息
            result.add(allHistory.get(idx));
        }

        // 打印筛选结果日志
        log.info("选择性记忆：{} 条历史 → 筛选后 {} 条（阈值={}）",
                allHistory.size(), result.size(), similarityThreshold);

        // 返回筛选后的消息列表
        return result;
    }

    /**
     * after() 方法 — 空操作
     *
     * 记忆保存逻辑已移到 adviseCall() 和 adviseStream() 中
     * 因为这两处能直接访问原始 request，获取用户原始消息文本
     * （after() 只能拿到 response，无法可靠获取原始用户消息）
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        // 直接返回响应，不做任何处理
        return response;
    }

    /**
     * 计算两个向量的余弦相似度
     * 公式：cos(θ) = (A·B) / (|A| × |B|)
     * 值域 [-1, 1]，越接近 1 表示越相似
     */
    private double cosineSimilarity(float[] a, float[] b) {
        // 点积
        double dotProduct = 0;
        // 向量 A 的模的平方
        double normA = 0;
        // 向量 B 的模的平方
        double normB = 0;
        // 遍历向量的每个维度
        for (int i = 0; i < a.length; i++) {
            // 累加点积
            dotProduct += a[i] * b[i];
            // 累加 A 的模平方
            normA += (double) a[i] * a[i];
            // 累加 B 的模平方
            normB += (double) b[i] * b[i];
        }
        // 如果任一向量为零向量，返回 0（避免除以零）
        if (normA == 0 || normB == 0) {
            return 0;
        }
        // 计算并返回余弦相似度
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 静态工厂方法：创建 Builder
     *
     * @param chatMemory     对话记忆存储
     * @param embeddingModel Embedding 模型
     * @return Builder 实例
     */
    public static Builder builder(ChatMemory chatMemory, EmbeddingModel embeddingModel) {
        // 创建并返回 Builder
        return new Builder(chatMemory, embeddingModel);
    }

    /**
     * Builder 模式，支持链式配置
     */
    public static class Builder {

        // 对话记忆存储（必填）
        private final ChatMemory chatMemory;
        // Embedding 模型（必填）
        private final EmbeddingModel embeddingModel;
        // 相似度阈值（默认 0.5）
        private double similarityThreshold = 0.5;
        // 执行顺序（默认 0）
        private int order = 0;

        /**
         * Builder 构造函数
         */
        public Builder(ChatMemory chatMemory, EmbeddingModel embeddingModel) {
            // 注入 ChatMemory
            this.chatMemory = chatMemory;
            // 注入 EmbeddingModel
            this.embeddingModel = embeddingModel;
        }

        /**
         * 设置相似度阈值
         */
        public Builder similarityThreshold(double threshold) {
            // 设置阈值
            this.similarityThreshold = threshold;
            return this;
        }

        /**
         * 设置执行顺序
         */
        public Builder order(int order) {
            // 设置顺序
            this.order = order;
            return this;
        }

        /**
         * 构建 SelectiveMemoryAdvisor 实例
         */
        public SelectiveMemoryAdvisor build() {
            // 创建实例
            return new SelectiveMemoryAdvisor(this);
        }
    }
}
