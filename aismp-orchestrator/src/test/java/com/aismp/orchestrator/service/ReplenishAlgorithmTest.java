package com.aismp.orchestrator.service;

import com.aismp.orchestrator.service.ReplenishAlgorithm.Plan;
import com.aismp.orchestrator.service.ReplenishAlgorithm.Procurement;
import com.aismp.orchestrator.service.ReplenishAlgorithm.Transfer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 补货算法单测 —— 本项目「数字不进 LLM 的嘴」这条主张的实际验收点。
 *
 * <p>这个类是整个平台唯一产出数字的地方，算错了没有任何上游能兜住：
 * 卡片上写着"调出 500 台"，实际会调 300 台，用户拿着卡片去建单就是错单。
 * 所以这里把每条规则都钉住：同层比较、上级→下级、可调出量递减、
 * 物料分摊守恒、无共同物料不发建议、缺口转采购。
 *
 * <p>全部纯函数，不连库不连网。
 */
class ReplenishAlgorithmTest {

    /** 造一行「仓位 × 物料」粒度的库存 */
    private static InventoryRow row(String wh, int level, String model, int available, double aging) {
        InventoryRow r = new InventoryRow();
        r.warehouseCode = wh;
        r.warehouseName = wh + "仓";
        r.warehouseLevel = level;
        r.model = model;
        r.materialCode = "M-" + model;
        r.materialName = model + "物料";
        r.bigType = "光猫";
        r.vendor = "华为";
        r.availableQty = available;
        r.totalQty = available;
        r.avgAgingDays = aging;
        r.maxAgingDays = (int) aging;
        r.materials.add(r.toMaterial());
        return r;
    }

    // ---------------- 聚合 ----------------

    @Test
    void aggregatesSameWarehouseAndModelIntoOneRow() {
        List<InventoryRow> rows = List.of(
                row("9B00", 2, "F657", 100, 30),
                row("9B00", 2, "F657", 50, 90));
        rows.get(1).materialCode = "M-F657-b";

        List<InventoryRow> agg = ReplenishAlgorithm.aggregateByWarehouseModel(rows);

        // 不聚合的话：同一仓会以两行身份各进一次 short/surplus，产出重复建议
        assertThat(agg).hasSize(1);
        assertThat(agg.get(0).availableQty).isEqualTo(150);
        assertThat(agg.get(0).materials).hasSize(2);
        // 主物料取可用量最大的
        assertThat(agg.get(0).materialCode).isEqualTo("M-F657");
    }

    @Test
    void aggregatedAgingIsWeightedByQuantity() {
        List<InventoryRow> rows = List.of(
                row("9B00", 2, "F657", 100, 0),
                row("9B00", 2, "F657", 300, 200));
        rows.get(1).materialCode = "M-F657-b";

        InventoryRow agg = ReplenishAlgorithm.aggregateByWarehouseModel(rows).get(0);

        // (0*100 + 200*300) / 400 = 150
        assertThat(agg.avgAgingDays).isEqualTo(150.0);
    }

    // ---------------- 物料分摊 ----------------

    @Test
    void splitRespectsSourceAvailabilityAndConservesQuantity() {
        List<Map<String, Object>> src = List.of(
                Map.of("materialCode", "A", "availableQty", 10),
                Map.of("materialCode", "B", "availableQty", 90));
        List<Map<String, Object>> tgt = List.of(
                Map.of("materialCode", "A", "quantity", 0),
                Map.of("materialCode", "B", "quantity", 0));

        List<Map<String, Object>> parts = ReplenishAlgorithm.splitByMaterials(50, src, tgt);

        int sum = parts.stream().mapToInt(p -> InventoryRow.i(p.get("quantity"))).sum();
        assertThat(sum).isEqualTo(50);   // 守恒：明细之和 == 建议量
        for (Map<String, Object> p : parts) {
            String code = InventoryRow.str(p.get("materialCode"));
            int have = code.equals("A") ? 10 : 90;
            assertThat(InventoryRow.i(p.get("quantity")))
                    .as("单物料分量不能超过调出仓实有")
                    .isLessThanOrEqualTo(have);
        }
    }

    @Test
    void splitReturnsEmptyWhenNoCommonMaterial() {
        List<Map<String, Object>> src = List.of(Map.of("materialCode", "A", "availableQty", 10));
        List<Map<String, Object>> tgt = List.of(Map.of("materialCode", "Z", "quantity", 0));

        assertThat(ReplenishAlgorithm.splitByMaterials(5, src, tgt)).isEmpty();
    }

    // ---------------- 方案生成 ----------------

    @Test
    void producesTransferFromUpperSurplusToLowerShortage() {
        // 9B00(L2) 富余 1000，9BZA(L3) 短缺 10 → 上级调下级
        List<InventoryRow> rows = List.of(
                row("9B00", 2, "F657", 1000, 60),
                row("9BZA", 3, "F657", 10, 20));

        Plan plan = ReplenishAlgorithm.plan(rows, 4, 3);

        assertThat(plan.transfers()).hasSize(1);
        Transfer t = plan.transfers().get(0);
        assertThat(t.fromWarehouse()).isEqualTo("9B00");
        assertThat(t.toWarehouse()).isEqualTo("9BZA");
        assertThat(t.operationType()).isEqualTo("TRANSFER");
        assertThat(t.quantity()).isGreaterThan(0);
        // 短缺到 0 才算紧急
        assertThat(t.priority()).isEqualTo("建议补货");
    }

