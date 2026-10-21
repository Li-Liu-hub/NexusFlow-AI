package com.aismp.orchestrator.service;

import com.aismp.orchestrator.client.TerminalApiClient;
import com.aismp.orchestrator.config.OrchestratorProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图表装配：取数 + 组装卡片 + 写一句确定性说明（note）。
 *
 * <p>五个内容维度：{@code warehouse / model / aging / stat / time}；
 * 四个展示样式：{@code bar / pie / line / table}（{@code stat} 恒为 metric 统计卡，
 * 四个总数没有分布形态，不参与样式切换）。
 *
 * <p><b>取数纪律</b>与补货路线一致：取数失败必须抛出、由节点转成人话；
 * 查到了但为空则如实说"没有可用于绘图的数据"，绝不渲染一张空图让人误以为库存为零。
 *
 * <p>数字全部是接口原值：这里只做「分组求和 / 取前 N / 合并尾巴」，不做任何推算。
 */
@Component
public class ChartBuilder {

    public static final int TOP_N = 10;
    public static final List<String> KINDS = List.of("warehouse", "aging", "model", "stat", "time");
    public static final List<String> STYLES = List.of("bar", "pie", "line", "table");

    /** 选型规则：命中即用，不烧 LLM（选型本质是 5×4 的分类，规则能覆盖绝大多数问法） */
    static final List<Map.Entry<String, List<String>>> KIND_HINTS = List.of(
            Map.entry("time", List.of("入库趋势", "入库走势", "各月入库", "月度入库", "入库对比",
                    "入库数据明细", "各年入库", "入库多少台", "入库量", "入库时间", "今年入库", "最近半年入库")),
            Map.entry("aging", List.of("库龄分布", "库龄区间", "库龄结构", "库龄")),
            Map.entry("model", List.of("各型号", "型号汇总", "型号分布", "型号库存", "型号构成", "型号排行", "按型号")),
            Map.entry("stat", List.of("总体情况", "整体统计", "库存总量", "总库存", "总台数", "整体情况", "库存统计")),
            Map.entry("warehouse", List.of("各仓", "仓位分布", "仓库分布", "库存分布", "仓位库存", "各仓位")));

    private static final Pattern YEARS = Pattern.compile("近(\\d+)年|最近(\\d+)年");
    private static final Pattern MONTHS = Pattern.compile("近(\\d+)\\s*个?月|最近(\\d+)\\s*个?月");

    private final TerminalApiClient api;
    private final OrchestratorProperties properties;

    public ChartBuilder(TerminalApiClient api, OrchestratorProperties properties) {
        this.api = api;
        this.properties = properties;
    }

    public record Result(Map<String, Object> card, String note) {
    }

