package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.graph.StateKeys;
import com.aismp.orchestrator.graph.StateSupport;
import com.aismp.orchestrator.service.InventoryRow;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数字红线第一道闸门：逐项核对卡片里的数字是否都能在算法输出里找到<b>同值</b>来源。
 *
 * <p>这是本项目最不能省的一道检查。卡片是用户唯一会看的东西，
 * 一旦卡片数字与算法输出对不上（比如多算了一台、合计写错），
 * 从界面上根本看不出来 —— 用户会拿着错数字去建单。所以这里做严格的 diff：
 * 合计要相等、每一行的 key 要存在、每一行的数量要相等。
 *
 * <p>只查不修：发现问题写进 meta，由 critic 决定回炉还是放行。
 */
public class VerifyNumbersNode implements NodeAction {

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> algo = StateSupport.asMap(state.data().get(StateKeys.ALGO_RESULT));
        List<Map<String, Object>> cards = state.data().get(StateKeys.CARDS) instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();

        Map<String, Integer> expectedTransfer = new LinkedHashMap<>();
        for (Map<String, Object> t : (List<Map<String, Object>>) algo.getOrDefault("transfers", List.of())) {
            String key = key(t.get("fromWarehouse"), t.get("toWarehouse"), t.get("model"));
            expectedTransfer.merge(key, InventoryRow.i(t.get("quantity")), Integer::sum);
        }
        Map<String, Integer> expectedProcure = new LinkedHashMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) algo.getOrDefault("procurements", List.of())) {
            expectedProcure.merge(StateSupport.text(p.get("model")), InventoryRow.i(p.get("suggestedQty")), Integer::sum);
        }

        List<String> violations = new ArrayList<>();
        for (Map<String, Object> card : cards) {
            String type = StateSupport.text(card.get("type"));
            if ("transfer_list".equals(type)) {
                int total = InventoryRow.i(card.get("totalQuantity"));
                int expect = expectedTransfer.values().stream().mapToInt(Integer::intValue).sum();
                if (total != expect) {
                    violations.add("调拨清单合计 %d ≠ 算法合计 %d".formatted(total, expect));
                }
                for (Map<String, Object> row : (List<Map<String, Object>>) card.getOrDefault("rows", List.of())) {
                    String key = key(row.get("fromWarehouse"), row.get("toWarehouse"), row.get("model"));
                    Integer exp = expectedTransfer.get(key);
                    if (exp == null) {
                        violations.add("调拨行 %s 在算法输出里不存在".formatted(key));
                    } else if (InventoryRow.i(row.get("quantity")) != exp) {
                        violations.add("调拨行 %s 数量 %s ≠ 算法 %d"
                                .formatted(key, row.get("quantity"), exp));
                    }
                }
            } else if ("procurement_list".equals(type)) {
                int total = InventoryRow.i(card.get("totalQuantity"));
                int expect = expectedProcure.values().stream().mapToInt(Integer::intValue).sum();
                if (total != expect) {
                    violations.add("采购清单合计 %d ≠ 算法合计 %d".formatted(total, expect));
                }
                for (Map<String, Object> row : (List<Map<String, Object>>) card.getOrDefault("rows", List.of())) {
                    String model = StateSupport.text(row.get("model"));
                    Integer exp = expectedProcure.get(model);
                    if (exp == null) {
                        violations.add("采购行 %s 在算法输出里不存在".formatted(model));
                    } else if (InventoryRow.i(row.get("suggestedQty")) != exp) {
                        violations.add("采购行 %s 数量 %s ≠ 算法 %d"
                                .formatted(model, row.get("suggestedQty"), exp));
                    }
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> metaOut = new LinkedHashMap<>();
        metaOut.put("verify_violations", violations);
        metaOut.put("verify_passed", violations.isEmpty());
        out.put(StateKeys.META, metaOut);
        return out;
    }

    private static String key(Object from, Object to, Object model) {
        return "%s->%s:%s".formatted(StateSupport.text(from), StateSupport.text(to), StateSupport.text(model));
    }
}
