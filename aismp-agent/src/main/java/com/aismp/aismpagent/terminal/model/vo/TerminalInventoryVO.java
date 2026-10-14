package com.aismp.aismpagent.terminal.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 库存聚合结果（按 仓位 × 物料 × 型号 聚合）
 * <p>
 * 这是补货算法的输入口径之一：编排层的 fetch 节点拿到它，算法节点据此算缺口与水位线。
 * 所有数字都来自 SQL 聚合，大模型不参与计算。
 *
 * @author agent-platform
 */
@Data
public class TerminalInventoryVO implements Serializable {

    /**
     * 仓位编码
     */
    private String warehouseCode;

    /**
     * 仓位名称
     */
    private String warehouseName;

    /**
     * 仓位层级：2=地市 3=区县 4=网格
     */
    private Integer warehouseLevel;

    /**
     * 终端大类
     */
    private String bigType;

    /**
     * 物料编码
     */
    private String materialCode;

    /**
     * 规格型号
     */
    private String model;

    /**
     * 物料名称
     */
    private String materialName;

    /**
     * 厂商（取 terminal.vendor，如 华为/中兴/烽火）
     * 2026-10-12 新增：采购清单表需要展示「厂商」列
     */
    private String vendor;

    /**
     * 可用数量（terminalStatus = '可用'）
     */
    private Integer availableQty;

    /**
     * 该仓位该型号的总数量
     */
    private Integer totalQty;

    /**
     * 在途数量（terminalStatus = '在途'）
     */
    private Integer inTransitQty;

    /**
     * 平均库龄（天）
     */
    private Double avgAgingDays;

    /**
     * 最大库龄（天）
     */
    private Integer maxAgingDays;

    private static final long serialVersionUID = 1L;
}
