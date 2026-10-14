package com.aismp.digitalhuman.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 装配结果：一个可以直接对话的数字员工。
 *
 * <p>它是「配置记录」在内存中的投影 —— 携带 version 便于判断是否需要重建，
 * 携带 skillNames / toolNames 便于运维界面和排障时确认「到底装进去了什么」。
 */
public record AssembledAgent(
        String agentCode,
        int version,
        ReactAgent agent,
        String systemPrompt,
        String modelName,
        List<String> skillNames,
        List<String> toolNames,
        long assembledAt
) {

    /**
     * 同步对话（无历史）：返回完整回答文本。
     *
     * <p>保留给编排层等只要最终结果的调用方；前端走 {@link #streamChat}。
     */
    public String chat(String message) {
        return chat(message, List.of());
    }

    /** 同步对话（带历史）。 */
    public String chat(String message, List<ChatTurn> history) {
        try {
            List<Message> messages = buildMessageList(message, history);
            return agent.call(messages).getText();
        } catch (GraphRunnerException e) {
            throw new IllegalStateException("数字员工执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式对话：逐段产出内容，通过回调推给调用方。
     *
     * <p>回调参数：{@code phase} 取值 ——
     * <ul>
     *   <li>{@code delta}：回答文本片段（AssistantMessage，逐段追加显示）</li>
     *   <li>{@code tool}：工具返回摘要（ToolResponseMessage，可展示「刚查了什么」）</li>
     * </ul>
     *
     * <p>受检异常在此收敛为 RuntimeException，让调用方统一处理。
     */
    public void streamChat(String message, List<ChatTurn> history, BiConsumer<String, String> onEvent) {
        try {
            List<Message> messages = buildMessageList(message, history);
            agent.streamMessages(messages).doOnNext(msg -> {
                if (msg instanceof AssistantMessage am && am.getText() != null && !am.getText().isEmpty()) {
                    onEvent.accept("delta", am.getText());
                } else if (msg instanceof ToolResponseMessage trm && !trm.getResponses().isEmpty()) {
                    // 工具回执：让前端能看到「数字员工刚查了什么」（取第一个工具结果摘要）
                    var r = trm.getResponses().get(0);
                    onEvent.accept("tool", r.name() + " → " + r.responseData());
                }
            }).blockLast();
        } catch (GraphRunnerException e) {
            throw new IllegalStateException("数字员工执行失败: " + e.getMessage(), e);
        }
    }

    /** 历史轮次（与 ConversationRepository.ChatTurn 同构，避免服务层反向依赖仓储层）。 */
    public record ChatTurn(String role, String content) {
    }

    /** 历史 + 本轮提问 → 按时间顺序的消息列表（同步/流式共用）。 */
    private List<Message> buildMessageList(String message, List<ChatTurn> history) {
        List<Message> messages = new ArrayList<>(history.size() + 1);
        for (ChatTurn turn : history) {
            if (turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            messages.add("assistant".equalsIgnoreCase(turn.role())
                    ? new AssistantMessage(turn.content())
                    : new UserMessage(turn.content()));
        }
        messages.add(new UserMessage(message));
        return messages;
    }
}
