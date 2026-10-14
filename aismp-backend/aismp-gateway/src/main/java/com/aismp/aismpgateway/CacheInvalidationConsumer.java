package com.aismp.aismpgateway;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;

/**
 * 缓存失效消费者（Gateway 端）
 * 消费 Backend 发来的缓存失效消息，删除对应的 Redis 缓存
 */
@Slf4j
@Component
public class CacheInvalidationConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = "cache.invalidation.queue.gateway")
    public void handleCacheInvalidation(@Payload String cacheKey,
                                         Channel channel,
                                         @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            Boolean deleted = stringRedisTemplate.delete(cacheKey);
            log.info("缓存失效处理完成：{} (deleted={})", cacheKey, deleted);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("缓存失效处理失败：{}", cacheKey, e);
            channel.basicNack(tag, false, false);
        }
    }
}
