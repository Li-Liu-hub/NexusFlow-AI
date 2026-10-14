package com.aismp.aismpagent.terminal.controller;

import com.aismp.aismpagent.terminal.common.BaseResponse;
import com.aismp.aismpagent.terminal.common.ResultUtils;
import com.aismp.aismpagent.terminal.model.entity.Warehouse;
import com.aismp.aismpagent.terminal.model.vo.AgingDistributionVO;
import com.aismp.aismpagent.terminal.model.vo.InboundCompareVO;
import com.aismp.aismpagent.terminal.model.vo.InboundSummaryVO;
import com.aismp.aismpagent.terminal.model.vo.InboundTrendVO;
import com.aismp.aismpagent.terminal.model.vo.InventorySnapshotVO;
import com.aismp.aismpagent.terminal.model.vo.TerminalInventoryVO;
import com.aismp.aismpagent.terminal.service.TerminalService;
import com.aismp.aismpagent.terminal.service.WarehouseScopeResolver;
import com.aismp.aismpagent.terminal.mapper.WarehouseMapper;
import com.aismp.aismpagent.terminal.task.StockAlertScanner;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 终端库存接口
 * <p>
 * 这组接口是**补货建议工作流的取数口**，供两类调用方使用：
 * 1. 编排层（Python）经网关签名调用 —— 作为 fetch 取数智能体的工具后端
 * 2. 前端数据看板直接调用
 * <p>
 * 全部是只读聚合查询，返回结构化数字，不做任何自然语言加工。
 * <p>
 * 实际 URL 前缀是 /api（backend 的 context-path），如
 * GET /api/terminal/inventory/aggregate
 * <p>
 * <b>数据可见范围（2026-10-08 收口"自报参数"遗留）</b>：门户通道（网关注入 X-User-* 头）下，
 * 显式传入的 warehouseCode 必须在可见范围（本仓 + 下级仓，口径同编排层 visible_scope）内，
 * 否则 40101；不带 warehouseCode 的取数默认收敛到本仓（aggregate 例外 —— 按范围过滤行，
 * 兼容门户首页的明细视图）。编排层 / 内核工具走 AK/SK 通道无注入头，保持原语义
 * （编排层取数要看到全量，可见范围过滤在编排层做）。
 * 已知边界：byModel / statCards / warehouseList 不带仓位参数（全省口径或组织元数据），
 * 不在本校验范围内。
 *
 * @author agent-platform
 */
@RestController
@RequestMapping("/terminal")
@Slf4j
public class TerminalController {

    @Resource
    private TerminalService terminalService;

    @Resource
    private WarehouseMapper warehouseMapper;

    @Resource
    private WarehouseScopeResolver scopeResolver;

    /** 库存告警扫描器（定时巡检 + 手动触发共用同一入口）。 */
    @Resource
    private StockAlertScanner stockAlertScanner;

    /**
     * 库存聚合：按 仓位 × 物料 × 型号
     *
     * @param warehouseCode 可选，限定仓位（门户通道：须在可见范围内；缺省 → 返回可见范围内的行）
     * @param model         可选，限定型号
     * @param bigType       可选，限定大类
     */
    @GetMapping("/inventory/aggregate")
    public BaseResponse<List<TerminalInventoryVO>> aggregate(
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String bigType,
            HttpServletRequest request) {
        Set<String> scope = portalScope(request);
        scopeResolver.requireInScope(warehouseCode, scope);
        List<TerminalInventoryVO> rows =
                terminalService.aggregateInventory(warehouseCode, model, bigType);
        if (scope != null && !StringUtils.hasText(warehouseCode)) {
            rows = filterInScope(rows, scope);
        }
        return ResultUtils.success(rows);
    }

    /**
     * 按型号跨仓位汇总 —— 判断"全省是否有货、是否只能采购"
     */
    @GetMapping("/inventory/byModel")
    public BaseResponse<List<TerminalInventoryVO>> byModel() {
        return ResultUtils.success(terminalService.aggregateByModel());
    }

    /**
     * 库龄分布
     *
     * @param warehouseCode 可选；门户通道缺省时取本仓（注入头），显式传入须在可见范围内
     */
    @GetMapping("/inventory/aging")
    public BaseResponse<List<AgingDistributionVO>> aging(
            @RequestParam(required = false) String warehouseCode,
            HttpServletRequest request) {
        Set<String> scope = portalScope(request);
        warehouseCode = scopedOrDefault(warehouseCode, scope, request);
        scopeResolver.requireInScope(warehouseCode, scope);
        return ResultUtils.success(terminalService.agingDistribution(warehouseCode));
    }

