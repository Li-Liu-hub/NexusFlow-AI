package com.aismp.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP 客户端配置。
 *
 * <p>为什么自己声明 {@code RestClient.Builder}：Spring Boot 4 把 RestClient 的自动配置
 * 挪出了 web starter，容器里没有这个 Bean —— 表现为启动能过、第一个请求 500
 * （UnsatisfiedDependencyException: No qualifying bean of type RestClient$Builder）。
 * 与其猜它落在哪个新 starter 里，不如显式给一个：本服务只有两处出网（LLM 与内核），
 * 都不需要复杂配置。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
