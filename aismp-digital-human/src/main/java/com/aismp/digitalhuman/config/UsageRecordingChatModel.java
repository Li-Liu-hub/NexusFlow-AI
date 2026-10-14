package com.aismp.digitalhuman.config;

import com.aismp.digitalhuman.tool.ModelUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * ChatModel 装饰器：每一跳模型调用的用量审计。
 *
 * <p><b>它解决什么问题</b>：M4 的 token 计量缺口。ReactAgent 在内部自己驱动
 * 多轮「模型调用 → 工具回执 → 再调模型」，用量只存在于每一跳的
 * ChatResponse.metadata.usage 里，没有任何出口交给业务层。
 * 把 ChatModel 包一层，用量就被截下来了。
 *
 * <p><b>为什么做成 @Primary 装饰而非改 AgentAssembler</b>：装配器依赖
 * {@code ChatModel} 接口，包装后注入的仍是同一接口 —— 装配器、路由器
 * 一行都不用改，这是「装饰器模式」的正经收益。
 *
 * <p><b>失败语义</b>：用量提取是旁路，任何异常都吞掉并原样返回下游响应，
 * 绝不因为计量失败让对话失败。
 */
public class UsageRecordingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordingChatModel.class);

    private final ChatModel delegate;

    public UsageRecordingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        record(response);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt).doOnNext(this::record);
    }

    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private void record(ChatResponse response) {
        try {
            if (response == null || response.getMetadata() == null) {
                return;
            }
            ModelUsageContext.accumulate(response.getMetadata().getUsage());
        } catch (Exception e) {
            log.debug("模型用量提取失败（忽略）: {}", e.getMessage());
        }
    }
}
