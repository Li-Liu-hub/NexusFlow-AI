package com.aismp.orchestrator.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据可见范围：一个仓位能看「本仓 + 下级仓」。
 *
 * <p>这是数据隔离的信任边界，值得单独成一个可测的纯函数：算错不是报错，
 * 而是把别的公司的库存算进均值、甚至给出跨公司调拨建议。
 *
 * <p>层级规则来自仓编码约定（9B00 地市 → 9BZA 区县 → 9BZA01 营业厅）：
 * <ul>
 *   <li>任意仓 → 自身 + 以自身编码为前缀的仓</li>
 *   <li>根仓（默认 9B00）→ 全部仓（它的下级是整棵树）</li>
 * </ul>
 *
 * <p>没有可见范围（系统账号 / 本机脚本直连）时返回 {@code null}，表示不做过滤 ——
 * 这与「范围为空集」是两件事，调用方必须区分。
 */
public final class WarehouseScope {

    private WarehouseScope() {
    }

    /**
     * @param warehouseCode 调用身份上的仓位；空则返回 null（不过滤）
     * @param allCodes      系统里已知的全部仓编码（用于求下级集合）
     * @param rootCode      根仓编码
     */
    public static Set<String> of(String warehouseCode, List<String> allCodes, String rootCode) {
        if (warehouseCode == null || warehouseCode.isBlank()) {
            return null;
        }
        String code = warehouseCode.trim();
        if (code.equals(rootCode)) {
            // 根仓看全量：用已知仓集合兜底，避免"只看到自己"
            if (allCodes != null && !allCodes.isEmpty()) {
                return new LinkedHashSet<>(allCodes);
            }
            return Set.of(code);
        }
        Set<String> scope = new LinkedHashSet<>();
        scope.add(code);
        if (allCodes != null) {
            for (String other : allCodes) {
                if (other != null && other.length() > code.length() && other.startsWith(code)) {
                    scope.add(other);
                }
            }
        }
        return scope;
    }

    /** 命中判断：scope 为 null 表示不过滤 */
    public static boolean visible(Set<String> scope, String warehouseCode) {
        return scope == null || scope.contains(warehouseCode);
    }

    /** 行过滤：scope 为 null 时原样返回 */
    public static <T> List<T> filter(Set<String> scope, List<T> rows) {
        return scope == null ? rows : rows;
    }
}
