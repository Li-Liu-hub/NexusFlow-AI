package com.aismp.project.mq;

import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置（Backend 端）
 * - 消费者：异步计数消息（Gateway 发来）
 * - 生产者：缓存失效通知（发给所有 Gateway 实例消费）
 */
@Configuration
public class MqConfig {

    // 场景一：异步计数队列（Gateway → Backend）
    @Bean
    public Queue invokeCountQueue() {
        return QueueBuilder.durable("invoke.count.queue").build();
    }

    // 场景二：缓存失效通知 fanout exchange（Backend → Gateway 广播）
    @Bean
    public FanoutExchange cacheInvalidationExchange() {
        return new FanoutExchange("cache.invalidation.exchange", true, false);
    }

    // Backend 端也声明 gateway 消费队列，确保 queue 和 exchange 的 binding 在 broker 端存在
    @Bean
    public Queue cacheInvalidationQueue() {
        return QueueBuilder.durable("cache.invalidation.queue.gateway").build();
    }

    @Bean
    public org.springframework.amqp.core.Binding cacheInvalidationBinding(
            Queue cacheInvalidationQueue,
            FanoutExchange cacheInvalidationExchange) {
        return org.springframework.amqp.core.BindingBuilder
                .bind(cacheInvalidationQueue)
                .to(cacheInvalidationExchange);
    }
}
