package com.aismp.aismpagent.model;

// 终止工具（让 Agent 自主决定任务结束）
import com.aismp.aismpagent.tools.TerminateTool;
// 工单查询工具
import com.aismp.aismpagent.tools.QueryOrderStatusTool;
// 审批人查询工具
import com.aismp.aismpagent.tools.QueryApproverTool;
// 权限查询工具
import com.aismp.aismpagent.tools.QueryMyPermissionTool;
// 自定义日志 Advisor
import com.aismp.aismpagent.advisor.MyLoggerAdvisor;
// RAG 查询转换器（查询重写 + 分类）
import com.aismp.aismpagent.rag.OaRagQueryTransformer;
// Jakarta 资源注入注解
import jakarta.annotation.Resource;
// Lombok 日志注解
import lombok.extern.slf4j.Slf4j;
// Spring AI 聊天客户端
import org.springframework.ai.chat.client.ChatClient;
// Spring AI 聊天模型（大模型接口）
import org.springframework.ai.chat.model.ChatModel;
// Spring AI 工具回调工具类
import org.springframework.ai.tool.ToolCallback;
// Spring AI 工具扫描器
import org.springframework.ai.support.ToolCallbacks;
// Spring AI 文档接口
import org.springframework.ai.document.Document;
// Spring AI RAG 查询对象
import org.springframework.ai.rag.Query;
// Spring AI 文档检索器（获取 FILTER_EXPRESSION 常量）
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
// Spring AI 向量检索请求
import org.springframework.ai.vectorstore.SearchRequest;
// Spring AI 向量存储接口
import org.springframework.ai.vectorstore.VectorStore;
// Spring AI 对话记忆接口
import org.springframework.ai.chat.memory.ChatMemory;
// Spring AI 助手消息
import org.springframework.ai.chat.messages.AssistantMessage;
// Spring AI 用户消息
import org.springframework.ai.chat.messages.UserMessage;
// Spring AI 消息接口
import org.springframework.ai.chat.messages.Message;
// Spring 组件注解
import org.springframework.stereotype.Component;
// Spring Web SSE 发射器
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
// Java 列表
import java.util.List;
// Java 流式收集
import java.util.stream.Collectors;

/**
 * OA 客服智能体（ReAct 模式 + Always-on RAG + 多轮记忆）
 *
 * 架构：方案 B（Always-on RAG + Agent 工具调用）
 * - 在 ReAct 循环开始前，先做一次 RAG 检索（查询重写 + 分类 + 向量检索）
 * - 检索到的知识库上下文拼入用户消息，贯穿整个 ReAct 循环
 * - Agent 的 3 个工具只负责查询实时数据库（工单/审批人/权限）
 * - 循环前从 ChatMemory 加载历史消息，循环后保存本轮 Q&A 到 ChatMemory
 *
 * 继承自 ToolCallAgent，集成 4 个工具：
 * 1. queryOrderStatus — 查工单流转状态
 * 2. queryApprover — 查审批人信息
 * 3. queryMyPermission — 查自己权限
 * 4. doTerminate — 终止任务
 */
// 注册为 Spring Bean
@Component
// 启用日志
@Slf4j
public class OaAgent extends ToolCallAgent {

    // RAG 查询转换器（查询重写 + 分类），Spring 自动注入
    @Resource
    private OaRagQueryTransformer oaRagQueryTransformer;

    // 向量存储（PGVector），Spring 自动注入
    @Resource
    private VectorStore vectorStore;

    // 对话记忆（PG 持久化），Spring 自动注入
    @Resource
    private ChatMemory chatMemory;

    // 当前会话 ID（由 Controller 设置，用于多轮记忆的加载和保存）
    private String currentConversationId;

    // 当前轮次的原始用户消息（RAG 增强前的文本，保存到 ChatMemory 时用）
    private String originalUserPrompt;

    // 混合检索（多查询 + 关键词双路 + RRF + Rerank）；无分类过滤时走它
    @Resource
    private com.aismp.aismpagent.rag.HybridRetrievalService hybridRetrieval;

    /**
     * 构造函数 —— Spring 自动注入所有工具和 ChatModel
     */
    public OaAgent(QueryOrderStatusTool queryOrderStatusTool,
                   QueryApproverTool queryApproverTool,
                   QueryMyPermissionTool queryMyPermissionTool,
                   TerminateTool terminateTool,
                   ChatModel chatModel) {
        // 调用父类构造，传入 4 个工具
        super(ToolCallbacks.from(
                queryOrderStatusTool,
                queryApproverTool,
                queryMyPermissionTool,
                terminateTool
        ));
        // 设置智能体名称（日志标识）
        this.setName("OaAgent");
        // 定义系统提示词
        this.setSystemPrompt("""
                你是一个 OA 系统智能客服。

                用户消息中可能包含【知识库上下文】，这是从 FAQ 知识库检索到的相关文档。
                请优先基于知识库上下文回答用户的操作类、流程类、概念类问题。

                你还有以下工具可用于查询实时数据：
                1. queryOrderStatus — 查询工单流转状态
                2. queryApprover — 查询审批人信息
                3. queryMyPermission — 查询用户权限

                工作原则：
                - 如果用户消息包含知识库上下文，优先基于上下文回答
                - 如果用户询问工单状态、审批人、权限等实时信息，调用对应工具
                - 回答要简洁、准确、有条理
                - 如果知识库上下文和工具都无法回答，礼貌告知用户
                - 涉及他人信息时只显示脱敏结果
                - 任务完成后调用 doTerminate 结束
                """);
        // 定义下一步提示词
        this.setNextStepPrompt("""
                Based on the user's question, the knowledge base context (if provided), and previous tool results, decide the next action:
                - If you need to query real-time data (order status, approver, permission), use the corresponding tool.
                - If you have enough information to answer the user (from knowledge base context or tool results), provide your answer and use doTerminate to end.
                """);
        // 设置最大步数为 10
        this.setMaxSteps(10);
        // 以注入的 ChatModel 为引擎创建 ChatClient，注册日志 Advisor
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        // 保存 ChatClient 到父类属性
        this.setChatClient(chatClient);
    }