    /**
     * 库存快照 —— 一次取全，保证算法看到同一时刻的一致视图
     *
     * @param warehouseCode 可选；门户通道缺省时取本仓（注入头），显式传入须在可见范围内
     */
    @GetMapping("/inventory/snapshot")
    public BaseResponse<InventorySnapshotVO> snapshot(
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String model,
            HttpServletRequest request) {
        Set<String> scope = portalScope(request);
        warehouseCode = scopedOrDefault(warehouseCode, scope, request);
        scopeResolver.requireInScope(warehouseCode, scope);
        return ResultUtils.success(terminalService.buildSnapshot(warehouseCode, model));
    }

    /**
     * 首页四个统计卡片
     */
    @GetMapping("/stat/cards")
    public BaseResponse<Map<String, Object>> statCards() {
        return ResultUtils.success(terminalService.statCards());
    }

    /**
     * 仓位列表（按层级排序）
     */
    @GetMapping("/warehouse/list")
    public BaseResponse<List<Warehouse>> warehouseList() {
        return ResultUtils.success(warehouseMapper.listAllOrdered());
    }

    /**
     * 列某仓位某物料的可用串码。
     *
     * <p>用途有两个：
     * 1. 出库环节需要"按调拨单的数量/型号导入串码"，前端据此提供串码选择/导出模板
     * 2. 端到端测试与运维排查
     *
     * <p>排序按库龄倒序 —— 优先出长库龄的货，与"盘活长库龄"的业务目标一致。
     * <p>门户通道：warehouseCode 必须在可见范围内，否则 40101。
     */
    @GetMapping("/inventory/serialNos")
    public BaseResponse<List<String>> serialNos(
            @RequestParam String warehouseCode,
            @RequestParam String materialCode,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest request) {
        scopeResolver.requireInScope(warehouseCode, portalScope(request));
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return ResultUtils.success(
                terminalService.listAvailableSerialNos(warehouseCode, materialCode, safeLimit));
    }

    /**
     * 库存健康检查 + 跨仓调拨建议（2026-10-06）
     *
     * <p>门户「库存健康检查」按钮的取数口：返回本仓的健康度（库龄结构/型号覆盖）
     * 与「本仓缺 × 兄弟仓富余」的调拨建议清单 —— 建议直接可一键转成调拨工单。
     * 全部是确定性计算（开发文档 1.4 数字红线），不经过 LLM。
     * <p>门户通道：warehouseCode 必须在可见范围内，否则 40101。
     */
    @GetMapping("/health/suggest")
    public BaseResponse<com.aismp.aismpagent.terminal.model.vo.InventoryHealthVO> healthSuggest(
            @RequestParam String warehouseCode,
            HttpServletRequest request) {
        scopeResolver.requireInScope(warehouseCode, portalScope(request));
        return ResultUtils.success(terminalService.inventoryHealth(warehouseCode));
    }

    /**
     * 库存告警巡检（原子能力，2026-10-07 调整）。
     *
     * <p>只做「查 + 判定」，返回有调拨建议的仓清单（含通知草稿），**不写通知** ——
     * 定时节奏与通知投递由编排层（agent-orchestrator 的 alert 图）负责：
     * 编排层周期性/手动调本接口拿发现，再调 {@code POST /api/transferOrder/notice} 写通知。
     *
     * @param warehouseCode 可选；不传则扫描全部仓。
     *                      门户通道缺省时取本仓（与编排层 alert/scan 的注入头口径一致），
     *                      显式传入须在可见范围内
     * @return 扫描摘要（扫描仓数 + 发现清单）
     */
    @PostMapping("/stockAlert/scan")
    public BaseResponse<StockAlertScanner.ScanResult> stockAlertScan(
            @RequestParam(required = false) String warehouseCode,
            HttpServletRequest request) {
        Set<String> scope = portalScope(request);
        warehouseCode = scopedOrDefault(warehouseCode, scope, request);
        scopeResolver.requireInScope(warehouseCode, scope);
        return ResultUtils.success(stockAlertScanner.scan(warehouseCode));
    }

