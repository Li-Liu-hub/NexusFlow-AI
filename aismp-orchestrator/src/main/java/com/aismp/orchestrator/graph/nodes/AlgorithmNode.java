package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.graph.StateKeys;
import com.aismp.orchestrator.graph.StateSupport;
import com.aismp.orchestrator.service.InventoryRow;
import com.aismp.orchestrator.service.ReplenishAlgorithm;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 补货路线第 2 步：跑算法。
 *
 * <p>「算法即工具」的落地：本节点没有任何判断，只是把快照喂进
 * {@link ReplenishAlgorithm}（纯函数、无 IO、有单测），再把结果原样放进状态。
 * LLM 在这里没有发言权 —— 这是「能幻觉的只有选择，不能幻觉事实」的那道墙。
 *
 * <p>可见范围：算法跑**全量**（调拨建议天然要看到可调出仓的存量，否则基层用户找不到货源），
 * 但产出只保留与本仓/下级仓相关的行 —— 任一端命中即保留（调入要确认、调出也要本仓放行）。
 */
public class AlgorithmNode implements NodeAction {

    private static final int MAX_TRANSFER_PER_MODEL = 3;

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long start = System.currentTimeMillis();
        Map<String, Object> snapshot = StateSupport.asMap(state.data().get(StateKeys.INVENTORY_SNAPSHOT));
        Map<String, Object> meta = StateSupport.asMap(state.data().get(StateKeys.META));

        List<Map<String, Object>> raw = snapshot.get("inventory") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        List<InventoryRow> rows = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            rows.add(InventoryRow.fromMap(r));
        }

        ReplenishAlgorithm.Plan plan = ReplenishAlgorithm.plan(
                rows, ReplenishAlgorithm.SAFETY_WEEKS_DEFAULT, MAX_TRANSFER_PER_MODEL);

        Set<String> scope = scopeOf(meta);
        if (scope != null) {
            List<ReplenishAlgorithm.Transfer> transfers = plan.transfers().stream()
                    .filter(t -> scope.contains(t.fromWarehouse()) || scope.contains(t.toWarehouse()))
                    .toList();
            List<ReplenishAlgorithm.Procurement> procurements = plan.procurements().stream()
                    .filter(p -> p.toWarehouse() == null || p.toWarehouse().isEmpty()
                            || scope.contains(p.toWarehouse()))
                    .toList();
            boolean hasT = !transfers.isEmpty();
            boolean hasP = !procurements.isEmpty();
            String strategy = hasT && hasP ? ReplenishAlgorithm.STRATEGY_MIXED
                    : hasP ? ReplenishAlgorithm.STRATEGY_PROCURE_ONLY
                    : ReplenishAlgorithm.STRATEGY_TRANSFER_ONLY;
            plan = new ReplenishAlgorithm.Plan(strategy, transfers, procurements, plan.trace());
        }

        Map<String, Object> aging = ReplenishAlgorithm.summarizeAging(
                snapshot.get("aging") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of());

        Map<String, Object> result = new LinkedHashMap<>(plan.toMap());
        result.put("agingSummary", aging);
        result.put("totalAvailable", snapshot.getOrDefault("totalAvailable", 0));
        result.put("totalInTransit", snapshot.getOrDefault("totalInTransit", 0));
        result.put("longAgingCount", snapshot.getOrDefault("longAgingCount", 0));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(StateKeys.ALGO_RESULT, result);
        out.put(StateKeys.STRATEGY, plan.strategy());
        Map<String, Object> metaOut = new LinkedHashMap<>();
        metaOut.put("algorithm_duration_ms", System.currentTimeMillis() - start);
        metaOut.put("channel", "replenish");
        metaOut.put("algo_trace", plan.trace());
        out.put(StateKeys.META, metaOut);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> scopeOf(Map<String, Object> meta) {
        Object scope = meta.get("scope");
        if (scope instanceof List<?> list && !list.isEmpty()) {
            return new java.util.LinkedHashSet<>((List<String>) list);
        }
        return null;
    }
}
