package com.aismp.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可见范围：一个仓位能看「本仓 + 下级仓」。
 *
 * <p>这是数据隔离的信任边界。算错的后果不是报错，而是把别的公司的库存算进均值、
 * 甚至给出跨公司调拨建议 —— 界面上看起来一切正常。所以边界用例必须钉死。
 */
class WarehouseScopeTest {

    private static final List<String> ALL = List.of("9B00", "9BZA", "9BZA01", "9BZA02", "9BZA03", "9BZA04");

    @Test
    void rootSeesEverything() {
        Set<String> scope = WarehouseScope.of("9B00", ALL, "9B00");
        assertThat(scope).containsExactlyInAnyOrderElementsOf(ALL);
    }

    @Test
    void midLevelSeesSelfAndChildren() {
        Set<String> scope = WarehouseScope.of("9BZA", ALL, "9B00");
        assertThat(scope).containsExactly("9BZA", "9BZA01", "9BZA02", "9BZA03", "9BZA04");
        assertThat(scope).doesNotContain("9B00");
    }

    @Test
    void leafSeesOnlyItself() {
        Set<String> scope = WarehouseScope.of("9BZA01", ALL, "9B00");
        assertThat(scope).containsExactly("9BZA01");
    }

    @Test
    void blankCodeMeansNoFiltering() {
        // null 表示「不过滤」，与「空集合」是两件事：系统账号/本机脚本直连走这条
        assertThat(WarehouseScope.of(null, ALL, "9B00")).isNull();
        assertThat(WarehouseScope.of("  ", ALL, "9B00")).isNull();
    }

    @Test
    void unknownCodeStillGetsItself() {
        // 编码不在配置清单里也不能被放大成全量
        Set<String> scope = WarehouseScope.of("9BZZ", ALL, "9B00");
        assertThat(scope).containsExactly("9BZZ");
    }

    @Test
    void visibleHonoursNullScope() {
        assertThat(WarehouseScope.visible(null, "任意")).isTrue();
        assertThat(WarehouseScope.visible(Set.of("9BZA"), "9BZA")).isTrue();
        assertThat(WarehouseScope.visible(Set.of("9BZA"), "9B00")).isFalse();
    }
}
