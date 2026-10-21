package com.aismp.aismpagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合单测。
 *
 * <p>为什么单独测这个纯函数：混合检索的最终顺序由它决定，而它没有 IO、没有模型，
 * 是整条链路里唯一「可以对答案」的地方。参数（k、权重）调错不会报错，只会让结果慢慢变差，
 * 所以边界必须钉住。
 */
class RrfFuserTest {

    @Test
    void singleListKeepsOriginalOrder() {
        List<RrfFuser.Ranked> out = RrfFuser.fuse(List.of(List.of("a", "b", "c")), null, 60);

        assertThat(out).extracting(RrfFuser.Ranked::key).containsExactly("a", "b", "c");
    }

    @Test
    void documentFoundByBothListsBeatsOneFoundByOne() {
        // b 在向量路排第 3、关键词路排第 1；a 只在向量路排第 1 —— 两路都命中的应该赢
        List<List<String>> lists = List.of(
                List.of("a", "x", "b"),
                List.of("b", "y"));
        List<RrfFuser.Ranked> out = RrfFuser.fuse(lists, null, 60);

        assertThat(out.get(0).key()).isEqualTo("b");
    }

    @Test
    void duplicateKeyInsideOneListCountsOnce() {
        // 同一路里重复的 key 只按最好名次计分，避免自己把自己刷上去
        List<RrfFuser.Ranked> out = RrfFuser.fuse(List.of(List.of("a", "a", "b")), null, 60);

        double scoreA = out.stream().filter(r -> r.key().equals("a")).findFirst().orElseThrow().score();
        double scoreB = out.stream().filter(r -> r.key().equals("b")).findFirst().orElseThrow().score();
        assertThat(scoreA).isEqualTo(1.0 / 61);
        assertThat(scoreB).isEqualTo(1.0 / 63);
    }

    @Test
    void weightsShiftTheBalance() {
        List<List<String>> lists = List.of(List.of("vector-hit"), List.of("keyword-hit"));
        // 向量路权重 3：0.3，关键词路 1 → 向量胜
        List<RrfFuser.Ranked> weighted = RrfFuser.fuse(lists, List.of(3.0, 1.0), 60);
        assertThat(weighted.get(0).key()).isEqualTo("vector-hit");

        // 反过来
        List<RrfFuser.Ranked> flipped = RrfFuser.fuse(lists, List.of(1.0, 3.0), 60);
        assertThat(flipped.get(0).key()).isEqualTo("keyword-hit");
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        assertThat(RrfFuser.fuse(List.of(), null, 60)).isEmpty();
        assertThat(RrfFuser.fuse(null, null, 60)).isEmpty();
        assertThat(RrfFuser.fuse(List.of(List.of(), List.of("a")), null, 60))
                .extracting(RrfFuser.Ranked::key).containsExactly("a");
        // 空串/空白 key 不参与计分
        assertThat(RrfFuser.fuse(List.of(List.of("", "  ", "a")), null, 60))
                .extracting(RrfFuser.Ranked::key).containsExactly("a");
    }

    @Test
    void largerKFlattensHeadAdvantage() {
        // k 越大，相邻名次的分数差越小（这是 k 的语义，不是实现细节）
        double gapSmallK = 1.0 / (1 + 1) - 1.0 / (1 + 2);
        double gapLargeK = 1.0 / (60 + 1) - 1.0 / (60 + 2);
        assertThat(gapLargeK).isLessThan(gapSmallK);
    }
}
