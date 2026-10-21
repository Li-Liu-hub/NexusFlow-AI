package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.client.TerminalApiClient;
import com.aismp.orchestrator.config.OrchestratorProperties;
import com.aismp.orchestrator.graph.StateKeys;
import com.aismp.orchestrator.graph.StateSupport;
import com.aismp.orchestrator.service.ChartBuilder;
import com.aismp.orchestrator.service.WarehouseScope;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图表路线第 2 步：取数并组装图表卡（确定性，无 LLM）。
 *
 * <p>与报告节点相同的失败纪律：取数失败 → 明确说"没取到"；
 * 查到了但为空 → 明确说"没有可用于绘图的数据"。**绝不渲染一张空图** ——
 * 空图会被理解成"库存为零"，比报错更危险。
 */
public class ChartBuildNode implements NodeAction {

    private final ChartBuilder builder;
    private final OrchestratorProperties properties;

    public ChartBuildNode(ChartBuilder builder, OrchestratorProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        long start = System.currentTimeMillis();
        Map<String, Object> plan = StateSupport.asMap(state.data().get(StateKeys.CHART_PLAN));
        String kind = StateSupport.text(plan.get("kind"));
        String style = StateSupport.text(plan.get("style"));
        String query = StateSupport.text(state.data().get(StateKeys.QUERY));
        Map<String, Object> meta = StateSupport.asMap(state.data().get(StateKeys.META));
        Set<String> scope = WarehouseScope.of(StateSupport.text(meta.get("warehouse_code")),
                properties.getTerminal().warehouseList(),
                properties.getTerminal().getRootWarehouse());

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> metaOut = new LinkedHashMap<>();

        ChartBuilder.Result result;
        try {
            result = builder.build(kind, style, scope, query);
        } catch (TerminalApiClient.TerminalApiException e) {
            String text = "图表数据获取失败，暂时无法绘图（库存服务不可达，请确认服务已启动后重试）。";
            out.put(StateKeys.CARDS, List.of());
            out.put(StateKeys.AGENT_RESPONSE, text);
            metaOut.put("chart_ok", false);
            metaOut.put("chart_error", e.getMessage());
            metaOut.put("chart_duration_ms", System.currentTimeMillis() - start);
            metaOut.put("channel", "chart");
            out.put(StateKeys.META, metaOut);
            return out;
        }

        Object labels = result.card().get("labels");
        boolean empty = !(labels instanceof List<?> l) || l.isEmpty();
        if (empty) {
            out.put(StateKeys.CARDS, List.of());
            out.put(StateKeys.AGENT_RESPONSE, "当前没有可用于绘图的数据（查询结果为空）。");
            metaOut.put("chart_ok", false);
            metaOut.put("chart_error", "empty");
            metaOut.put("chart_duration_ms", System.currentTimeMillis() - start);
            metaOut.put("channel", "chart");
            out.put(StateKeys.META, metaOut);
            return out;
        }

        out.put(StateKeys.CARDS, List.of(result.card()));
        out.put(StateKeys.AGENT_RESPONSE, result.note());
        metaOut.put("chart_ok", true);
        metaOut.put("chart_duration_ms", System.currentTimeMillis() - start);
        metaOut.put("channel", "chart");
        out.put(StateKeys.META, metaOut);
        return out;
    }
}
