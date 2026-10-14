package com.aismp.aismpagent.config;

// Spring Web MVC 配置器
import org.springframework.context.annotation.Configuration;
// Spring Web MVC 配置器接口
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
// Spring Web CORS 注册器
import org.springframework.web.servlet.config.annotation.CorsRegistry;

/**
 * 全局跨域配置
 *
 * 允许前端项目（如 Vue/React 开发服务器、chat.html）跨域访问后端接口。
 * 解决浏览器同源策略限制导致的 CORS 错误。
 */
// 注册为 Spring 配置类
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 配置跨域映射
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 覆盖所有请求
        registry.addMapping("/**")
                // 允许发送 Cookie
                .allowCredentials(true)
                // 放行哪些域名（必须用 patterns，否则 * 会和 allowCredentials 冲突）
                .allowedOriginPatterns("*")
                // 允许的 HTTP 方法
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // 允许的请求头
                .allowedHeaders("*")
                // 暴露的响应头
                .exposedHeaders("*");
    }
}
