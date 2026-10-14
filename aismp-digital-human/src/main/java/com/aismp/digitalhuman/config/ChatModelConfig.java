package com.aismp.digitalhuman.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * ChatModel 装配：用 {@link UsageRecordingChatModel} 包装自动配置的 OpenAiChatModel，
 * 并标记 @Primary —— 之后所有按 ChatModel 接口注入的地方（AgentAssembler / RouterService）
 * 拿到的都是带用量审计的包装层，这些类对此无感知。
 */
@Configuration
public class ChatModelConfig {

    @Bean
    @Primary
    public ChatModel usageRecordingChatModel(OpenAiChatModel delegate) {
        return new UsageRecordingChatModel(delegate);
    }
}
