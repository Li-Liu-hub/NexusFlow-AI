package com.aismp.project.mq;

import com.rabbitmq.client.Channel;
import com.aismp.project.service.UserInterfaceInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.Map;

/**
 * 异步计数消费者（Backend 端）
 * 消费 Gateway 发来的计数消息，异步调用 invokeCount
 */
@Slf4j
@Component
public class InvokeCountConsumer {

    @Resource
    private UserInterfaceInfoService userInterfaceInfoService;

    @RabbitListener(queues = "invoke.count.queue")
    public void handleInvokeCount(@Payload Map<String, Object> msg,
                                   Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            Long interfaceInfoId = ((Number) msg.get("interfaceInfoId")).longValue();
            Long userId = ((Number) msg.get("userId")).longValue();
            boolean success = userInterfaceInfoService.invokeCount(interfaceInfoId, userId);
            if (success) {
                log.info("异步计数成功：interfaceInfoId={}, userId={}", interfaceInfoId, userId);
            } else {
                log.warn("异步计数失败（可能次数已用尽）：interfaceInfoId={}, userId={}", interfaceInfoId, userId);
            }
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("异步计数消费失败", e);
            // nack 不重新入队，Spring Retry 已在前面重试 3 次
            channel.basicNack(tag, false, false);
        }
    }
}