    // -------------------------------------------------------------------------
    // 入库量时间维度（2026-10-07 新增）：「查数助手」的月/年/日期工具后端
    //
    // 口径：按【首次入库时间 firstInboundAt】归桶 —— 历史视角，
    // 不随设备当前状态/仓位变化。全部只读 COUNT，数字红线不变。
    // 门户通道：显式仓须在可见范围内；缺省仓取本仓（注入头）——
    // 编排层/内核工具走 AK/SK 通道不受影响。
    // -------------------------------------------------------------------------

    /**
     * 入库量时间序列：月度趋势 / 年度对比 / 按日期查询
     *
     * @param granularity month（默认）/ year / day；day 必须给 from 与 to 且跨度 ≤ 366 天
     * @param from        起始日期（含）yyyy-MM-dd，可选
     * @param to          结束日期（含）yyyy-MM-dd，可选
     * @param groupBy     none（默认）/ warehouse 按仓附加分组 / model 按型号附加分组
     */
    @GetMapping("/inventory/inboundTrend")
    public BaseResponse<InboundTrendVO> inboundTrend(
            @RequestParam(required = false, defaultValue = "month") String granularity,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String bigType,
            @RequestParam(required = false, defaultValue = "none") String groupBy,
            HttpServletRequest request) {
        Set<String> scope = portalScope(request);
        warehouseCode = scopedOrDefault(warehouseCode, scope, request);
        scopeResolver.requireInScope(warehouseCode, scope);
        return ResultUtils.success(terminalService.inboundTrend(
                granularity, from, to, warehouseCode, model, bigType, groupBy));
    }

    /**
     * 指定月份的入库量对比（如 2025-06 与 2025-08）
     *
     * @param months 逗号分隔的 YYYY-MM，1~24 个；缺失月份补 0，升序返回
     */
    @GetMapping("/inventory/monthCompare")
    public BaseResponse<InboundCompareVO> monthCompare(
            @RequestParam String months,
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String bigType,
            HttpServletRequest request) {
        Set<String> scope = portalScope(request);
        warehouseCode = scopedOrDefault(warehouseCode, scope, request);
        scopeResolver.requireInScope(warehouseCode, scope);
        return ResultUtils.success(
                terminalService.inboundMonthsCompare(months, warehouseCode, model, bigType));
    }

    /**
     * 入库量汇总（不按时间分桶）：问"一共入库多少台"时用；可附带按仓/按型号分组明细
     *
     * @param groupBy none（默认，只出总量）/ warehouse / model
     */
    @GetMapping("/inventory/inboundSummary")
    public BaseResponse<InboundSummaryVO> inboundSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String bigType,
            @RequestParam(required = false, defaultValue = "none") String groupBy,
            HttpServletRequest request) {
        Set<String> scope = portalScope(request);
        warehouseCode = scopedOrDefault(warehouseCode, scope, request);
        scopeResolver.requireInScope(warehouseCode, scope);
        return ResultUtils.success(terminalService.inboundSummary(
                from, to, warehouseCode, model, bigType, groupBy));
    }

    // -------------------------------------------------------------------------
    // 门户可见范围辅助（2026-10-08，新方法置底）
    // -------------------------------------------------------------------------

    /**
     * 门户通道可见范围（本仓 + 下级仓）；非门户通道 / 无仓库归属的演示账号 → null = 不限制。
     */
    private Set<String> portalScope(HttpServletRequest request) {
        return scopeResolver.visibleFrom(request.getHeader("X-User-Warehouse"));
    }

    /**
     * 缺省仓收敛：门户通道（scope 非空）且未显式指定仓位 → 取注入头的本仓，
     * 与编排层 alert/scan、replenish/preview 的「门户通道以注入头为准」口径一致；
     * 非门户通道 / 演示账号原样返回（保持既有语义）。
     */
    private String scopedOrDefault(String requested, Set<String> scope, HttpServletRequest request) {
        if (scope != null && !StringUtils.hasText(requested)) {
            return request.getHeader("X-User-Warehouse");
        }
        return requested;
    }

    /** 按可见范围过滤行（行数据带 warehouseCode 才能过滤；不改变原顺序）。 */
    private List<TerminalInventoryVO> filterInScope(List<TerminalInventoryVO> rows, Set<String> scope) {
        List<TerminalInventoryVO> kept = new ArrayList<>();
        for (TerminalInventoryVO row : rows) {
            if (scope.contains(row.getWarehouseCode())) {
                kept.add(row);
            }
        }
        return kept;
    }
}
