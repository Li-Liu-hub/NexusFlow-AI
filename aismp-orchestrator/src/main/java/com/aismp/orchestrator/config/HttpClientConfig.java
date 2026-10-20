package com.aismp.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP 客户端配置。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>显式提供 {@code RestClient.Builder}</b> —— Spring Boot 4 把 RestClient 的自动配置
 *       挪出了 web starter，容器里没有这个 Bean；表现为启动能过、第一个请求 500
 *       （UnsatisfiedDependencyException: No qualifying bean of type RestClient$Builder）。</li>
 *   <li><b>换掉默认的请求工厂</b> —— RestClient 默认走 {@code SimpleClientHttpRequestFactory}
 *       （底层 HttpURLConnection），每个请求一条连接，对 https 的 LLM 端点就是每次重新 TLS 握手。
 *       这里改用 JDK HttpClient：自带连接池与 keep-alive，一次握手后复用连接；
 *       同时把连接超时设成 5s、读超时设成 180s —— 后者按内核 ReAct 的最长耗时给足，
 *       避免内核卡住时把 Tomcat 线程（只有 20 个）一直占着。</li>
 * </ol>
 */
@Configuration
public class HttpClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(180);

    @Bean
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);

        return RestClient.builder().requestFactory(factory);
    }
}
