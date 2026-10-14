package com.aismp.aismpgateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 服务间调用用的 RestTemplate。
 *
 * <p>加 {@code @LoadBalanced} 后，URL 里的主机名会被当作<b>服务名</b>交给
 * Spring Cloud LoadBalancer 解析 —— 于是可以写
 * {@code http://aismp-backend/inner/user/byAccessKey}，
 * 由 Nacos 服务发现拿到真实实例地址（backend 注册的 127.0.0.1:7529）。
 *
 * <p>这是本次「去 Dubbo」改造的落点：原来 {@code @DubboReference} 直接注入远程接口代理，
 * 现在换成显式 HTTP 调用，协议更透明、依赖更少，且与全链路一致。
 *
 * <p><b>bean 名必须是 loadBalancedRestTemplate</b>：{@code CustomGlobalFilter}
 * 用 {@code @Resource(name=...)} 按名注入，以此和项目里其他普通 RestTemplate 区分开
 * （未标 @LoadBalanced 的 RestTemplate 不能解析服务名，混用会报
 * "Unable to find instance for aismp-backend"）。
 */
@Configuration
public class LoadBalancedClientConfig {

    @Bean("loadBalancedRestTemplate")
    @LoadBalanced
    public RestTemplate loadBalancedRestTemplate() {
        // ⚠️ 超时是「Dubbo → HTTP」改造最容易漏的一步：
        // Dubbo 默认 1000ms 超时，而 RestTemplate 默认 connect/read 都是 -1（无限等）。
        // 后果不是卡事件循环（鉴权逻辑已挪到 boundedElastic），而是**耗尽弹性线程池** ——
        // backend 一卡，所有弹性线程阻塞在 HTTP 上，网关整体丧失鉴权能力。
        // 取值与 Dubbo 原超时同量级：连接 2s / 读取 3s。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(factory);
    }
}
