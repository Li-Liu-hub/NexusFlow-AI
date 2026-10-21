package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.graph.StateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数字闸门：卡片数字与算法输出必须逐项相等。
 *
 * <p>这是本项目最不能省的一道检查。卡片是用户唯一会看的东西，一旦数字对不上
 * （多算一台、合计写错），界面上根本看不出来 —— 用户会拿着错数字去建单。
 * 所以这里既测"守得住错误"，也测"别误伤正确结果"（误伤会触发无意义的回炉）。
 */
class VerifyNumbersNodeTest {

    private static final VerifyNumbersNode NODE = new VerifyNumbersNode();

    private static OverAllState state(Map<String, Object> data) {
        return OverAllStateBuilder.builder().withData(new HashMap<>(data)).build();
    }

    private static Map<String, Object> transfer(String from, String to, String model, int qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fromWarehouse", from);
        m.put("toWarehouse", to);
        m.put("model", model);
        m.put("quantity", qty);
        return m;
    }

    private static Map<String, Object> card(String type, int total, List<Map<String, Object>> rows) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", type);
        c.put("totalQuantity", total);
        c.put("rows", rows);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(Map<String, Object> out) {
        return (Map<String, Object>) out.get(StateKeys.META);
    }

    @Test
    void passesWhenCardsMatchAlgorithmOutput() {
        Map<String, Object> algo = Map.of("transfers", List.of(transfer("9B00", "9BZA", "F657", 120)));
        Map<String, Object> cardRow = new LinkedHashMap<>(transfer("9B00", "9BZA", "F657", 120));

        Map<String, Object> out = NODE.apply(state(Map.of(
                StateKeys.ALGO_RESULT, algo,
                StateKeys.CARDS, List.of(card("transfer_list", 120, List.of(cardRow))))));

        assertThat(meta(out).get("verify_passed")).isEqualTo(true);
        assertThat((List<?>) meta(out).get("verify_violations")).isEmpty();
    }

    @Test
    void catchesTamperedRowQuantity() {
        Map<String, Object> algo = Map.of("transfers", List.of(transfer("9B00", "9BZA", "F657", 120)));
        Map<String, Object> tampered = new LinkedHashMap<>(transfer("9B00", "9BZA", "F657", 150));

        Map<String, Object> out = NODE.apply(state(Map.of(
                StateKeys.ALGO_RESULT, algo,
                StateKeys.CARDS, List.of(card("transfer_list", 150, List.of(tampered))))));

        assertThat(meta(out).get("verify_passed")).isEqualTo(false);
        assertThat((List<String>) meta(out).get("verify_violations"))
                .anyMatch(v -> v.contains("数量"));
    }

    @Test
    void catchesWrongCardTotal() {
        Map<String, Object> algo = Map.of("transfers", List.of(transfer("9B00", "9BZA", "F657", 120)));
        Map<String, Object> cardRow = new LinkedHashMap<>(transfer("9B00", "9BZA", "F657", 120));

        Map<String, Object> out = NODE.apply(state(Map.of(
                StateKeys.ALGO_RESULT, algo,
                StateKeys.CARDS, List.of(card("transfer_list", 999, List.of(cardRow))))));

        assertThat(meta(out).get("verify_passed")).isEqualTo(false);
        assertThat((List<String>) meta(out).get("verify_violations")).anyMatch(v -> v.contains("合计"));
    }

    @Test
    void catchesRowNotInAlgorithmOutput() {
        Map<String, Object> algo = Map.of("transfers", List.of(transfer("9B00", "9BZA", "F657", 120)));
        Map<String, Object> ghost = new LinkedHashMap<>(transfer("9B00", "9BZB", "F657", 60));

        Map<String, Object> out = NODE.apply(state(Map.of(
                StateKeys.ALGO_RESULT, algo,
                StateKeys.CARDS, List.of(card("transfer_list", 60, List.of(ghost))))));

        assertThat(meta(out).get("verify_passed")).isEqualTo(false);
        assertThat((List<String>) meta(out).get("verify_violations")).anyMatch(v -> v.contains("不存在"));
    }

    @Test
    void procurementRowsCheckedByModel() {
        Map<String, Object> algo = Map.of("procurements",
                List.of(Map.of("model", "PT9500", "suggestedQty", 30)));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("model", "PT9500");
        row.put("suggestedQty", 30);

        Map<String, Object> ok = NODE.apply(state(Map.of(
                StateKeys.ALGO_RESULT, algo,
                StateKeys.CARDS, List.of(card("procurement_list", 30, List.of(row))))));
        assertThat(meta(ok).get("verify_passed")).isEqualTo(true);

        row.put("suggestedQty", 31);
        Map<String, Object> bad = NODE.apply(state(Map.of(
                StateKeys.ALGO_RESULT, algo,
                StateKeys.CARDS, List.of(card("procurement_list", 31, List.of(row))))));
        assertThat(meta(bad).get("verify_passed")).isEqualTo(false);
    }

    @Test
    void emptyCardsPassTrivially() {
        // 没有建议时不该报错（"库存均衡"是正常结论）
        Map<String, Object> out = NODE.apply(state(Map.of(
                StateKeys.ALGO_RESULT, Map.of("transfers", List.of(), "procurements", List.of()),
                StateKeys.CARDS, List.of())));
        assertThat(meta(out).get("verify_passed")).isEqualTo(true);
    }
}
