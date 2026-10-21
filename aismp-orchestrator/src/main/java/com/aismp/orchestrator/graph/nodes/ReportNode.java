package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.graph.StateKeys;
import com.aismp.orchestrator.graph.StateSupport;
import com.aismp.orchestrator.service.SuggestionStore;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 补货路线第 3 步：装配卡片。
 *
 * <p>卡片类型沿用前端契约：{@code replenish_reminder} / {@code transfer_list} /
 * {@code procurement_list}。所有数字都是 algo_result 的<b>原值搬运</b>，
 * 本节点不做任何算术 —— 这样下游 verify_numbers 的 diff 闸门才有意义。
 *
 * <p>总结句用**模板**而不是 LLM：原 Python 版在这一步调过一次总结 LLM，
 * 实测出现过「algorithm 完成后 3.5 分钟无进展」的卡死，回滚即恢复；而这句话
 * 本来就只有固定槽位，模板同样自然，还少一个故障点。
 */
public class ReportNode implements NodeAction {

    private final SuggestionStore suggestions;

    public ReportNode(SuggestionStore suggestions) {
        this.suggestions = suggestions;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long start = System.currentTimeMillis();
        Map<String, Object> meta = StateSupport.asMap(state.data().get(StateKeys.META));

        // 取数失败必须短路：空快照会被算成「0 条建议」，写成「库存均衡」就是把故障报成健康
        if (Boolean.FALSE.equals(meta.get("fetch_ok"))) {
            String text = "库存数据获取失败，暂时无法给出补货建议（库存服务不可达，请确认服务已启动后重试）。";
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "replenish_unavailable");
            card.put("title", "补货建议暂不可用");
            card.put("detail", "库存数据未取到，本次未生成任何建议");
            card.put("actions", List.of());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(StateKeys.CARDS, List.of(card));
            out.put(StateKeys.AGENT_RESPONSE, text);
            Map<String, Object> metaOut = new LinkedHashMap<>();
            metaOut.put("report_duration_ms", System.currentTimeMillis() - start);
            metaOut.put("report_skipped_reason", "fetch_failed");
            metaOut.put("fetch_error", meta.get("fetch_error"));
            out.put(StateKeys.META, metaOut);
            return out;
        }

        Map<String, Object> algo = StateSupport.asMap(state.data().get(StateKeys.ALGO_RESULT));
        String strategy = StateSupport.text(state.data().get(StateKeys.STRATEGY));
        List<Map<String, Object>> transfers = (List<Map<String, Object>>) algo.getOrDefault("transfers", List.of());
        List<Map<String, Object>> procurements = (List<Map<String, Object>>) algo.getOrDefault("procurements", List.of());
        Map<String, Object> snapshot = StateSupport.asMap(state.data().get(StateKeys.INVENTORY_SNAPSHOT));

        int transferQty = transfers.stream().mapToInt(t -> InventoryNum.i(t.get("quantity"))).sum();
        int procureQty = procurements.stream().mapToInt(p -> InventoryNum.i(p.get("suggestedQty"))).sum();
        TreeSet<String> models = new TreeSet<>();
        transfers.forEach(t -> models.add(StateSupport.text(t.get("model"))));
        procurements.forEach(p -> models.add(StateSupport.text(p.get("model"))));
        models.remove("");

        List<Map<String, Object>> cards = new ArrayList<>();

        // 1) 补货提醒卡（总是有，作为对话流入口）
        Map<String, Object> reminder = new LinkedHashMap<>();
        reminder.put("type", "replenish_reminder");
        reminder.put("title", "小智补货提醒");
        reminder.put("detail", transferQty + procureQty > 0
                ? "共需补货 %d 台".formatted(transferQty + procureQty)
                : "当前各仓位库存均衡，暂无补货需求");
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTerminals", snapshot.getOrDefault("totalTerminals", 0));
        stats.put("totalAvailable", algo.getOrDefault("totalAvailable", 0));
        stats.put("totalInTransit", algo.getOrDefault("totalInTransit", 0));
        stats.put("longAgingCount", algo.getOrDefault("longAgingCount", 0));
        reminder.put("stats", stats);
        reminder.put("actions", List.of("去补货", "下载补货清单"));
        cards.add(reminder);

