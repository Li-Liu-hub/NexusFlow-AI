package com.aismp.orchestrator.graph;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.state.strategy.MergeStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 状态键的合并策略。
 *
 * <p>为什么必须显式声明：Alibaba Graph 的默认策略是「后写覆盖」，
 * 而 {@code meta} 是多个节点各自往里塞计时/通道信息的共享字典 ——
 * 用覆盖策略会丢掉先写的 key（原 Python 版用 {@code Annotated[dict, merge_dict]}
 * 显式 reducer 解决同一个问题，见 state.py 顶部注释）。
 */
@Configuration
public class GraphStateConfig {

    @Bean
    public KeyStrategyFactory keyStrategyFactory() {
        return new KeyStrategyFactoryBuilder()
                .defaultStrategy(new ReplaceStrategy())
                .addStrategy(StateKeys.META, new MergeStrategy())
                .build();
    }
}
