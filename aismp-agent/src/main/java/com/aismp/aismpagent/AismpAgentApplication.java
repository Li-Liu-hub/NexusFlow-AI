package com.aismp.aismpagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * aismp-agent 启动类。
 *
 * <p>2026-10-07 起本服务<b>不再自带定时任务</b>：库存告警巡检的定时节奏上移到编排层
 * （agent-orchestrator 的 alert 图），本服务只提供原子能力（scan / notice）。
 * 故 {@code @EnableScheduling} 已移除。
 */
@SpringBootApplication
public class AismpAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AismpAgentApplication.class, args);
    }

}
