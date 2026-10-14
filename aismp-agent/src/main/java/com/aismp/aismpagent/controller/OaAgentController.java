package com.aismp.aismpagent.controller;

// OA 客服 Agent
import com.aismp.aismpagent.model.OaAgent;
// Agent 状态枚举
import com.aismp.aismpagent.model.AgentState;
// Jakarta 资源注入注解
import jakarta.annotation.Resource;
// Lombok 日志注解
import lombok.extern.slf4j.Slf4j;
// Spring Web REST 控制器注解
import org.springframework.web.bind.annotation.*;
// Spring Web SSE 发射器
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// Java Map
import java.util.Map;

/**
 * OA 客服智能体接口（ReAct 模式 + Always-on RAG + 多轮记忆）
 *
 * 与 OaChatController 的区别：
 * - OaChatController（模块 7）：ChatClient + Advisor 链，单次请求-响应
 * - OaAgentController（模块 8）：Agent ReAct 循环，多步骤自主决策
 *
 * 身份传递：
 * - X-User-Id：用户工号，用于查询工具的行级过滤
 * - X-Area-Code：地市编码，用于审批人查询的地市过滤
 *
 * 多轮记忆：
 * - conversationId：会话 ID，用于关联多轮对话历史（PG 持久化）
 */
// 注册为 REST 控制器
@RestController
// 基础路径 /oa/agent
@RequestMapping("/oa/agent")
// 启用日志
@Slf4j
public class OaAgentController {

    // OA 客服 Agent（Spring 自动注入）
    @Resource
    private OaAgent oaAgent;

    /**
     * 智能体对话接口（同步，返回完整执行过程）
     * POST /oa/agent/chat
     * Header: X-User-Id（工号）、X-Area-Code（地市编码）
     * Body: { "message": "忘记密码怎么办", "conversationId": "user123" }
     */
    // 映射 POST /oa/agent/chat
    @PostMapping("/chat")
    public String chat(
            // 从 Header 获取用户工号
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            // 从 Header 获取地市编码
            @RequestHeader(value = "X-Area-Code", required = false) String areaCode,
            // 从 Body 获取用户消息和会话 ID
            @RequestBody AgentRequest request
    ) {
        // 打印收到的消息
        log.info("OA Agent 收到消息：userId={}, conversationId={}, message={}",
                userId, request.conversationId, request.message);
        // 每次调用前重置 Agent 状态
        oaAgent.setState(AgentState.IDLE);
        // 清空上一轮的消息上下文（Agent 内部的 run() 会先加载历史记忆）
        oaAgent.getMessageList().clear();
        // 重置步数
        oaAgent.setCurrentStep(0);
        // 注入用户身份信息到工具上下文
        oaAgent.updateToolContext(Map.of(
                "userId", userId != null ? userId : "default",
                "areaCode", areaCode != null ? areaCode : ""
        ));
        // 设置会话 ID（用于多轮记忆的加载和保存）
        oaAgent.setCurrentConversationId(request.conversationId);
        // 启动 Agent 执行循环
        return oaAgent.run(request.message);
    }

    /**
     * 智能体对话接口（流式输出，SSE 逐步推送执行过程）
     * GET /oa/agent/chat/sse?message=忘记密码怎么办&conversationId=user123
     * Header: X-User-Id（工号）、X-Area-Code（地市编码）
     */
    // 映射 GET /oa/agent/chat/sse，返回 SSE 流
    @GetMapping(value = "/chat/sse")
    public SseEmitter chatStream(
            // 从 Header 获取用户工号
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            // 从 Header 获取地市编码
            @RequestHeader(value = "X-Area-Code", required = false) String areaCode,
            // 从查询参数获取用户消息
            @RequestParam String message,
            // 从查询参数获取会话 ID
            @RequestParam(required = false) String conversationId
    ) {
        // 打印收到的消息
        log.info("OA Agent 收到消息（流式）：userId={}, conversationId={}, message={}",
                userId, conversationId, message);
        // 每次调用前重置 Agent 状态
        oaAgent.setState(AgentState.IDLE);
        // 清空上一轮的消息上下文
        oaAgent.getMessageList().clear();
        // 重置步数
        oaAgent.setCurrentStep(0);
        // 注入用户身份信息
        oaAgent.updateToolContext(Map.of(
                "userId", userId != null ? userId : "default",
                "areaCode", areaCode != null ? areaCode : ""
        ));
        // 设置会话 ID
        oaAgent.setCurrentConversationId(conversationId);
        // 启动 Agent 流式执行
        return oaAgent.runStream(message);
    }

    /**
     * 请求体
     */
    // 静态内部类
    public static class AgentRequest {
        // 用户消息
        public String message;
        // 会话 ID（用于多轮记忆，同一 ID 的消息共享对话历史）
        public String conversationId;
    }
}
