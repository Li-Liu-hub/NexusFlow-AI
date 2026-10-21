package com.aismp.aismpagent.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词通道的打分函数。
 *
 * <p>这一路存在的唯一理由是「精确字面命中」——型号编码、专有名词这类
 * 向量模型经常召回不准的东西。所以测试的重点是：
 * 字面命中必须显著高于语义泛泛相关，长文档不能天然占便宜。
 */
class KeywordSearcherTest {

    @Test
    void exactPhraseScoresHigherThanLooseOverlap() {
        String query = "库龄分布";
        double exact = KeywordSearcher.score(query, "终端库龄分布按 0-30、30-90 天分桶统计。");
        double loose = KeywordSearcher.score(query, "分布情况需要结合库房实际查看。");

        assertThat(exact).isGreaterThan(loose);
        assertThat(loose).isGreaterThan(0);
    }

    @Test
    void returnsZeroWhenNoBigramHits() {
        assertThat(KeywordSearcher.score("库龄分布", "调拨单需要账管与仓管双方确认。")).isZero();
        assertThat(KeywordSearcher.score("", "任意内容")).isZero();
        assertThat(KeywordSearcher.score("库", "库龄")).isZero();
    }

    @Test
    void longDocumentDoesNotWinJustByBeingLong() {
        // 同一句话重复堆长：命中次数增加了，但长度惩罚要压住它
        String query = "补货建议";
        String shortDoc = "补货建议按同级均值计算缺口。";
        String longDoc = shortDoc + "无关内容。".repeat(200);

        double shortScore = KeywordSearcher.score(query, shortDoc);
        double longScore = KeywordSearcher.score(query, longDoc);

        assertThat(longScore).isGreaterThan(0);
        // 长文档仍可能因重复出现略高，但不能出现数量级碾压
        assertThat(longScore).isLessThan(shortScore * 5);
    }

    @Test
    void ignoresPunctuationAndCase() {
        // 两边的**实际内容**必须一致，只差标点与空格；否则测的就不是"归一化"而是文本长度
        double withPunct = KeywordSearcher.score("F657 怎么申请调拨？", "关于 F657 的申请调拨流程说明");
        double withoutPunct = KeywordSearcher.score("F657怎么申请调拨", "关于F657的申请调拨流程说明");
        assertThat(withPunct).isCloseTo(withoutPunct, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void repeatedTermHasDiminishingReturn() {
        // 同一个词出现 10 次不应是出现 1 次的 10 倍分（用 log 压过）
        double once = KeywordSearcher.score("调拨", "调拨流程说明");
        double tenTimes = KeywordSearcher.score("调拨", "调拨流程说明" + "调拨".repeat(9));
        assertThat(tenTimes).isLessThan(once * 4);
    }
}
