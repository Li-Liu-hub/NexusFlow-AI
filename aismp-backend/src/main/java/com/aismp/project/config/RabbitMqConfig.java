package com.aismp.project.config;

import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * RabbitMQ 消息转换器配置（2026-10-10 修）。
 *
 * <h3>问题现象</h3>
 * 后端 {@code InvokeCountConsumer} 每次消费 {@code invoke.count.queue} 都失败：
 * <pre>
 * ListenerExecutionFailedException: Failed to convert message
 * Caused by: java.lang.SecurityException:
 *   Attempt to deserialize unauthorized class java.util.HashMap;
 *   add allowed class name patterns to the message converter ...
 *   at org.springframework.amqp.utils.SerializationUtils.checkAllowedList(SerializationUtils.java:166)
 * </pre>
 * 原生日志里累计命中 <b>38,658 次</b>（属长期遗留，非容器化引入）。
 *
 * <h3>根因</h3>
 * 链路是：网关 {@code CustomGlobalFilter} →
 * <pre>
 * Map&lt;String,Object&gt; msg = new HashMap&lt;&gt;();
 * msg.put("interfaceInfoId", ...); msg.put("userId", ...);
 * rabbitTemplate.convertAndSend("invoke.count.queue", msg);
 * </pre>
 * 两侧都没有显式配置 MessageConverter，Spring Boot 用默认的
 * {@link SimpleMessageConverter}。它把 {@code Map} 走 <b>Java 原生序列化</b>
 * （content-type = application/x-java-serialized-object）。
 * 而 <b>Spring AMQP 4.0 给 Java 反序列化加了默认白名单</b>
 * （{@code SerializationUtils.checkAllowedList}），{@code java.util.*} 不在其中 →
 * 消费端反序列化直接被拒。
 *
 * <h3>影响</h3>
 * 接口调用计数无法经 MQ 异步落库。网关侧虽有「MQ 失败降级为同步 HTTP」的兜底
 * （见 {@code CustomGlobalFilter} 的 catch 分支），但那条兜底只覆盖「发送失败」，
 * <b>本问题发生在消费端</b>，所以计数实际上一直在丢。
 *
 * <h3>修法</h3>
 * 显式声明 {@link SimpleMessageConverter} 并放开白名单。
 * <b>刻意保持 Java 序列化不变</b> —— 生产端（网关）不动，只放开消费端校验，
 * 属于最小改动、不动协议。Spring Boot 会把唯一的 {@code MessageConverter} Bean
 * 同时应用到 {@code RabbitTemplate} 与监听容器工厂，所以一个 Bean 即可。
 *
 * <p>长期建议：改用 {@code Jackson2JsonMessageConverter}（可读、跨语言、无
 * Java 反序列化安全面），但<b>必须生产端与消费端同时切换</b>，只换一端会更糟。
 */
@Configuration
public class RabbitMqConfig {

    @Bean
    public SimpleMessageConverter simpleMessageConverter() {
        SimpleMessageConverter converter = new SimpleMessageConverter();
        converter.setAllowedListPatterns(List.of(
                // invoke.count.queue 的 payload 是 HashMap{interfaceInfoId,userId}
                "java.util.*",
                "java.lang.*",
                // 业务对象（若后续把计数消息换成 POJO，这里已放开）
                "com.aismp.*"
        ));
        return converter;
    }
}
