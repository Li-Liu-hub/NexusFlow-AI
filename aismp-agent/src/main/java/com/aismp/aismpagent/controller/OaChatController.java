package com.aismp.aismpagent.controller;

// 选择性记忆 Advisor（替代 MessageChatMemoryAdvisor，解决跨话题污染）
import com.aismp.aismpagent.advisor.SelectiveMemoryAdvisor;
// RAG Advisor 工厂（模块 6 的管道）
import com.aismp.aismpagent.rag.OaRagAdvisorFactory;
// 三个查询工具（模块 9）
import com.aismp.aismpagent.tools.QueryApproverTool;
import com.aismp.aismpagent.tools.QueryMyPermissionTool;
import com.aismp.aismpagent.tools.QueryOrderStatusTool;
// Jakarta 资源注入注解
import jakarta.annotation.Resource;
// Lombok 日志注解
import lombok.extern.slf4j.Slf4j;
// Spring AI ChatClient，构建大模型调用链
import org.springframework.ai.chat.client.ChatClient;
// Spring AI 对话记忆接口
import org.springframework.ai.chat.memory.ChatMemory;
// Spring AI 大模型抽象接口
import org.springframework.ai.chat.model.ChatModel;
// Spring AI Embedding 模型（用于选择性记忆的向量计算）
import org.springframework.ai.embedding.EmbeddingModel;
// Spring AI 模块化 RAG 管道 Advisor
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
// Spring Web REST 控制器注解
import org.springframework.web.bind.annotation.*;
// Reactor Flux（流式返回）
import reactor.core.publisher.Flux;

// Java Map 工具
import java.util.Map;

/**
 * OA 智能客服对话接口
 * 接入 ChatMemory（多轮记忆）+ RetrievalAugmentationAdvisor（RAG 检索增强）+ 查询工具（实时数据）
 * 知识型问题走 RAG（基于 FAQ），查询型问题走工具调用（查数据库）
 */
// 注册为 REST 控制器
@RestController
// 基础路径 /oa
@RequestMapping("/oa")
// 启用日志
@Slf4j
public class OaChatController {

    // 对话记忆（模块 2 的 PG 持久化记忆）
    @Resource
    private ChatMemory chatMemory;

    // 大模型
    @Resource
    private ChatModel chatModel;

    // Embedding 模型（用于选择性记忆的相似度计算）
    @Resource
    private EmbeddingModel embeddingModel;

    // RAG Advisor 工厂（模块 6 的管道）
    @Resource
    private OaRagAdvisorFactory oaRagAdvisorFactory;

    // 工具：查工单流转状态（模块 9）
    @Resource
    private QueryOrderStatusTool queryOrderStatusTool;

    // 工具：查审批人信息（模块 9）
    @Resource
    private QueryApproverTool queryApproverTool;

    // 工具：查自己权限（模块 9）
    @Resource
    private QueryMyPermissionTool queryMyPermissionTool;

    // ChatClient 延迟初始化（等工厂和工具注入完成后构建）
    private ChatClient chatClient;