    /**
     * 设置当前会话 ID（由 Controller 调用）
     */
    public void setCurrentConversationId(String conversationId) {
        this.currentConversationId = conversationId;
    }

    /**
     * 重写 run()：加载历史 → RAG 预处理 → ReAct 循环
     */
    @Override
    public String run(String userPrompt) {
        // 保存原始用户消息（RAG 增强前的文本，供 cleanup() 保存到 ChatMemory）
        this.originalUserPrompt = userPrompt;
        // 加载对话历史到 messageList
        loadHistory();
        // RAG 预处理
        String augmentedPrompt = augmentWithRag(userPrompt);
        // 调用父类 run()，启动 ReAct 循环（cleanup() 会在 finally 中自动保存记忆）
        return super.run(augmentedPrompt);
    }

    /**
     * 重写 runStream()：加载历史 → RAG 预处理 → 流式 ReAct 循环
     */
    @Override
    public SseEmitter runStream(String userPrompt) {
        // 保存原始用户消息
        this.originalUserPrompt = userPrompt;
        // 加载对话历史
        loadHistory();
        // RAG 预处理
        String augmentedPrompt = augmentWithRag(userPrompt);
        // 调用父类 runStream()（cleanup() 会在 finally 中自动保存记忆）
        return super.runStream(augmentedPrompt);
    }

    /**
     * 重写 cleanup()：Agent 执行完成后保存本轮 Q&A 到 ChatMemory
     *
     * BaseAgent.run() 和 runStream() 的 finally 块都会调用 cleanup()，
     * 所以在这里统一保存记忆，无需在 run()/runStream() 中分别处理。
     *
     * 只保存：原始用户消息 + Agent 最终回答（不保存 RAG 增强文本、工具调用等中间过程）
     */
    @Override
    protected void cleanup() {
        // 只在正常完成时保存（排除 ERROR 状态）
        if (currentConversationId != null
                && originalUserPrompt != null
                && getLastThinkText() != null
                && getState() == AgentState.FINISHED) {
            // 保存原始用户消息和 Agent 最终回答（干净的 Q&A 对，不含 RAG 上下文和工具结果）
            chatMemory.add(currentConversationId, List.of(
                    new UserMessage(originalUserPrompt),
                    new AssistantMessage(getLastThinkText())
            ));
            log.info("Agent 多轮记忆：已保存会话 {} 的 Q&A 到 ChatMemory", currentConversationId);
        }
        // 清理本轮临时状态
        this.originalUserPrompt = null;
    }

    /**
     * 加载对话历史：从 ChatMemory 读取历史消息，追加到 messageList
     *
     * Controller 在调用 run() 前已清空 messageList，所以这里直接 addAll。
     * 加载历史后，大模型在 think() 中能看到之前的对话上下文。
     */
    private void loadHistory() {
        // 没有会话 ID，跳过（单轮模式）
        if (currentConversationId == null) {
            return;
        }
        // 从 PG 加载该会话的历史消息（MessageWindowChatMemory 自动控制窗口大小）
        List<Message> history = chatMemory.get(currentConversationId);
        // 如果有历史，追加到 messageList
        if (history != null && !history.isEmpty()) {
            getMessageList().addAll(history);
            log.info("Agent 多轮记忆：加载会话 {} 的 {} 条历史消息", currentConversationId, history.size());
        }
    }

    /**
     * RAG 预处理：查询重写 + 分类 + 向量检索 + 拼接上下文
     */
    private String augmentWithRag(String userPrompt) {
        // 1. 查询重写 + 分类（一次 LLM 调用，使用 glm-4-flash）
        Query originalQuery = Query.builder()
                .text(userPrompt)
                .build();
        // 调用查询转换器：重写口语化问题 + 判断分类
        Query transformedQuery = oaRagQueryTransformer.transform(originalQuery);

        // 2. 从转换后的 Query context 提取 filter 表达式
        String filterExpr = (String) transformedQuery.context()
                .get(VectorStoreDocumentRetriever.FILTER_EXPRESSION);

        // 3. 取检索结果
        List<String> passages;
        // 分类过滤（category in [...]）直接透传给混合检索：
        // 向量路走 SQL 过滤，关键词路按同口径收口，两路都不会越过分区
        passages = hybridRetrieval.search(transformedQuery.text(), filterExpr);
        if (filterExpr != null && !filterExpr.isEmpty()) {
            log.info("Agent RAG 预处理：分类过滤 {}", filterExpr);
        }

        // 4. 检查检索结果
        if (passages.isEmpty()) {
            log.info("Agent RAG 预处理：无匹配文档，使用原始用户消息");
            return userPrompt;
        }

        // 5. 拼接知识库上下文到用户消息
        log.info("Agent RAG 预处理：匹配到 {} 段", passages.size());
        String context = String.join("\n\n---\n\n", passages);

        return "以下是从知识库检索到的相关文档，请基于这些内容回答用户问题：\n\n" +
                "【知识库上下文】\n" + context + "\n\n" +
                "【用户问题】\n" + userPrompt;
    }
}
