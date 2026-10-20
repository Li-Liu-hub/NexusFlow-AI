package com.aismp.orchestrator.llm;

import com.aismp.orchestrator.config.OrchestratorProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由解析与降级规则。
 *
 * <p>这些分支全靠「模型输出千奇百怪」驱动，不脱网测就只能等线上出事才发现：
 * <ul>
 *   <li>正常单词 → 对应路线</li>
 *   <li>输出里带杂质（"Kb." / "order"）→ 仍能归一</li>
 *   <li>连续解析不出来 → 第 3 次后降级 kb，并带上原因</li>
 *   <li>抛异常 → 同样降级 kb，不往上甩 error 帧</li>
 *   <li>没配 Key → 一次都不调，直接 kb</li>
 * </ul>
 */
class LlmRouterTest {

    private static OrchestratorProperties props(String apiKey) {
        OrchestratorProperties p = new OrchestratorProperties();
        p.getLlm().setApiKey(apiKey);
        return p;
    }

    /** 依次返回预设输出；同时记录被调了几次（验证重试次数） */
    private static final class StubClient implements RouteLlmClient {
        private final Deque<Object> script = new ArrayDeque<>();
        int calls;

        StubClient(Object... outputs) {
            for (Object o : outputs) {
                script.add(o);
            }
        }

        @Override
        public String complete(String query) {
            calls++;
            Object next = script.isEmpty() ? "" : script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (String) next;
        }
    }

    @Test
    void mapsPlainWordToRoute() {
        LlmRouter router = new LlmRouter(props("sk-test"), new StubClient("replenish"));
        assertThat(router.route("哪个仓该补货", "u1").route()).isEqualTo("replenish");
    }

    @Test
    void toleratesNoiseAroundTheWord() {
        // 大小写 + 标点 + 一段废话，仍要能匹配
        LlmRouter router = new LlmRouter(props("sk-test"),
                new StubClient("Kb. 因为这是流程问题"));
        LlmRouter.RouteDecision d = router.route("故障返厂怎么登记", "u1");
        assertThat(d.route()).isEqualTo("kb");
        assertThat(d.reason()).contains("kb");
    }

    @Test
    void legacyOrderLabelFallsBackToReplenish() {
        // 建单不是意图，是补货线末端的按钮动作 —— 归入 replenish 而不是新开一路
        LlmRouter router = new LlmRouter(props("sk-test"), new StubClient("order"));
        assertThat(router.route("就按这个建议建单", "u1").route()).isEqualTo("replenish");
    }

    @Test
    void longWordWinsOverShortSubstring() {
        // "replenish" 里不含 "chart"/"kb"；但若模型同时输出两个词，按优先级取第一个
        LlmRouter router = new LlmRouter(props("sk-test"),
                new StubClient("chart 不是 replenish"));
        assertThat(router.route("画个分布图", "u1").route()).isEqualTo("replenish");
    }

    @Test
    void retriesThreeTimesThenDegradesToKb() {
        StubClient stub = new StubClient("看不懂", "还是看不懂", "真看不懂");
        LlmRouter router = new LlmRouter(props("sk-test"), stub);

        LlmRouter.RouteDecision d = router.route("???", "u1");

        assertThat(stub.calls).isEqualTo(3);
        assertThat(d.route()).isEqualTo("kb");
        assertThat(d.reason()).startsWith("路由降级").contains("第3次无法解析");
    }

    @Test
    void recoversOnSecondAttempt() {
        StubClient stub = new StubClient("??", "replenish");
        LlmRouter router = new LlmRouter(props("sk-test"), stub);

        assertThat(router.route("这个仓要不要补", "u1").route()).isEqualTo("replenish");
        assertThat(stub.calls).isEqualTo(2);
    }

    @Test
    void exceptionIsTreatedAsFailureNotCrash() {
        StubClient stub = new StubClient(
                new IllegalStateException("网关 502"), "chart");
        LlmRouter router = new LlmRouter(props("sk-test"), stub);

        LlmRouter.RouteDecision d = router.route("走势", "u1");

        assertThat(d.route()).isEqualTo("chart");
        assertThat(stub.calls).isEqualTo(2);
    }

    @Test
    void allAttemptsFailingWithExceptionStillDegrades() {
        StubClient stub = new StubClient(
                new RuntimeException("e1"), new RuntimeException("e2"), new RuntimeException("e3"));
        LlmRouter router = new LlmRouter(props("sk-test"), stub);

        LlmRouter.RouteDecision d = router.route("随便问问", "u1");

        assertThat(stub.calls).isEqualTo(3);
        assertThat(d.route()).isEqualTo("kb");
        assertThat(d.reason()).contains("RuntimeException");
    }

    @Test
    void missingApiKeyNeverCallsTheModel() {
        StubClient stub = new StubClient("replenish");
        LlmRouter router = new LlmRouter(props(""), stub);

        LlmRouter.RouteDecision d = router.route("补货", "u1");

        assertThat(stub.calls).isZero();
        assertThat(d.route()).isEqualTo("kb");
        assertThat(d.reason()).contains("未配置 LLM Key");
    }

    @Test
    void durationIsReported() {
        LlmRouter.RouteDecision d = new LlmRouter(props("sk-test"), new StubClient("kb"))
                .route("你好", "u1");
        assertThat(d.durationMs()).isGreaterThanOrEqualTo(0);
    }
}
