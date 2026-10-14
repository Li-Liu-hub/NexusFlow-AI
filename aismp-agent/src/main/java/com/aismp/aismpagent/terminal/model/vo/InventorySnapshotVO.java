package com.aismp.aismpagent.terminal.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 库存快照 —— 编排层 fetch 取数智能体的**单次取数返回**
 * <p>
 * 设计意图（对应开发文档 M2 的"取数智能体工具循环 ≤3 轮"）：
 * 与其让智能体分别调 3 个接口（库存聚合 / 库龄 / 在途），不如一次返回完整快照，
 * 既减少一轮工具调用，也保证算法节点看到的是**同一时刻的一致视图**
 * （分开查会在两次查询之间产生时间差，导致"可用+在途"对不上）。
 * <p>
 * 快照里所有数字都来自 SQL 聚合，编排层只做搬运，不做计算。
 *
 * @author agent-platform
 */
@Data
public class InventorySnapshotVO implements Serializable {

    /**
     * 快照生成时间
     */
    private String generatedAt;

    /**
     * 查询范围：仓位编码（可为空 = 全部仓位）
     */
    private String warehouseCode;

    /**
     * 查询范围：型号（可为空）
     */
    private String model;

    /**
     * 终端总台数
     */
    private Integer totalTerminals;

    /**
     * 可用总台数
     */
    private Integer totalAvailable;

    /**
     * 在途总台数
     */
    private Integer totalInTransit;

    /**
     * 长库龄台数（库龄 >= 180 天）
     */
    private Integer longAgingCount;

    /**
     * 按 仓位 × 物料 × 型号 的库存聚合
     */
    private List<TerminalInventoryVO> inventory;

    /**
     * 按仓位聚合的库龄分布
     */
    private List<AgingDistributionVO> aging;

    /**
     * 按型号跨仓位汇总（判断"全省是否有货"）
     */
    private List<TerminalInventoryVO> byModel;

    private static final long serialVersionUID = 1L;
}
