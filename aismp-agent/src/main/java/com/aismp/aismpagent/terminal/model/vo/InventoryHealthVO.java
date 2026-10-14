package com.aismp.aismpagent.terminal.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 库存健康检查 + 调拨建议（门户「库存健康检查」按钮的返回）。
 *
 * <p>口径全部是确定性计算（开发文档 1.4 的"数字红线"）：
 * 健康度 = 库龄分布与型号覆盖；建议 = 「本仓低库存 × 兄弟仓富余」的配对，
 * 建议调入量取富余仓可用量的 1/10（上限 100、下限 10），不掺任何 LLM 估算。
 *
 * @author agent-platform
 */
@Data
public class InventoryHealthVO implements Serializable {

    private String warehouseCode;

    private String warehouseName;

    /** 总库存（含在途） */
    private Integer totalStock;

    /** 可用库存 */
    private Integer availableQty;

    /** 长库龄库存（库龄 ≥ 365 天） */
    private Integer longAgingQty;

    /** 长库龄占比 0~1（保留 3 位） */
    private Double longAgingRatio;

    /** 在库型号数 */
    private Integer modelCount;

    /** HEALTHY / WATCH / RISK */
    private String healthStatus;

    /** 一句话结论（确定性模板，不是 LLM 生成） */
    private String summary;

    /** 调拨建议（最多 5 条） */
    private List<Suggestion> suggestions;

    @Data
    public static class Suggestion implements Serializable {
        private String materialCode;
        private String model;
        private String materialName;
        private String bigType;

        /** 富余仓（调出方） */
        private String fromWarehouse;
        private String fromWarehouseName;
        private Integer availableAtFrom;
        private Double fromAvgAgingDays;

        /** 本仓（调入方） */
        private String toWarehouse;
        private String toWarehouseName;
        private Integer availableAtTo;

        /** 建议调入数量 */
        private Integer suggestQty;

        /** 建议理由（确定性文案） */
        private String reason;

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}