    /**
     * 智能客服对话接口（流式输出）
     * POST /oa/chat（SSE 流式返回，用户 1-2s 看到首字）
     * Header: X-User-Id（工号）、X-Area-Code（地市编码）
     * Body: { "message": "我的工单到哪了", "conversationId": "user123" }
     */
    // 映射 POST /oa/chat，返回 SSE 流
    @PostMapping(value = "/chat", produces = "text/event-stream")
    public Flux<String> chatStream(
            // 从 Header 获取用户工号（模块 0：身份传递，OA 系统登录后注入）
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            // 从 Header 获取地市编码（用于工具内的数据权限过滤）
            @RequestHeader(value = "X-Area-Code", required = false) String areaCode,
            // 从 Body 获取用户消息和对话 ID
            @RequestBody ChatRequest request
    ) {
        // 初始化 ChatClient（如果尚未初始化）
        initChatClient();
        // 打印收到的消息
        log.info("OA 客服收到消息（流式）：userId={}, conversationId={}, message={}", userId, request.conversationId, request.message);

        // 流式调用大模型（逐字输出，用户 1-2s 看到首字）
        return chatClient.prompt()
                // 用户当前消息
                .user(request.message)
                // 传入用户身份信息（模块 0：ToolContext 身份传递）
                .toolContext(Map.of(
                        "userId", userId != null ? userId : "default",
                        "areaCode", areaCode != null ? areaCode : ""
                ))
                // 传入 conversationId 和原始用户消息
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, request.conversationId)
                        .param("ORIGINAL_USER_TEXT", request.message))
                // 流式调用
                .stream()
                // 获取文本内容流
                .content();
    }

    /**
     * 智能客服对话接口（同步输出）
     * POST /oa/chat/sync（等待完整响应后一次性返回）
     * 适用于不需要打字机效果的场景（如 API 对接、自动化测试）
     */
    // 映射 POST /oa/chat/sync，返回 JSON
    @PostMapping("/chat/sync")
    public String chatSync(
            // 从 Header 获取用户工号
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            // 从 Header 获取地市编码
            @RequestHeader(value = "X-Area-Code", required = false) String areaCode,
            // 从 Body 获取用户消息和对话 ID
            @RequestBody ChatRequest request
    ) {
        // 初始化 ChatClient（如果尚未初始化）
        initChatClient();
        // 打印收到的消息
        log.info("OA 客服收到消息（同步）：userId={}, conversationId={}, message={}", userId, request.conversationId, request.message);

        // 同步调用大模型（等待完整响应）
        return chatClient.prompt()
                // 用户当前消息
                .user(request.message)
                // 传入用户身份信息
                .toolContext(Map.of(
                        "userId", userId != null ? userId : "default",
                        "areaCode", areaCode != null ? areaCode : ""
                ))
                // 传入 conversationId 和原始用户消息
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, request.conversationId)
                        .param("ORIGINAL_USER_TEXT", request.message))
                // 同步调用
                .call()
                // 获取文本内容
                .content();
    }

    /**
     * 初始化 ChatClient（懒加载，线程安全由 synchronized 保证）
     * 挂载：系统提示 + 选择性记忆 + RAG 管道 + 查询工具
     */
    private synchronized void initChatClient() {
        // 如果已初始化，直接返回
        if (chatClient != null) {
            return;
        }
        // 从工厂创建 RAG Advisor 管道
        RetrievalAugmentationAdvisor ragAdvisor = oaRagAdvisorFactory.create();
        // 构建 ChatClient：系统提示 + 记忆 Advisor + RAG Advisor + 查询工具
        chatClient = ChatClient.builder(chatModel)
                // 系统提示词：定义 AI 角色 + 区分知识型/查询型 + 兜底策略
                .defaultSystem("你是一个 OA 系统智能客服。" +
                        "基于检索到的知识库内容回答用户的操作类、流程类、概念类问题。" +
                        "当用户询问工单状态、审批人信息或自己的权限时，调用对应的查询工具获取实时数据。" +
                        "如果检索结果中没有相关信息且无法通过工具回答，请礼貌告知用户这不在你的服务范围内。" +
                        "回答要简洁、准确、有条理。" +
                        "涉及他人信息时只显示脱敏结果。" +
                        "请专注于回答用户当前的问题。如果当前问题与之前的对话话题不同，请作为独立的新问题处理，不要被之前的对话内容影响。")
                .defaultAdvisors(
                        // 选择性记忆（embedding 相似度筛选历史，解决跨话题污染）
                        SelectiveMemoryAdvisor.builder(chatMemory, embeddingModel)
                                .similarityThreshold(0.5)
                                .build(),
                        // RAG 检索增强（查询重写+分类→检索→兜底）
                        // ⚠️ 必须用 BaseAdvisorCallAdapter 包装：Spring AI 1.0.3 的
                        // defaultAdvisors 按实际接口分发，RetrievalAugmentationAdvisor
                        // 只实现了 BaseAdvisor，直接传入会被静默忽略（检索永不发生）
                        new com.aismp.aismpagent.rag.BaseAdvisorCallAdapter(
                                ragAdvisor, "RagAugmentation", Integer.MAX_VALUE - 1)
                )
                // 注册三个查询工具（大模型自主决定是否调用）
                .defaultTools(
                        queryOrderStatusTool,
                        queryApproverTool,
                        queryMyPermissionTool
                )
                .build();
        // 打印初始化完成日志
        log.info("OA 智能客服 ChatClient 初始化完成（含 3 个查询工具）");
    }

    /**
     * 对话请求体
     */
    // 静态内部类，用于接收 JSON 请求体
    public static class ChatRequest {
        // 用户消息
        public String message;
        // 对话 ID（用于关联记忆）
        public String conversationId;
    }
}
