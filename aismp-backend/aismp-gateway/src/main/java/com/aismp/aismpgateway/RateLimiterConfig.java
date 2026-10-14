package com.aismp.aismpgateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * 限流配置
 * 按 accessKey 维度做令牌桶限流，底层是 Redis + Lua 脚本
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> {
            // 限流维度统一收口到 ak：
            //   MCP 通道（/sse,/mcp/**）→ 鉴权过滤器从 token 映射出的 ak（存于 exchange 属性）
            //   REST 通道 → accessKey 请求头
            // 兜底固定 key，防止 Mono.just(null) NPE（Bearer 请求没有 accessKey 头时曾 500）
            String ak = exchange.getAttribute("rateLimitAk");
            if (ak == null || ak.isEmpty()) {
                ak = exchange.getRequest().getHeaders().getFirst("accessKey");
            }
            return Mono.just(ak != null ? ak : "anonymous");
        };
    }
}
