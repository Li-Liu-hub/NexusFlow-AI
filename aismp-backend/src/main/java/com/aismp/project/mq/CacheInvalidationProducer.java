package com.aismp.project.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 缓存失效消息生产者（Backend 端）
 * 数据变更（封禁用户、下线接口）后，通知所有网关实例删除对应的 Redis 缓存
 */
@Slf4j
@Component
public class CacheInvalidationProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送用户缓存失效通知
     */
    public void sendUserCacheInvalidation(String accessKey) {
        String cacheKey = "user:ak:" + accessKey;
        send(cacheKey);
    }

    /**
     * 发送接口缓存失效通知
     */
    public void sendInterfaceCacheInvalidation(String path, String method) {
        String cacheKey = "interface:" + path + ":" + method;
        send(cacheKey);
    }

    private void send(String cacheKey) {
        try {
            rabbitTemplate.convertAndSend("cache.invalidation.exchange", "", cacheKey);
            log.info("发送缓存失效通知：{}", cacheKey);
        } catch (Exception e) {
            log.error("发送缓存失效通知失败：{}", cacheKey, e);
        }
    }
}
