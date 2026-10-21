package com.aismp.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图表选型的规则层与时间范围解析。
 *
 * <p>为什么值得单测：原版这里是「LLM 优先 + 静默重试」，一次提供方抖动把整条链路拖到
 * 301.8 秒；现在改成规则优先，那这条规则就必须准 —— 命中率高低直接决定图表路线
 * 是「1ms 出图」还是「再赌一次模型」。
 */
class ChartBuilderRuleTest {

    @Test
    void recognisesEachDimension() {
        assertThat(ChartBuilder.kindByRule("各仓位的库存分布")).isEqualTo("warehouse");
        assertThat(ChartBuilder.kindByRule("库龄结构怎么样")).isEqualTo("aging");
        assertThat(ChartBuilder.kindByRule("按型号看看库存")).isEqualTo("model");
        assertThat(ChartBuilder.kindByRule("总体情况如何")).isEqualTo("stat");
        assertThat(ChartBuilder.kindByRule("最近半年入库趋势")).isEqualTo("time");
    }

    @Test
    void returnsNullWhenNothingMatches() {
        // 未命中才允许走 LLM 兜底，不能瞎猜
        assertThat(ChartBuilder.kindByRule("终端故障返厂怎么登记")).isNull();
    }

    @Test
    void styleKeywords() {
        assertThat(ChartBuilder.styleByKeyword("画个饼图")).isEqualTo("pie");
        assertThat(ChartBuilder.styleByKeyword("占比是多少")).isEqualTo("pie");
        assertThat(ChartBuilder.styleByKeyword("看走势")).isEqualTo("line");
        assertThat(ChartBuilder.styleByKeyword("给个明细表格")).isEqualTo("table");
        assertThat(ChartBuilder.styleByKeyword("画一下")).isNull();
    }

    @Test
    void timeRangeDefaultsToSixMonths() {
        String[] r = ChartBuilder.timeRange("随便看看");
        assertThat(r[0]).isEqualTo("month");
        String from = r[1];
        LocalDate expected = ChartBuilder.shiftMonths(LocalDate.now(), 5);
        assertThat(from).isEqualTo(expected.toString());
    }

    @Test
    void timeRangeUnderstandsChineseNumerals() {
        // 近三年 → 年粒度，起点是 3 年前的 1 月 1 日
        String[] r = ChartBuilder.timeRange("近三年入库情况");
        assertThat(r[0]).isEqualTo("year");
        assertThat(r[1]).isEqualTo((LocalDate.now().getYear() - 2) + "-01-01");

        String[] m = ChartBuilder.timeRange("近三个月入库");
        assertThat(m[0]).isEqualTo("month");
        assertThat(m[1]).isEqualTo(ChartBuilder.shiftMonths(LocalDate.now(), 2).toString());
    }

    @Test
    void halfYearIsSixMonths() {
        // 「近半年」必须先归一成 6 个月，否则 半→6 会被误配成年粒度
        String[] r = ChartBuilder.timeRange("最近半年入库量");
        assertThat(r[0]).isEqualTo("month");
        assertThat(r[1]).isEqualTo(ChartBuilder.shiftMonths(LocalDate.now(), 5).toString());
    }

    @Test
    void thisYearAndLastYear() {
        assertThat(ChartBuilder.timeRange("今年入库").clone()[0]).isEqualTo("month");
        assertThat(ChartBuilder.timeRange("去年入库")[1])
                .isEqualTo((LocalDate.now().getYear() - 1) + "-01-01");
    }
}
