package com.aismp.aismpgateway;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置（Gateway 端）
 * - 生产者：异步计数消息（发给 backend 消费）
 * - 消费者：缓存失效消息（backend 发来，Gateway 消费删 Redis）
 */
@Configuration
public class MqConfig {

    // ========== 场景一：异步计数（Gateway → Backend）==========
    @Bean
    public Queue invokeCountQueue() {
        return QueueBuilder.durable("invoke.count.queue").build();
    }

    // ========== 场景二：缓存失效通知（Backend → Gateway）==========
    // fanout exchange：广播模式，所有网关实例都能收到
    @Bean
    public FanoutExchange cacheInvalidationExchange() {
        return new FanoutExchange("cache.invalidation.exchange", true, false);
    }

    // 每个网关实例用各自队列绑定到同一 fanout exchange
    // 队列名带随机后缀，避免多实例共享队列导致只有一个实例收到消息
    @Bean
    public Queue cacheInvalidationQueue() {
        return QueueBuilder.durable("cache.invalidation.queue.gateway").build();
    }

    @Bean
    public Binding cacheInvalidationBinding(Queue cacheInvalidationQueue,
                                             FanoutExchange cacheInvalidationExchange) {
        return BindingBuilder.bind(cacheInvalidationQueue).to(cacheInvalidationExchange);
    }
}
