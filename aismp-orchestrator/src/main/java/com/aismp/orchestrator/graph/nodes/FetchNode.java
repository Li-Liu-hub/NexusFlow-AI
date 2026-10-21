package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.client.TerminalApiClient;
import com.aismp.orchestrator.config.OrchestratorProperties;
import com.aismp.orchestrator.graph.StateKeys;
import com.aismp.orchestrator.graph.StateSupport;
import com.aismp.orchestrator.service.WarehouseScope;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 补货路线第 1 步：取数。
 *
 * <p>为什么不让 LLM 决定「先查哪个再查哪个」：库存快照、聚合明细、库龄分布是**同一时刻的
 * 一致视图**，拆成多轮只会引入时间差（导致"可用+在途"对不上）。这里没有决策空间，
 * 所以用确定性代码 —— 能用 workflow 就不用自主 agent。
 *
 * <p>取数失败**必须留痕**（meta.fetch_ok=false）：下游据此短路，
 * 否则空快照会被算成「0 条建议」，再让模型措辞成「库存均衡、暂无需操作」。
 */
public class FetchNode implements NodeAction {

    private final TerminalApiClient api;
    private final OrchestratorProperties properties;

    public FetchNode(TerminalApiClient api, OrchestratorProperties properties) {
        this.api = api;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        long start = System.currentTimeMillis();
        Map<String, Object> meta = StateSupport.asMap(state.data().get(StateKeys.META));
        String warehouseCode = StateSupport.text(meta.get("warehouse_code"));
        Set<String> scope = WarehouseScope.of(warehouseCode,
                properties.getTerminal().warehouseList(),
                properties.getTerminal().getRootWarehouse());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean ok;
        String err = null;
        try {
            snapshot.putAll(api.snapshot());
            rows.addAll(api.aggregate());
            ok = true;
        } catch (TerminalApiClient.TerminalApiException e) {
            ok = false;
            err = e.getMessage();
        }

        // 明细行保留全量：补货算法需要跨仓可比才能找到「可调出仓」，产出再按可见范围收口
        snapshot.put("inventory", rows);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(StateKeys.INVENTORY_SNAPSHOT, snapshot);
        Map<String, Object> metaOut = new LinkedHashMap<>();
        metaOut.put("fetch_ok", ok);
        metaOut.put("fetch_error", err);
        metaOut.put("fetch_duration_ms", System.currentTimeMillis() - start);
        metaOut.put("channel", "replenish");
        metaOut.put("scope", scope == null ? null : scope.stream().sorted().toList());
        out.put(StateKeys.META, metaOut);
        return out;
    }
}
