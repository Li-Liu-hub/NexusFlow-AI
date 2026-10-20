package com.aismp.orchestrator;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.aismp.orchestrator.api.ChatController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 上下文装配测试（不起 Tomcat、不连网络、不连库）。
 *
 * <p>为什么必须有这一层：之前的单测全是**手工 new 节点**再装配图，
 * 于是「三个节点压根没注册成 Bean」这种错完全测不出来 —— 编译过、单测 32 个全绿，
 * 一部署上去 `/health` 直接 500（UnsatisfiedDependencyException）。
 *
 * <p>这个测试只做一件事：让容器把所有 Bean 真正创建一遍。
 * 装配断链会在这里红，而不是等部署后才发现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrchestratorContextTest {

    @Autowired
    private CompiledGraph graph;

    @Autowired
    private ChatController chatController;

    @Test
    void graphAndControllerAreWired() {
        assertThat(graph).isNotNull();
        assertThat(graph.stateGraph).isNotNull();
        assertThat(chatController).isNotNull();
    }
}
