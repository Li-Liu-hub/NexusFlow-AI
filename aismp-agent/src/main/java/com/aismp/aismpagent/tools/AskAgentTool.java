package com.aismp.aismpagent.tools;

// OA 客服 Agent（ReAct 智能体本体）
import com.aismp.aismpagent.model.OaAgent;
// Agent 状态枚举
import com.aismp.aismpagent.model.AgentState;
// Spring AI 工具注解，声明可被 AI 调用的工具
import org.springframework.ai.tool.annotation.Tool;
// Spring AI 工具注解，描述工具参数
import org.springframework.ai.tool.annotation.ToolParam;
// Spring 组件注解
import org.springframework.stereotype.Component;
// Jakarta 资源注入注解
import jakarta.annotation.Resource;
// Spring ObjectProvider：延迟解析 Bean，打破装配期循环依赖
import org.springframework.beans.factory.ObjectProvider;
// Lombok 日志注解
import lombok.extern.slf4j.Slf4j;
// Java UUID，用于生成独立会话 ID
import java.util.UUID;
// Java Map，用于工具上下文注入
import java.util.Map;

/**
 * 智能体即工具（Agent-as-a-Tool）
 *
 * 把整个 OA 客服智能体（RAG 检索 + ReAct 多步推理 + 工具调用）封装成一个 MCP 工具，
 * 让外部 AI 客户端（Cursor、Claude Desktop 等）把任务整体委托给智能体：
 * 客户端只发一句话，智能体内部自主完成"检索→思考→行动→终止"全循环并返回最终答案。
 *
 * 与其他 4 个工具的区别：
 * - queryOrderStatus 等：单一能力，客户端的 LLM 决定怎么用
 * - askAgent：整套智能体能力，客户端把"怎么解题"也一并委托出去
 *
 * 会话隔离：每次调用生成独立 conversationId（mcp-{uuid}），MCP 调用不污染
 * 真实用户的多轮记忆，也不继承历史——无状态委托，一次调用一次完整执行。
 *
 * 已知限制：OaAgent 是单例有状态 Bean，MCP 调用与 HTTP 调用并发时会竞争
 * 内部状态（与 OaAgentController 同源限制）；解耦需把智能体独立部署（M8 预留）。
 */
// 注册为 Spring Bean
@Component
// 启用日志
@Slf4j
public class AskAgentTool {

    // OA 客服 Agent——用 ObjectProvider 延迟解析，不能用直接注入：
    // 装配期存在环 oaAgent → chatModel → toolCallbackResolver → oaToolCallbackProvider
    // → askAgentTool → oaAgent（工具包智能体，智能体的依赖图又收编了这个工具），
    // ObjectProvider 把解析推迟到首次调用，装配期环就断了
    @Resource
    private ObjectProvider<OaAgent> oaAgentProvider;

    /**
     * 委托 OA 客服智能体回答问题（完整 ReAct 循环）
     *
     * @param message 用户问题，例如：电费稽核的流程是什么、忘记密码怎么办
     * @return 智能体最终答案（已由内部终止工具收尾）
     */
    @Tool(description = "委托 OA 客服智能体完整回答问题。智能体内部会自主检索知识库、" +
            "查询工单/审批人/权限等多步推理后给出最终答案。适合把 OA 领域问题整体委托处理，" +
            "例如：电费稽核流程、忘记密码怎么办、报表导不出来等。")
    public String askAgent(
            // 工具参数：要委托给智能体的完整问题
            @ToolParam(description = "要委托给 OA 客服智能体的完整问题") String message
    ) {
        // MCP 委托统一用独立身份，不带真实用户工号
        String mcpUserId = "mcp-client";
        // 每次调用独立会话：不复用历史、不污染真实用户记忆
        String conversationId = "mcp-" + UUID.randomUUID().toString().substring(0, 8);
        // 首次调用时才解析 Agent Bean（破装配期循环依赖，见字段注释）
        OaAgent oaAgent = oaAgentProvider.getObject();
        log.info("Agent-as-Tool 收到委托：conversationId={}, message={}", conversationId, message);
        // 与 OaAgentController 相同的状态复位流程：每次调用前重置 Agent
        oaAgent.setState(AgentState.IDLE);
        oaAgent.getMessageList().clear();
        oaAgent.setCurrentStep(0);
        // 注入工具上下文（行级过滤身份），MCP 委托无地市信息
        oaAgent.updateToolContext(Map.of(
                "userId", mcpUserId,
                "areaCode", ""
        ));
        // 独立会话 ID：本次执行的记忆只属于这次委托
        oaAgent.setCurrentConversationId(conversationId);
        // 同步执行完整 ReAct 循环，返回最终答案
        String answer = oaAgent.run(message);
        log.info("Agent-as-Tool 委托完成：conversationId={}, 答案长度={}", conversationId, answer.length());
        return answer;
    }
}
