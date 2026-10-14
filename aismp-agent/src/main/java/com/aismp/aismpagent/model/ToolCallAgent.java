package com.aismp.aismpagent.model;

// hutool 集合工具，CollUtil.getLast() 取列表最后一个元素
import cn.hutool.core.collection.CollUtil;
// Lombok 注解
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
// Spring AI 助手消息，代表大模型的回复（可能包含 tool_calls）
import org.springframework.ai.chat.messages.AssistantMessage;
// Spring AI 用户消息
import org.springframework.ai.chat.messages.UserMessage;
// Spring AI 聊天响应，包含大模型的完整返回
import org.springframework.ai.chat.model.ChatResponse;
// Spring AI 聊天选项接口
import org.springframework.ai.chat.prompt.ChatOptions;
// Spring AI 提示词对象，封装消息列表 + 选项
import org.springframework.ai.chat.prompt.Prompt;
// Spring AI 工具调用聊天选项（可设置工具定义 + 是否自动执行）
import org.springframework.ai.model.tool.ToolCallingChatOptions;
// Spring AI 工具调用管理者，用于手动执行工具
import org.springframework.ai.model.tool.ToolCallingManager;
// Spring AI 工具执行结果，包含更新后的消息列表
import org.springframework.ai.model.tool.ToolExecutionResult;
// Spring AI 工具回调接口，描述一个可被 AI 调用的工具
import org.springframework.ai.tool.ToolCallback;
// Spring AI 消息接口
import org.springframework.ai.chat.messages.Message;
// Spring AI 工具响应消息，代表工具执行后的返回结果
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 *
 * think()：调大模型，传入工具定义，大模型决定是否使用工具
 * act()：通过 ToolCallingManager 手动执行大模型选择的工具
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    // 可用的工具数组（writeFile、readFile、doTerminate）
    private final ToolCallback[] availableTools;

    // 保存了工具调用信息的大模型响应（think() 中赋值，act() 中使用）
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者，用于手动执行工具（Spring AI 提供）
    private final ToolCallingManager toolCallingManager;

    // 聊天选项：包含工具定义 + 禁用自动执行（我们自己控制执行时机）
    // 非 final：运行时可通过 updateToolContext() 重建（加入 userId/areaCode）
    private ChatOptions chatOptions;

    /**
     * 构造函数
     *
     * @param availableTools 工具数组，由 YuManus 通过 ToolCallbacks.from() 创建后传入
     */
    public ToolCallAgent(ToolCallback[] availableTools) {
        // 调用父类构造
        super();
        // 保存工具列表
        this.availableTools = availableTools;
        // 创建工具调用管理者
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 构建聊天选项（核心配置）
        this.chatOptions = ToolCallingChatOptions.builder()
                // 把工具注册到选项中（★ 必须放这里，大模型才能看到工具定义）
                .toolCallbacks(availableTools)
                // 禁用 Spring AI 自动执行工具，由我们在 act() 中手动执行
                .internalToolExecutionEnabled(false)
                .build();
    }

    /**
     * 更新工具上下文（userId、areaCode 等身份信息）
     *
     * 重建 chatOptions，把 toolContext 加入其中。
     * ToolCallingManager.executeToolCalls() 会从 Prompt 的 chatOptions 中读取 toolContext，
     * 传给工具的 ToolContext 参数。
     *
     * @param toolContext 工具上下文 Map（如 {userId: "123", areaCode: "WH"}）
     */
    // 更新工具上下文
    public void updateToolContext(Map<String, Object> toolContext) {
        // 重建 chatOptions，加入 toolContext
        this.chatOptions = ToolCallingChatOptions.builder()
                // 保留原有工具回调
                .toolCallbacks(availableTools)
                // 保留禁用自动执行
                .internalToolExecutionEnabled(false)
                // 注入工具上下文（userId/areaCode）
                .toolContext(toolContext)
                .build();
    }

    /**
     * 思考阶段：调大模型，决定是否使用工具
     *
     * @return true = 要调用工具，false = 不需要
     */
    @Override
    public boolean think() {
        // 检查是否设置了 nextStepPrompt（每一步都提醒大模型主动选工具）
        if (getNextStepPrompt() != null && !getNextStepPrompt().isEmpty()) {
            // 将 nextStepPrompt 包装成 UserMessage
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            // 加入消息上下文列表
            getMessageList().add(userMessage);
        }
        // 获取当前所有消息上下文
        List<Message> messageList = getMessageList();
        // 构建 Prompt（消息列表 + 工具定义 + 禁用自动执行）
        Prompt prompt = new Prompt(messageList, chatOptions);
        // 开始 try 块
        try {
            // ★★★ 调用大模型
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    // 设置系统提示词
                    .system(getSystemPrompt())
                    // 同步调用
                    .call()
                    // 获取完整响应
                    .chatResponse();
            // 保存响应到实例属性，供 act() 使用
            this.toolCallChatResponse = chatResponse;
            // 提取大模型的回复消息
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 提取文字回复内容
            String result = assistantMessage.getText();
            // 提取工具调用列表（空 = 大模型没选工具，非空 = 大模型要调工具）
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            // 日志：打印思考内容
            log.info(getName() + "的思考: " + result);
            // 日志：打印选择了几个工具
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            // 构建工具信息日志
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s",
                            toolCall.name(),
                            toolCall.arguments())
                    )
                    .collect(Collectors.joining("\n"));
            // 日志：打印每个工具的名称和参数
            log.info(toolCallInfo);
            // 保存大模型的文字回答（无论是否选工具，都可能在文字回答的同时调用工具）
            setLastThinkText(result);
            // 判断大模型是否选择了工具
            if (toolCallList.isEmpty()) {
                // 空列表 = 没选工具，把文字回复加入消息上下文
                getMessageList().add(assistantMessage);
                // 返回 false，不执行 act()
                return false;
            } else {
                // 非空 = 要调工具，不手动添加消息（act() 中 ToolCallingManager 会自动加）
                return true;
            }
        // 捕获异常
        } catch (Exception e) {
            // 记录错误日志
            log.error(getName() + "的思考过程遇到了问题: " + e.getMessage());
            // 将错误信息加入消息列表
            getMessageList().add(
                    new AssistantMessage("处理时遇到错误: " + e.getMessage()));
            // 返回 false，不执行 act()
            return false;
        }
    }

    /**
     * 行动阶段：执行 think() 中大模型选择的工具
     *
     * @return 执行结果
     */
    @Override
    public String act() {
        // 检查响应是否包含工具调用
        if (!toolCallChatResponse.hasToolCalls()) {
            // 没有工具调用，直接返回
            return "没有工具调用";
        }
        // 构建 Prompt（消息列表 + 工具定义）
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        // ★★★ 手动执行工具：ToolCallingManager 内部会匹配工具、反射调用 Java 方法、封装结果
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        // 用执行后的完整消息列表替换当前 messageList（已含助手消息 + 工具结果）
        setMessageList(toolExecutionResult.conversationHistory());
        // 取出最后一条消息（一定是工具执行结果）
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        // 构建工具执行结果字符串
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 完成了它的任务！结果: " + response.responseData())
                .collect(Collectors.joining("\n"));
        // 检查是否调用了终止工具 doTerminate
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));
        // 如果调用了终止工具
        if (terminateToolCalled) {
            // 状态改为 FINISHED → Agent Loop 下一轮退出
            setState(AgentState.FINISHED);
        }
        // 日志：打印工具执行结果
        log.info(results);
        // 返回结果
        return results;
    }
}