        // 2) 调拨清单卡
        if (!transfers.isEmpty()) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "transfer_list");
            card.put("title", "建议调拨补货清单");
            card.put("subtitle", "优先匹配可调拨库存，减少重复采购并提升库存周转效率");
            card.put("badge", "AI 智能匹配");
            card.put("totalQuantity", transferQty);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> t : transfers) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("model", t.get("model"));
                row.put("materialName", t.get("materialName"));
                row.put("fromWarehouse", t.get("fromWarehouse"));
                row.put("fromWarehouseName", t.get("fromWarehouseName"));
                row.put("toWarehouse", t.get("toWarehouse"));
                row.put("toWarehouseName", t.get("toWarehouseName"));
                row.put("quantity", t.get("quantity"));
                row.put("priority", t.get("priority"));
                row.put("operationType", t.getOrDefault("operationType", "TRANSFER"));
                row.put("reason", t.get("reason"));
                row.put("materials", t.getOrDefault("materials", List.of()));
                rows.add(row);
            }
            card.put("rows", rows);
            card.put("actions", List.of("申请调拨"));
            cards.add(card);
        }

        // 3) 采购清单卡
        if (!procurements.isEmpty()) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "procurement_list");
            card.put("title", "建议采购补货清单");
            card.put("badge", "AI 智能推荐");
            card.put("totalQuantity", procureQty);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> p : procurements) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("model", p.get("model"));
                row.put("materialCode", p.get("materialCode"));
                row.put("materialName", p.get("materialName"));
                row.put("vendor", p.get("vendor"));
                String brand = StateSupport.text(p.get("suggestedBrand"));
                row.put("suggestedBrand", brand.isEmpty() ? "待定" : brand);
                row.put("suggestedQty", p.get("suggestedQty"));
                row.put("priority", p.get("priority"));
                row.put("reason", p.get("reason"));
                rows.add(row);
            }
            card.put("rows", rows);
            card.put("actions", List.of("导出采购清单"));
            cards.add(card);
        }

        String summary = summaryOf(strategy, transfers.size(), transferQty,
                procurements.size(), procureQty, String.join("、", models),
                InventoryNum.i(algo.get("longAgingCount")));

        Map<String, Object> suggestionPayload = new LinkedHashMap<>();
        suggestionPayload.put("strategy", strategy);
        suggestionPayload.put("transfers", transfers);
        suggestionPayload.put("procurements", procurements);
        suggestionPayload.put("models", new ArrayList<>(models));
        String suggestionId = suggestions.save(suggestionPayload);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(StateKeys.CARDS, cards);
        out.put(StateKeys.SUGGESTION_ID, suggestionId);
        out.put(StateKeys.AGENT_RESPONSE, summary);
        Map<String, Object> metaOut = new LinkedHashMap<>();
        metaOut.put("report_duration_ms", System.currentTimeMillis() - start);
        metaOut.put("suggestion_id", suggestionId);
        metaOut.put("strategy", strategy);
        metaOut.put("transfer_qty", transferQty);
        metaOut.put("procure_qty", procureQty);
        out.put(StateKeys.META, metaOut);
        return out;
    }

    /** 总结句：所有数字都是槽位预填，LLM 不参与（原版即如此，这里更进一步用模板） */
    static String summaryOf(String strategy, int transferCount, int transferQty,
                            int procureCount, int procureQty, String models, int longAging) {
        if (transferQty + procureQty == 0) {
            return "当前各仓位库存分布均衡，暂无补货需求（长库龄 %d 台，可持续关注周转）。".formatted(longAging);
        }
        String head = switch (strategy == null ? "" : strategy) {
            case "procure_only" -> "省内可调拨库存不足，建议以采购补货：";
            case "mixed" -> "建议先调拨周转、缺口再采购：";
            default -> "建议优先调拨补货：";
        };
        StringBuilder sb = new StringBuilder(head);
        sb.append("共需补货 %d 台".formatted(transferQty + procureQty));
        if (transferCount > 0) {
            sb.append("，其中调拨 %d 条共 %d 台".formatted(transferCount, transferQty));
        }
        if (procureCount > 0) {
            sb.append("、采购 %d 条共 %d 台".formatted(procureCount, procureQty));
        }
        if (!models.isEmpty()) {
            sb.append("；涉及型号：").append(models);
        }
        sb.append("。");
        return sb.toString();
    }

    /** 数字读取（与算法侧的宽松解析同口径，避免 "12.0" 这类值抛异常） */
    static final class InventoryNum {
        private InventoryNum() {
        }

        static int i(Object v) {
            return com.aismp.orchestrator.service.InventoryRow.i(v);
        }
    }
}
