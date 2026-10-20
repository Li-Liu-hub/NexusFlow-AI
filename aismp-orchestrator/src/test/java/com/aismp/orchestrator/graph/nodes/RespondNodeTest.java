package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.graph.StateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * respond 节点的契约：result 载荷的 key 就是前端契约，改名等于改协议。
 *
 * <p>另外锁一个迁移期行为：<b>还没迁过来的路线必须明说「未迁移」</b>，
 * 不能用空回答冒充正常结果 —— 否则排查时根本分不清是模型没话说还是代码没走通。
 */
class RespondNodeTest {

    private static final RespondNode NODE = new RespondNode();

    private static OverAllState state(Map<String, Object> data) {
        return OverAllStateBuilder.builder().withData(new HashMap<>(data)).build();
    }

    @Test
    void keepsContractKeysForKbRoute() {
        Map<String, Object> out = NODE.apply(state(Map.of(
                StateKeys.ROUTE, StateKeys.ROUTE_KB,
                StateKeys.AGENT_RESPONSE, "按《终端资产管理办法》第 5 条……",
                StateKeys.CARDS, List.of(Map.of("type", "kb")),
                StateKeys.SUGGESTION_ID, "sug-9")));

        assertThat(out).containsKeys(StateKeys.AGENT_RESPONSE, StateKeys.CARDS,
                StateKeys.STRATEGY, StateKeys.ROUTE, StateKeys.SUGGESTION_ID);
        assertThat(out.get(StateKeys.AGENT_RESPONSE)).asString().startsWith("按《终端资产");
        assertThat(out.get(StateKeys.ROUTE)).isEqualTo(StateKeys.ROUTE_KB);
        assertThat(out.get(StateKeys.SUGGESTION_ID)).isEqualTo("sug-9");
    }

    @Test
    void defaultsCardsToEmptyListNotNull() {
        // 前端直接 .map() 遍历 cards，null 会白屏；空列表才是安全默认值
        Map<String, Object> out = NODE.apply(state(Map.of(
                StateKeys.ROUTE, StateKeys.ROUTE_KB,
                StateKeys.AGENT_RESPONSE, "ok")));
        assertThat(out.get(StateKeys.CARDS)).isInstanceOf(List.class);
        assertThat((List<?>) out.get(StateKeys.CARDS)).isEmpty();
        assertThat(out.get(StateKeys.STRATEGY)).isEqualTo("");
    }

    @Test
    void emptyAnswerFallsBackToHumanSentence() {
        // 空回答必须兜一句人话；且不能暴露内部实现状态（线路名 / 迁移进度都不该出现在对客文案里）
        Map<String, Object> out = NODE.apply(state(Map.of(StateKeys.ROUTE, StateKeys.ROUTE_CHART)));
        String answer = String.valueOf(out.get(StateKeys.AGENT_RESPONSE));
        assertThat(answer).isNotEmpty().contains("稍后再试");
        assertThat(answer).doesNotContain("chart").doesNotContain("迁移");
    }

    @Test
    void emptyAnswerOnOrderRouteIsAllowed() {
        // order 路线的落点是建单动作，正文可以为空（前端看 cards/order_info）
        Map<String, Object> out = NODE.apply(state(Map.of(StateKeys.ROUTE, StateKeys.ROUTE_ORDER)));
        assertThat(out.get(StateKeys.AGENT_RESPONSE)).isEqualTo("");
    }

    @Test
    void missingRouteFallsBackToKbWithoutFakeAnswer() {
        Map<String, Object> out = NODE.apply(state(Map.of(StateKeys.AGENT_RESPONSE, "有答案")));
        assertThat(out.get(StateKeys.ROUTE)).isEqualTo("");
        assertThat(out.get(StateKeys.AGENT_RESPONSE)).isEqualTo("有答案");
    }
}
