package com.aismp.aismpagent.terminal.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.aismp.aismpagent.terminal.model.entity.Terminal;
import com.aismp.aismpagent.terminal.model.vo.AgingDistributionVO;
import com.aismp.aismpagent.terminal.model.vo.InboundCompareVO;
import com.aismp.aismpagent.terminal.model.vo.InboundSummaryVO;
import com.aismp.aismpagent.terminal.model.vo.InboundTrendVO;
import com.aismp.aismpagent.terminal.model.vo.InventoryHealthVO;
import com.aismp.aismpagent.terminal.model.vo.InventorySnapshotVO;
import com.aismp.aismpagent.terminal.model.vo.TerminalInventoryVO;

import java.util.List;

/**
 * 终端库存服务
 * <p>
 * 面向"补货建议"工作流的取数入口。所有方法的返回值都是**可计算的数字**，
 * 不含任何自然语言生成 —— 这是开发文档 1.4 的"数字红线"。
 *
 * @author agent-platform
 */
public interface TerminalService extends IService<Terminal> {

    /**
     * 库存聚合：按 仓位 × 物料 × 型号
     *
     * @param warehouseCode 可选
     * @param model         可选
     * @param bigType       可选
     */
    List<TerminalInventoryVO> aggregateInventory(String warehouseCode, String model, String bigType);

    /**
     * 按型号跨仓位汇总
     */
    List<TerminalInventoryVO> aggregateByModel();

    /**
     * 库龄分布
     *
     * @param warehouseCode 可选，为空则统计全部
     */
    List<AgingDistributionVO> agingDistribution(String warehouseCode);

    /**
     * 构建库存快照 —— 编排层 fetch 节点的单次取数入口
     *
     * @param warehouseCode 可选
     * @param model         可选
     */
    InventorySnapshotVO buildSnapshot(String warehouseCode, String model);

    /**
     * 取某仓位某物料下的可用串码（出库校验用）
     */
    List<String> listAvailableSerialNos(String warehouseCode, String materialCode, int limit);

    /**
     * 全量统计卡片的四个数字：库存总量 / 长库龄总量 / 昨日装机量 / 昨日回收入库量
     * <p>
     * 后两个依赖出库/回收流水，当前数据源（库存快照表）没有该维度，
     * 因此返回 null 并在前端显示为"—"，而不是编造一个数字。
     */
    java.util.Map<String, Object> statCards();

    /**
     * 库存健康检查 + 跨仓调拨建议（门户「库存健康检查」按钮）
     * <p>确定性计算：健康度看库龄结构，建议 = 本仓低库存 × 兄弟仓富余的配对。
     *
     * @param warehouseCode 本仓（调入方）
     */
    InventoryHealthVO inventoryHealth(String warehouseCode);

    // -------------------------------------------------------------------------
    // 入库量时间维度（2026-10-07 新增）：「查数助手」的月/年/日期工具后端
    // 口径：按【首次入库时间】归桶（历史视角），数字全部来自 SQL COUNT。
    // -------------------------------------------------------------------------

    /**
     * 入库量时间序列：按 月/年/日 分桶统计
     *
     * @param granularity month（默认）/ year / day；day 必须给 from 与 to 且跨度 ≤ 366 天
     * @param from        起始日期（含）YYYY-MM-DD，可空
     * @param to          结束日期（含）YYYY-MM-DD，可空
     * @param groupBy     none（默认）/ warehouse 按仓 / model 按型号
     */
    InboundTrendVO inboundTrend(String granularity, String from, String to,
                                String warehouseCode, String model, String bigType, String groupBy);

    /**
     * 指定月份的入库量对比（months 为逗号分隔的 YYYY-MM，1~24 个）
     */
    InboundCompareVO inboundMonthsCompare(String months, String warehouseCode,
                                          String model, String bigType);

    /**
     * 入库量汇总（不按时间分桶）：总量 + 可选分组明细
     */
    InboundSummaryVO inboundSummary(String from, String to, String warehouseCode,
                                    String model, String bigType, String groupBy);
}