    /** 规则选型：返回 kind，未命中返回 null */
    public static String kindByRule(String query) {
        String q = query == null ? "" : query;
        for (Map.Entry<String, List<String>> e : KIND_HINTS) {
            if (e.getValue().stream().anyMatch(q::contains)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 样式关键词：饼/占比 → pie，趋势/走势 → line，表格 → table，其余 bar */
    public static String styleByKeyword(String query) {
        String q = query == null ? "" : query;
        if (q.contains("饼") || q.contains("占比") || q.contains("构成")) {
            return "pie";
        }
        if (q.contains("趋势") || q.contains("走势")) {
            return "line";
        }
        if (q.contains("表格") || q.contains("明细") || q.contains("清单")) {
            return "table";
        }
        return null;
    }

    public Result build(String kind, String style, Set<String> scope, String query) {
        String k = kind == null || kind.isBlank() ? "stat" : kind;
        String s = style == null || style.isBlank() ? ("stat".equals(k) ? "metric" : "bar") : style;
        if ("time".equals(k)) {
            return timeChart(s, scope, query);
        }
        if ("stat".equals(k)) {
            return statChart(scope);
        }

        List<Map<String, Object>> rows = api.aggregate();
        String groupKey;
        String nameKey;
        String title;
        String series = "可用库存(台)";
        if ("warehouse".equals(k)) {
            groupKey = "warehouseCode";
            nameKey = "warehouseName";
            title = "各仓可用库存分布";
        } else if ("model".equals(k)) {
            groupKey = "model";
            nameKey = "model";
            title = "各型号可用库存汇总";
        } else {
            return agingChart(s, scope);
        }

        List<Map<String, Object>> scoped = filter(rows, scope);
        Agg agg = aggregateBy(scoped, groupKey, nameKey);
        List<String> labels = agg.names;
        List<Integer> data = agg.quantities;
        String unit = "warehouse".equals(k) ? "个仓位" : "个型号";
        StringBuilder note = new StringBuilder("共 %d %s，可用库存合计 %d 台"
                .formatted(agg.groups, unit, agg.total));
        if (!labels.isEmpty()) {
            note.append("，最高为 %s（%d 台）".formatted(labels.get(0), data.get(0)));
        }
        if (scope != null) {
            note.append("（数据范围：本仓及下级仓）");
        }
        if ("line".equals(s)) {
            note.append("（折线为按值降序的分布走势，非时间序列）");
        }
        List<String> mergedLabels = labels;
        List<Integer> mergedData = data;
        if (agg.groups > labels.size()) {
            int rest = agg.total - data.stream().mapToInt(Integer::intValue).sum();
            if (rest > 0) {
                mergedLabels = new ArrayList<>(labels);
                mergedData = new ArrayList<>(data);
                mergedLabels.add("其他");
                mergedData.add(rest);
            }
        }
        return new Result(card(title, s, mergedLabels, mergedData, note.toString(), series), note.toString());
    }

    private Result statChart(Set<String> scope) {
        Map<String, Object> snapshot = api.snapshot();
        long totalTerminals = InventoryRow.i(snapshot.get("totalTerminals"));
        long totalAvailable = InventoryRow.i(snapshot.get("totalAvailable"));
        long totalInTransit = InventoryRow.i(snapshot.get("totalInTransit"));
        long longAging = InventoryRow.i(snapshot.get("longAgingCount"));
        if (scope != null) {
            // 有可见范围时按明细重算三个量（快照聚合里没有仓位维度，直接过滤不了）
            List<Map<String, Object>> scoped = filter(api.aggregate(), scope);
            totalTerminals = scoped.stream().mapToLong(r -> InventoryRow.i(r.get("totalQty"))).sum();
            totalAvailable = scoped.stream().mapToLong(r -> InventoryRow.i(r.get("availableQty"))).sum();
            totalInTransit = scoped.stream().mapToLong(r -> InventoryRow.i(r.get("inTransitQty"))).sum();
        }
        List<String> labels = List.of("终端总数", "可用", "在途", "长库龄");
        List<Integer> data = List.of((int) totalTerminals, (int) totalAvailable,
                (int) totalInTransit, (int) longAging);
        String note = "终端总数 %d 台，可用 %d 台，在途 %d 台，长库龄 %d 台%s"
                .formatted(totalTerminals, totalAvailable, totalInTransit, longAging,
                        scope == null ? "" : "（长库龄为全量口径）");
        return new Result(card("库存整体统计", "metric", labels, data, note, "台数"), note);
    }

    private Result agingChart(String style, Set<String> scope) {
        List<Map<String, Object>> rows = new ArrayList<>(api.aging());
        rows.sort(Comparator.comparingInt(r -> InventoryRow.i(r.get("sortNo"))));
        List<String> labels = rows.stream().map(r -> InventoryRow.str(r.get("band"))).toList();
        List<Integer> data = rows.stream().map(r -> InventoryRow.i(r.get("cnt"))).toList();
        int total = data.stream().mapToInt(Integer::intValue).sum();
        int longAging = 0;
        for (int i = 0; i < labels.size(); i++) {
            if ("180-365".equals(labels.get(i)) || "365+".equals(labels.get(i))) {
                longAging += data.get(i);
            }
        }
        String note = "共 %d 台在统计范围内，其中 180 天以上长库龄 %d 台".formatted(total, longAging);
        if (scope != null) {
            note += "（库龄为全量口径，暂未按仓位细分）";
        }
        return new Result(card("终端库龄分布", "metric".equals(style) ? "bar" : style, labels, data, note, "台数"), note);
    }

    /** 入库时间序列：labels=时间桶（升序）、data=各桶入库台数 */
    private Result timeChart(String style, Set<String> scope, String query) {
        String[] range = timeRange(query);   // [granularity, from, to]
        Map<String, Object> trend = api.inboundTrend(range[0], range[1], range[2],
                scope == null ? "none" : "warehouse");
        List<Map<String, Object>> rows = trend.get("rows") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();

        List<String> periods;
        List<Integer> data;
        long total;
        if (scope != null) {
            Map<String, Integer> agg = new TreeMap<>();
            for (Map<String, Object> r : rows) {
                if (scope.contains(InventoryRow.str(r.get("groupKey")))) {
                    agg.merge(InventoryRow.str(r.get("period")), InventoryRow.i(r.get("cnt")), Integer::sum);
                }
            }
            periods = new ArrayList<>(agg.keySet());
            data = periods.stream().map(agg::get).toList();
            total = data.stream().mapToInt(Integer::intValue).sum();
        } else {
            periods = rows.stream().map(r -> InventoryRow.str(r.get("period"))).toList();
            data = rows.stream().map(r -> InventoryRow.i(r.get("cnt"))).toList();
            total = InventoryRow.i(trend.getOrDefault("totalCnt", data.stream().mapToInt(Integer::intValue).sum()));
        }
        String unit = "year".equals(range[0]) ? "年" : "月";
        String title = "入库量趋势（按%s）".formatted(unit);
        StringBuilder note = new StringBuilder("统计范围 %s ~ %s，共入库 %d 台（%d 个%s，按首次入库时间归桶）"
                .formatted(range[1] == null || range[1].isBlank() ? "最早" : range[1],
                        range[2] == null || range[2].isBlank() ? "今" : range[2],
                        total, periods.size(), unit));
        if (!data.isEmpty()) {
            int peak = data.stream().mapToInt(Integer::intValue).max().orElse(0);
            note.append("，最高为 %s（%d 台）".formatted(periods.get(data.indexOf(peak)), peak));
        }
        if (scope != null) {
            note.append("（数据范围：本仓及下级仓）");
        }
        return new Result(card(title, style, periods, data, note.toString(), "入库台数"), note.toString());
    }

    /**
     * 从话术里解析时间范围：只认高置信词，未命中默认「最近 6 个月」按月分桶。
     *
     * <p>为什么用规则而不是 LLM：范围解析是可判定的规则活，放进提示词只会引入不确定性。
     * 中文数字按单字归一（近三年→近3年），「半年」按 6 个月折算。
     */
    static String[] timeRange(String query) {
        String q = query == null ? "" : query.trim();
        q = q.replace("半年", "6个月");
        StringBuilder norm = new StringBuilder();
        for (char ch : q.toCharArray()) {
            norm.append(switch (ch) {
                case '一' -> "1";
                case '两', '二' -> "2";
                case '三' -> "3";
                case '四' -> "4";
                case '五' -> "5";
                case '六' -> "6";
                case '七' -> "7";
                case '八' -> "8";
                case '九' -> "9";
                default -> String.valueOf(ch);
            });
        }
        String n = norm.toString();
        LocalDate today = LocalDate.now();
        int year = today.getYear();

        Matcher my = YEARS.matcher(n);
        boolean byYear = my.find() || n.contains("按年") || n.contains("年度") || n.contains("各年");
        if (byYear) {
            int count = my.reset().find() ? Integer.parseInt(my.group(1) != null ? my.group(1) : my.group(2)) : 3;
            return new String[]{"year", (year - count + 1) + "-01-01", today.toString()};
        }
        if (n.contains("去年")) {
            return new String[]{"month", (year - 1) + "-01-01", (year - 1) + "-12-31"};
        }
        if (n.contains("今年")) {
            return new String[]{"month", year + "-01-01", today.toString()};
        }
        Matcher mm = MONTHS.matcher(n);
        if (mm.find()) {
            int count = Integer.parseInt(mm.group(1) != null ? mm.group(1) : mm.group(2));
            return new String[]{"month", shiftMonths(today, count - 1).toString(), today.toString()};
        }
        return new String[]{"month", shiftMonths(today, 5).toString(), today.toString()};
    }

    static LocalDate shiftMonths(LocalDate d, int n) {
        int total = d.getYear() * 12 + (d.getMonthValue() - 1) - n;
        return LocalDate.of(total / 12, total % 12 + 1, 1);
    }

    private static List<Map<String, Object>> filter(List<Map<String, Object>> rows, Set<String> scope) {
        if (scope == null) {
            return rows;
        }
        return rows.stream().filter(r -> scope.contains(InventoryRow.str(r.get("warehouseCode")))).toList();
    }

    private record Agg(List<String> names, List<Integer> quantities, int groups, int total) {
    }

    /** 按 key 聚合求和，取前 TOP_N（其余在调用方合并为「其他」） */
    private static Agg aggregateBy(List<Map<String, Object>> rows, String key, String nameKey) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Integer> sums = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String k = InventoryRow.str(r.get(key));
            if (k.isEmpty()) {
                continue;
            }
            names.putIfAbsent(k, InventoryRow.str(r.get(nameKey)).isEmpty() ? k : InventoryRow.str(r.get(nameKey)));
            sums.merge(k, InventoryRow.i(r.get("availableQty")), Integer::sum);
        }
        List<Map.Entry<String, Integer>> ordered = sums.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .toList();
        List<String> labels = new ArrayList<>();
        List<Integer> data = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ordered.stream().limit(TOP_N).toList()) {
            labels.add(names.get(e.getKey()));
            data.add(e.getValue());
        }
        int total = sums.values().stream().mapToInt(Integer::intValue).sum();
        return new Agg(labels, data, sums.size(), total);
    }

    private static Map<String, Object> card(String title, String chartType, List<String> labels,
                                            List<Integer> data, String note, String seriesName) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "chart");
        card.put("chartType", chartType);
        card.put("title", title);
        card.put("labels", labels);
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", seriesName);
        series.put("data", data);
        card.put("series", List.of(series));
        card.put("note", note);
        return card;
    }

    /** 已知仓编码（配置项） */
    public Set<String> knownWarehouses() {
        return new LinkedHashSet<>(properties.getTerminal().warehouseList());
    }
}
