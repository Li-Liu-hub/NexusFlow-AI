package com.aismp.orchestrator.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 确定性补货算法工具箱 —— 「算法即工具」的落地。
 *
 * <p><b>设计原则</b>：LLM 只决定「跑哪几种分析、传什么参数」，所有数字都在这里算。
 * 本类纯函数、无 IO、无随机，可独立单测 —— 这样"能幻觉的只有选择，不能幻觉事实"。
 *
 * <p><b>口径</b>：开发文档 B 附录给的是报表口径（周使用量 → 水位线 → 缺口），
 * 需要「领用/出库流水」。当前数据源只有库存快照、没有历史流水，所以默认启用
 * <b>分布口径</b>（{@link #plan}）：只用库存分布与库龄，算出来的都是可执行的真实数字。
 * 报表口径的三个函数照样保留，补上流水表即可切换。
 *
 * <p>本类是原 Python 版 {@code tools/algorithms.py} 的逐条移植，
 * 差异只有一处：{@code need} 的四舍五入用 {@link Math#round}（Python 的 round 是银行家舍入）。
 */
public final class ReplenishAlgorithm {

    private ReplenishAlgorithm() {
    }

    // ---- 可调参数（对应"参数配置"菜单的语义）----
    public static final int SAFETY_WEEKS_DEFAULT = 4;
    public static final int LONG_AGING_DAYS = 180;
    public static final int HIGH_AGING_DAYS = 365;
    /** 可用量低于同级均值的该比例 → 短缺 */
    public static final double LOW_STOCK_RATIO = 0.6;
    /** 可用量高于同级均值的该比例 → 富余 */
    public static final double SURPLUS_RATIO = 1.4;

    public static final String PRIORITY_URGENT = "紧急补货";
    public static final String PRIORITY_NORMAL = "建议补货";

    public static final String STRATEGY_TRANSFER_ONLY = "transfer_only";
    public static final String STRATEGY_PROCURE_ONLY = "procure_only";
    public static final String STRATEGY_MIXED = "mixed";

    /** 一条调拨建议（对应前端「建议调拨补货清单表」的一行） */
    public record Transfer(String model, String materialCode, String materialName, String bigType,
                           String fromWarehouse, String fromWarehouseName,
                           String toWarehouse, String toWarehouseName,
                           int quantity, String priority, String reason, String operationType,
                           List<Map<String, Object>> materials) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", model);
            m.put("materialCode", materialCode);
            m.put("materialName", materialName);
            m.put("bigType", bigType);
            m.put("fromWarehouse", fromWarehouse);
            m.put("fromWarehouseName", fromWarehouseName);
            m.put("toWarehouse", toWarehouse);
            m.put("toWarehouseName", toWarehouseName);
            m.put("quantity", quantity);
            m.put("priority", priority);
            m.put("reason", reason);
            m.put("operationType", operationType);
            m.put("materials", materials);
            return m;
        }
    }

    /** 一条采购建议（对应前端「建议采购补货清单表」的一行） */
    public record Procurement(String model, String materialCode, String materialName, String bigType,
                              String suggestedBrand, int suggestedQty, String priority,
                              String reason, String toWarehouse, String vendor) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("model", model);
            m.put("materialCode", materialCode);
            m.put("materialName", materialName);
            m.put("bigType", bigType);
            m.put("suggestedBrand", suggestedBrand);
            m.put("suggestedQty", suggestedQty);
            m.put("priority", priority);
            m.put("reason", reason);
            m.put("toWarehouse", toWarehouse);
            m.put("vendor", vendor);
            return m;
        }
    }

    /** 补货方案（算法节点的完整输出） */
    public record Plan(String strategy, List<Transfer> transfers, List<Procurement> procurements,
                       List<String> trace) {

        public Plan withStrategy(String s) {
            return new Plan(s, transfers, procurements, trace);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategy", strategy);
            m.put("transfers", transfers.stream().map(Transfer::toMap).toList());
            m.put("procurements", procurements.stream().map(Procurement::toMap).toList());
            m.put("trace", trace);
            return m;
        }
    }

    // ------------------------------------------------------------------
    // 报表口径（需要用量流水，当前数据源不可用，接口先留好）
    // ------------------------------------------------------------------

    /** 周使用量 = (近三月使用总量 ÷ 天数) × 7 */
    public static double weeklyUsage(int recentTotal, int days) {
        if (days <= 0) {
            return 0d;
        }
        return Math.round(recentTotal * 7.0 / days * 100) / 100.0;
    }

    /** 水位线 = 周使用量 × 安全周数 */
    public static double waterline(double weekly, int safetyWeeks) {
        return Math.round(weekly * safetyWeeks * 100) / 100.0;
    }

    /** 缺口 = 预测需求 − (可用 + 在途)，负数归零 */
    public static int gap(int predictedDemand, int available, int inTransit) {
        return Math.max(0, predictedDemand - available - inTransit);
    }

    // ------------------------------------------------------------------
    // 分布口径
    // ------------------------------------------------------------------

    /**
     * 把「仓位 × 物料」粒度聚合成「仓位 × 型号」粒度。
     *
     * <p>为什么必须聚合：快照按「仓位 × 物料 × 型号」分组返回，同一型号有多个物料编码时
     * 同仓会返回多行。不聚合的话：① 同一仓以多行身份重复进入 short/surplus，产出
     * from/to/model 全同的重复建议；② 均值被物料行数加权（仓的权重变成物料行数）。
     * 聚合规则：数量求和、库龄按总量加权、物料明细留在 materials 供建单拆分、
     * 主物料取可用量最大的那个。
     */
    public static List<InventoryRow> aggregateByWarehouseModel(List<InventoryRow> rows) {
        Map<String, InventoryRow> merged = new LinkedHashMap<>();
        for (InventoryRow r : rows) {
            if (r.model == null || r.model.isEmpty()) {
                continue;
            }
            String key = r.warehouseCode + "|" + r.model;
            InventoryRow agg = merged.get(key);
            if (agg == null) {
                agg = new InventoryRow();
                agg.warehouseCode = r.warehouseCode;
                agg.warehouseName = r.warehouseName;
                agg.warehouseLevel = r.warehouseLevel;
                agg.materialCode = r.materialCode;
                agg.model = r.model;
                agg.materialName = r.materialName;
                // 聚合行是新建对象：vendor/bigType 不显式带过来就会在这一步静默丢失
                agg.vendor = r.vendor;
                agg.bigType = r.bigType;
                agg.availableQty = r.availableQty;
                agg.totalQty = r.totalQty;
                agg.inTransitQty = r.inTransitQty;
                agg.avgAgingDays = r.avgAgingDays;
                agg.maxAgingDays = r.maxAgingDays;
                if (!r.materialCode.isEmpty()) {
                    agg.materials.add(r.toMaterial());
                }
                merged.put(key, agg);
                continue;
            }
            int w = agg.totalQty + r.totalQty;
            agg.avgAgingDays = w > 0
                    ? Math.round((agg.avgAgingDays * agg.totalQty + r.avgAgingDays * r.totalQty) / w * 10) / 10.0
                    : Math.max(agg.avgAgingDays, r.avgAgingDays);
            agg.availableQty += r.availableQty;
            agg.totalQty += r.totalQty;
            agg.inTransitQty += r.inTransitQty;
            agg.maxAgingDays = Math.max(agg.maxAgingDays, r.maxAgingDays);
            if (!r.materialCode.isEmpty()) {
                agg.materials.add(r.toMaterial());
            }
        }
        for (InventoryRow agg : merged.values()) {
            if (!agg.materials.isEmpty()) {
                Map<String, Object> dom = agg.materials.stream()
                        .max(Comparator.comparingInt(m -> InventoryRow.i(m.get("availableQty"))))
                        .orElseThrow();
                agg.materialCode = InventoryRow.str(dom.get("materialCode"));
                agg.materialName = InventoryRow.str(dom.get("materialName"));
                agg.bigType = InventoryRow.str(dom.get("bigType"));
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 把调拨量拆到具体物料上 —— 按<b>调出仓实有</b>加权，逐物料钳制上限。
     *
     * <p>为什么看调出仓：只按调入仓的物料结构分摊，会给调出仓没有的物料也分量，
     * 后端逐物料库存校验必然拒绝，整单建不出来。规则：只分两边都有的物料 →
     * 按调出仓实有加权 → 单物料不超过调出仓实有 → 余量按实有从大到小补齐（守恒）。
     * 两边没有共同物料 → 空表，调用方跳过（物理上无法履约）。
     */
    public static List<Map<String, Object>> splitByMaterials(int qty, List<Map<String, Object>> srcMaterials,
                                                             List<Map<String, Object>> tgtMaterials) {
        Map<String, Integer> have = new LinkedHashMap<>();
        for (Map<String, Object> m : srcMaterials == null ? List.<Map<String, Object>>of() : srcMaterials) {
            String code = InventoryRow.str(m.get("materialCode"));
            if (!code.isEmpty()) {
                have.merge(code, InventoryRow.i(m.get("availableQty")), Integer::sum);
            }
        }
        List<Map<String, Object>> common = new ArrayList<>();
        for (Map<String, Object> m : tgtMaterials == null ? List.<Map<String, Object>>of() : tgtMaterials) {
            String code = InventoryRow.str(m.get("materialCode"));
            if (have.getOrDefault(code, 0) > 0) {
                common.add(m);
            }
        }
        if (common.isEmpty()) {
            return List.of();
        }
        int totalW = common.stream().mapToInt(m -> have.get(InventoryRow.str(m.get("materialCode")))).sum();
        if (totalW <= 0) {
            return List.of();
        }
        Map<String, Map<String, Object>> parts = new LinkedHashMap<>();
        for (Map<String, Object> m : common) {
            String code = InventoryRow.str(m.get("materialCode"));
            int give = Math.min(qty * have.get(code) / totalW, have.get(code));
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("materialCode", code);
            part.put("materialName", InventoryRow.str(m.get("materialName")));
            part.put("bigType", InventoryRow.str(m.get("bigType")));
            part.put("quantity", give);
            parts.put(code, part);
        }
        int rest = qty - parts.values().stream().mapToInt(p -> InventoryRow.i(p.get("quantity"))).sum();
        List<Map<String, Object>> ordered = common.stream()
                .sorted(Comparator.comparingInt((Map<String, Object> m) -> have.get(InventoryRow.str(m.get("materialCode")))).reversed())
                .toList();
        for (Map<String, Object> m : ordered) {
            if (rest <= 0) {
                break;
            }
            String code = InventoryRow.str(m.get("materialCode"));
            Map<String, Object> p = parts.get(code);
            int add = Math.min(rest, have.get(code) - InventoryRow.i(p.get("quantity")));
            if (add > 0) {
                p.put("quantity", InventoryRow.i(p.get("quantity")) + add);
                rest -= add;
            }
        }
        return parts.values().stream().filter(p -> InventoryRow.i(p.get("quantity")) > 0).toList();
    }

    /**
     * 同层优先：拿同级仓位的分布做比较，避免拿省仓和网格仓直接比。
     * 同级少于两个仓位时回退用全部行 —— 否则均值等于自身，会产出「自己调给自己」。
     */
    static List<InventoryRow> matchLevel(List<InventoryRow> rows, int level) {
        List<InventoryRow> same = rows.stream().filter(r -> r.warehouseLevel == level).toList();
        return same.size() >= 2 ? same : rows;
    }

    /**
     * 基于库存分布生成补货方案（逐型号）：
     * <ol>
     *   <li>聚合成「仓位 × 型号」粒度</li>
     *   <li>按<b>同层级</b>算该型号可用量均值</li>
     *   <li>可用 &lt; 均值×0.6 → 短缺；可用 &gt; 均值×1.4 且 &gt;0 → 富余</li>
     *   <li>富余仓按缺口从小到大填补短缺仓，可调出量随调出递减（不重复承诺）</li>
     *   <li>填不满的部分转采购</li>
     * </ol>
     * 返回的每个数字都直接来自入参，不做任何推测性放大。
     */
    public static Plan plan(List<InventoryRow> rawRows, int safetyWeeks, int maxTransferPerModel) {
        List<String> trace = new ArrayList<>();
        List<InventoryRow> rows = aggregateByWarehouseModel(rawRows);
        Map<String, List<InventoryRow>> grouped = new TreeMap<>();
        for (InventoryRow r : rows) {
            grouped.computeIfAbsent(r.model, k -> new ArrayList<>()).add(r);
        }

        List<Transfer> transfers = new ArrayList<>();
        List<Procurement> procurements = new ArrayList<>();

        for (Map.Entry<String, List<InventoryRow>> e : grouped.entrySet()) {
            String model = e.getKey();
            List<InventoryRow> group = e.getValue();
            int level = group.get(0).warehouseLevel;
            List<InventoryRow> peers = matchLevel(group, level);
            if (peers.size() < 2) {
                // 只有一个仓位供该型号：谈不上仓间调剂，不产出建议（比编一条假建议诚实）
                continue;
            }
            double avg = peers.stream().mapToInt(r -> r.availableQty).average().orElse(0);
            if (avg <= 0) {
                continue;
            }
            List<InventoryRow> shortOnes = peers.stream()
                    .filter(r -> r.availableQty < avg * LOW_STOCK_RATIO).toList();
            List<InventoryRow> surplus = peers.stream()
                    .filter(r -> r.availableQty > avg * SURPLUS_RATIO && r.availableQty > 0)
                    .sorted(Comparator.comparingDouble((InventoryRow r) -> r.avgAgingDays).reversed()
                            .thenComparing(Comparator.comparingInt((InventoryRow r) -> r.availableQty).reversed()))
                    .collect(Collectors.toList());
            if (shortOnes.isEmpty()) {
                continue;
            }
            Map<String, Integer> giveable = new LinkedHashMap<>();
            for (InventoryRow src : surplus) {
                giveable.put(src.warehouseCode, (int) (src.availableQty - avg));
            }
            List<InventoryRow> targets = shortOnes.stream()
                    .sorted(Comparator.comparingInt((InventoryRow r) -> r.availableQty))
                    .toList();

            for (InventoryRow target : targets) {
                int need = (int) Math.round(avg - target.availableQty);
                if (need <= 0) {
                    continue;
                }
                trace.add("%s@%s 可用 %d < 同级均值 %.1f，缺口 %d"
                        .formatted(model, target.warehouseCode, target.availableQty, avg, need));
                int remaining = need;
                int picked = 0;
                for (InventoryRow src : surplus) {
                    if (remaining <= 0 || picked >= maxTransferPerModel) {
                        break;
                    }
                    if (src.warehouseCode.equals(target.warehouseCode)) {
                        continue;   // 同仓不自调
                    }
                    int canGive = giveable.getOrDefault(src.warehouseCode, 0);
                    if (canGive <= 0) {
                        continue;
                    }
                    int qty = Math.min(canGive, remaining);
                    if (qty <= 0) {
                        continue;
                    }
                    List<String> reasonBits = new ArrayList<>();
                    reasonBits.add("可用 %d 高于同级均值 %.1f".formatted(src.availableQty, avg));
                    if (src.avgAgingDays >= LONG_AGING_DAYS) {
                        reasonBits.add("平均库龄 %.0f 天（长库龄，优先盘活）".formatted(src.avgAgingDays));
                    }
                    // 只保留上级→下级的调拨（业务规则：调拨由上级发起）
                    if (src.warehouseLevel == target.warehouseLevel || src.warehouseLevel > target.warehouseLevel) {
                        continue;
                    }
                    List<Map<String, Object>> alloc = splitByMaterials(qty, src.materials, target.materials);
                    if (alloc.isEmpty()) {
                        trace.add("  → 跳过 %s→%s（%s）：两边无共同物料，无法履约"
                                .formatted(src.warehouseCode, target.warehouseCode, model));
                        continue;
                    }
                    qty = alloc.stream().mapToInt(m -> InventoryRow.i(m.get("quantity"))).sum();
                    transfers.add(new Transfer(
                            model,
                            !src.materialCode.isEmpty() ? src.materialCode : target.materialCode,
                            !src.materialName.isEmpty() ? src.materialName : target.materialName,
                            !src.bigType.isEmpty() ? src.bigType : target.bigType,
                            src.warehouseCode, src.warehouseName,
                            target.warehouseCode, target.warehouseName,
                            qty,
                            target.availableQty == 0 ? PRIORITY_URGENT : PRIORITY_NORMAL,
                            String.join("；", reasonBits),
                            "TRANSFER",
                            alloc));
                    remaining -= qty;
                    giveable.put(src.warehouseCode, giveable.get(src.warehouseCode) - qty);
                    picked++;
                    trace.add("  → 从 %s 调出 %d 台到 %s".formatted(src.warehouseCode, qty, target.warehouseCode));
                }
                if (remaining > 0) {
                    String brand = pickBrand(peers);
                    procurements.add(new Procurement(model, target.materialCode, target.materialName,
                            target.bigType, brand, remaining, PRIORITY_URGENT,
                            "省内可调拨库存不足，缺口剩余 %d 台需采购".formatted(remaining),
                            target.warehouseCode, target.vendor));
                    trace.add("  → %s 剩余缺口 %d 台转采购（建议品牌 %s）"
                            .formatted(target.warehouseCode, remaining, brand));
                }
            }
        }

        boolean hasT = !transfers.isEmpty();
        boolean hasP = !procurements.isEmpty();
        String strategy = hasT && hasP ? STRATEGY_MIXED : hasP ? STRATEGY_PROCURE_ONLY : STRATEGY_TRANSFER_ONLY;
        trace.add(0, "算法：分布口径（安全周数参数 %d 仅留痕，未参与本口径计算）".formatted(safetyWeeks));
        trace.add("结果：调拨 %d 条 / 采购 %d 条 / 策略 %s".formatted(transfers.size(), procurements.size(), strategy));
        return new Plan(strategy, transfers, procurements, trace);
    }

    /**
     * 给采购建议挑建议品牌。
     *
     * <p>诚实说明：当前聚合结果没有厂家维度（terminal 表有 vendor，但聚合接口未按厂家分组），
     * 所以返回空串，由报表节点显示"待定"。不返回编造的品牌名。
     */
    static String pickBrand(List<InventoryRow> rows) {
        return "";
    }

    /** 品牌评分（0~100）：库龄越低越好（60%）、可用库存越充足越好（40%）。纯启发式。 */
    public static List<Map<String, Object>> scoreBrands(List<Map<String, Object>> vendorStats) {
        if (vendorStats == null || vendorStats.isEmpty()) {
            return List.of();
        }
        int maxQty = Math.max(1, vendorStats.stream()
                .mapToInt(v -> InventoryRow.i(v.get("availableQty"))).max().orElse(0));
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> v : vendorStats) {
            int qty = InventoryRow.i(v.get("availableQty"));
            double aging = InventoryRow.f(v.get("avgAgingDays"));
            double agingScore = Math.max(0, 100.0 * (1 - Math.min(aging, 365) / 365));
            double qtyScore = 100.0 * qty / maxQty;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("vendor", v.get("vendor"));
            item.put("availableQty", qty);
            item.put("avgAgingDays", aging);
            item.put("score", Math.round((agingScore * 0.6 + qtyScore * 0.4) * 10) / 10.0);
            scored.add(item);
        }
        scored.sort(Comparator.comparingDouble((Map<String, Object> m) -> InventoryRow.f(m.get("score"))).reversed());
        return scored;
    }

    /** 库龄摘要：把后端分桶结果压成算法节点需要的几个数字。 */
    public static Map<String, Object> summarizeAging(List<Map<String, Object>> agingRows) {
        int total = 0;
        int longAging = 0;
        Set<String> longBands = new LinkedHashSet<>(List.of("180-365", "365+"));
        for (Map<String, Object> row : agingRows == null ? List.<Map<String, Object>>of() : agingRows) {
            int cnt = InventoryRow.i(row.get("cnt"));
            total += cnt;
            if (longBands.contains(Objects.toString(row.get("band"), ""))) {
                longAging += cnt;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("longAging", longAging);
        out.put("longAgingRatio", total == 0 ? 0.0 : Math.round(longAging * 10000.0 / total) / 10000.0);
        return out;
    }
}