    @Test
    void neverTransfersFromLowerToUpper() {
        // 下级富余、上级短缺：业务规则不允许"收回"，只能转采购
        List<InventoryRow> rows = List.of(
                row("9B00", 2, "F657", 10, 20),
                row("9BZA", 3, "F657", 1000, 60));

        Plan plan = ReplenishAlgorithm.plan(rows, 4, 3);

        assertThat(plan.transfers()).isEmpty();
        assertThat(plan.procurements()).isNotEmpty();
        assertThat(plan.procurements().get(0).toWarehouse()).isEqualTo("9B00");
        assertThat(plan.strategy()).isEqualTo(ReplenishAlgorithm.STRATEGY_PROCURE_ONLY);
    }

    @Test
    void doesNotPromiseSameSurplusTwice() {
        // 一个富余仓、两个短缺仓：累计调出不能超过它的富余量
        List<InventoryRow> rows = List.of(
                row("9B00", 2, "F657", 1000, 60),
                row("9BZA", 3, "F657", 20, 10),
                row("9BZB", 3, "F657", 20, 10));

        Plan plan = ReplenishAlgorithm.plan(rows, 4, 3);

        int outFrom9B00 = plan.transfers().stream()
                .filter(t -> t.fromWarehouse().equals("9B00"))
                .mapToInt(Transfer::quantity).sum();
        double avg = (1000 + 20 + 20) / 3.0;              // 346.67
        assertThat(outFrom9B00).isLessThanOrEqualTo((int) (1000 - avg));
    }

    @Test
    void noSuggestionWhenOnlyOneWarehouseHasTheModel() {
        // 单仓谈不上仓间调剂，宁可不出建议也不编一条
        Plan plan = ReplenishAlgorithm.plan(List.of(row("9B00", 2, "F657", 100, 30)), 4, 3);
        assertThat(plan.transfers()).isEmpty();
        assertThat(plan.procurements()).isEmpty();
    }

    @Test
    void strategyIsMixedWhenBothTransferAndProcurementExist() {
        // 有「中间仓位」吃掉一部分均值时，富余量就不够填满所有缺口 → 调拨 + 采购同时出现。
        // 注意：如果只有富余仓和短缺仓（无中间仓），可调出量恰好等于缺口总和，永远出不了 mixed。
        List<InventoryRow> rows = List.of(
                row("9B00", 2, "F657", 600, 60),
                row("9BZA", 3, "F657", 300, 10),
                row("9BZB", 3, "F657", 300, 10),
                row("9BZC", 3, "F657", 0, 10),
                row("9BZD", 3, "F657", 0, 10));

        Plan plan = ReplenishAlgorithm.plan(rows, 4, 3);

        assertThat(plan.transfers()).isNotEmpty();
        assertThat(plan.procurements()).isNotEmpty();
        assertThat(plan.strategy()).isEqualTo(ReplenishAlgorithm.STRATEGY_MIXED);
        assertThat(plan.trace()).isNotEmpty();
    }

    @Test
    void planIsDeterministic() {
        // 同一份输入两次跑必须逐字段相同 —— 否则"用户看到的建议"和"建单用的建议"会对不上
        List<InventoryRow> rows = new ArrayList<>(List.of(
                row("9B00", 2, "F657", 900, 60),
                row("9BZA", 3, "F657", 30, 20),
                row("9B00", 2, "PT9500", 800, 40),
                row("9BZA", 3, "PT9500", 12, 10)));

        Plan a = ReplenishAlgorithm.plan(rows, 4, 3);
        Plan b = ReplenishAlgorithm.plan(rows, 4, 3);

        assertThat(a.toMap()).isEqualTo(b.toMap());
    }

    // ---------------- 报表口径（留好接口）----------------

    @Test
    void docSpecFormulas() {
        assertThat(ReplenishAlgorithm.weeklyUsage(3000, 90)).isEqualTo(233.33);
        assertThat(ReplenishAlgorithm.weeklyUsage(100, 0)).isEqualTo(0.0);
        assertThat(ReplenishAlgorithm.waterline(233.33, 4)).isEqualTo(933.32);
        assertThat(ReplenishAlgorithm.gap(1000, 700, 100)).isEqualTo(200);
        assertThat(ReplenishAlgorithm.gap(50, 700, 100)).isZero();
    }

    @Test
    void summarizeAgingCountsLongBands() {
        Map<String, Object> s = ReplenishAlgorithm.summarizeAging(List.of(
                Map.of("band", "0-30", "cnt", 100),
                Map.of("band", "180-365", "cnt", 50),
                Map.of("band", "365+", "cnt", 50)));

        assertThat(s.get("total")).isEqualTo(200);
        assertThat(s.get("longAging")).isEqualTo(100);
        assertThat(s.get("longAgingRatio")).isEqualTo(0.5);
    }
}
