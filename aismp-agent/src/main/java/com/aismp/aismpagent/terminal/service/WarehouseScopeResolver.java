package com.aismp.aismpagent.terminal.service;

import com.aismp.aismpagent.terminal.common.ErrorCode;
import com.aismp.aismpagent.terminal.exception.BusinessException;
import com.aismp.aismpagent.terminal.mapper.WarehouseMapper;
import com.aismp.aismpagent.terminal.model.entity.Warehouse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 门户数据可见范围解析器（2026-10-08 收口 /api/terminal/** 与 transferOrder 查询的"自报参数"遗留）。
 *
 * <p>口径与编排层 {@code graph.visible_scope} 完全一致（同一规则两处实现是刻意的 ——
 * 编排层是取数后的展示过滤，本类是 agent 侧的访问控制，互为兜底）：
 * <b>本仓 + 所有下级仓</b>（warehouse.parentCode 递归）；无仓库归属的账号
 * （注入仓为空，演示账号）→ null = 不限制；层级取不到时按最严口径：只看本仓
 * （宁可少看，不可多看）。
 *
 * <p>只对「门户通道」生效 —— 编排层 / 内核工具经网关 AK/SK 通道调用时没有注入头，
 * 调用方传 null 即保持原语义（编排层取数要看到全量，可见范围过滤在编排层做）。
 *
 * @author agent-platform
 */
@Component
@Slf4j
public class WarehouseScopeResolver {

    @Resource
    private WarehouseMapper warehouseMapper;

    /**
     * 以 rootCode 为根算可见仓位集合（含自身与所有下级）；rootCode 为空 → null（不限制）。
     */
    public Set<String> visibleFrom(String rootCode) {
        if (!StringUtils.hasText(rootCode)) {
            return null;
        }
        List<Warehouse> rows;
        try {
            rows = warehouseMapper.listAllOrdered();
        } catch (Exception e) {
            log.warn("仓位层级查询失败，可见范围按最严口径（仅本仓 {}）", rootCode, e);
            return Set.of(rootCode);
        }
        Map<String, List<String>> children = new HashMap<>();
        for (Warehouse w : rows) {
            String parent = w.getParentCode() == null ? "" : w.getParentCode();
            String code = w.getWarehouseCode() == null ? "" : w.getWarehouseCode();
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(code);
        }
        Set<String> scope = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(rootCode);
        while (!stack.isEmpty()) {
            String code = stack.pop();
            if (!StringUtils.hasText(code) || !scope.add(code)) {
                continue;
            }
            List<String> kids = children.get(code);
            if (kids != null) {
                stack.addAll(kids);
            }
        }
        return scope;
    }

    /**
     * 校验请求仓在可见范围内（scope 为 null = 不限制，直接放行），越权抛 40101。
     */
    public void requireInScope(String requested, Set<String> scope) {
        if (scope == null || !StringUtils.hasText(requested)) {
            return;
        }
        if (!scope.contains(requested)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "请求仓位 " + requested + " 超出当前账号可见范围");
        }
    }
}
