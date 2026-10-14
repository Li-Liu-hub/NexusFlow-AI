package com.aismp.digitalhuman.config;

import com.aismp.digitalhuman.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器注册：整个内核除了健康检查与登录接口，全部要求登录。
 *
 * <ul>
 *   <li>/admin/** → 仅 ADMIN（管理台配置面）</li>
 *   <li>/agent/** → 登录即可（门户运行面；编排层用 SERVICE 账号）</li>
 *   <li>/health、/auth/login → 匿名</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/health", "/health/**", "/auth/login", "/error");
    }
}
