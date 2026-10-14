package com.aismp.digitalhuman;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数字员工内核入口。
 *
 * <p>本模块与 aismp-agent 完全隔离：aismp-agent 走 Spring Boot 3.5 + Spring AI 1.0.3，
 * 本模块走 Spring Boot 4 + Spring AI 2.0 + SAA。两者通过编排层（agent-orchestrator）的
 * 不同节点分别接入，互不依赖。
 */
@SpringBootApplication
public class DigitalHumanApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalHumanApplication.class, args);
    }
}
