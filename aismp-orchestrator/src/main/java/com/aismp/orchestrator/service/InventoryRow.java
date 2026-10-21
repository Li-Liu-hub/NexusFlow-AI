package com.aismp.orchestrator.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存聚合行（与后端 `/api/terminal/inventory/aggregate` 返回字段对齐）。
 *
 * <p>为什么用可变类而不是 record：核心算法要按「仓位 × 型号」**就地累加**（数量求和、
 * 库龄按量加权），用不可变对象会写出大量 map 更新代码，反而更容易错。
 *
 * <p>这也是「数字红线」的输入侧契约 —— 算法只用这里的字段算数，不碰 LLM 输出。
 */
public class InventoryRow {

    public String warehouseCode = "";
    public String warehouseName = "";
    public int warehouseLevel;
    public String materialCode = "";
    public String model = "";
    public String materialName = "";
    public String vendor = "";
    public String bigType = "";
    public int availableQty;
    public int totalQty;
    public int inTransitQty;
    public double avgAgingDays;
    public int maxAgingDays;
    /** 同仓同型号下的物料明细（建单需要物料粒度，算法比较用仓粒度，两级靠它衔接） */
    public final List<Map<String, Object>> materials = new ArrayList<>();

    public static InventoryRow fromMap(Map<String, Object> d) {
        InventoryRow r = new InventoryRow();
        r.warehouseCode = str(d.get("warehouseCode"));
        r.warehouseName = str(d.get("warehouseName"));
        r.warehouseLevel = i(d.get("warehouseLevel"));
        r.materialCode = str(d.get("materialCode"));
        r.model = str(d.get("model"));
        r.materialName = str(d.get("materialName"));
        r.vendor = str(d.get("vendor"));
        r.bigType = str(d.get("bigType"));
        r.availableQty = i(d.get("availableQty"));
        r.totalQty = i(d.get("totalQty"));
        r.inTransitQty = i(d.get("inTransitQty"));
        r.avgAgingDays = f(d.get("avgAgingDays"));
        r.maxAgingDays = i(d.get("maxAgingDays"));
        return r;
    }

    public Map<String, Object> toMaterial() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("materialCode", materialCode);
        m.put("materialName", materialName);
        m.put("bigType", bigType);
        m.put("availableQty", availableQty);
        return m;
    }

    public static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    public static int i(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static double f(Object v) {
        if (v == null) {
            return 0d;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
