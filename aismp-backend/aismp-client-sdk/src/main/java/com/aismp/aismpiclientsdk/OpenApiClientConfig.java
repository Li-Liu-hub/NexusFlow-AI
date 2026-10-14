package com.aismp.aismpiclientsdk;

import com.aismp.aismpiclientsdk.client.OpenApiClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 开放平台客户端自动装配。
 *
 * <p>读取配置前缀 {@code aismp.client} 下的 accessKey / secretKey
 * （与 {@link OpenApiClient} 的构造参数一一对应），装配一个可直接注入的客户端 Bean。
 */
@Configuration
@ConfigurationProperties("aismp.client")
@Data
@ComponentScan
public class OpenApiClientConfig {

    private String accessKey;

    private String secretKey;

    @Bean
    public OpenApiClient openApiClient() {
        return new OpenApiClient(accessKey, secretKey);
    }
}
