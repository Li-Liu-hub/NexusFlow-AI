package com.aismp.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 编排层入口。
 *
 * <p>职责与原 Python 版（agent-orchestrator，FastAPI + LangGraph）完全一致：
 * 只做「路由 + 取数编排 + 质检」，不承载业务实现，也不装配数字员工。
 *
 * <p>迁移对照：
 * <ul>
 *   <li>{@code graph.py:1445 build_graph()} → {@code graph/OrchestratorGraphConfig}</li>
 *   <li>{@code graph.py:398 supervise()} → {@code graph/nodes/SuperviseNode}</li>
 *   <li>{@code graph.py:499 digital_human_node()} → {@code graph/nodes/KbNode}</li>
 *   <li>{@code graph.py:1385 respond()} → {@code graph/nodes/RespondNode}</li>
 *   <li>{@code server.py:200 chat_stream()} → {@code api/ChatController}</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
