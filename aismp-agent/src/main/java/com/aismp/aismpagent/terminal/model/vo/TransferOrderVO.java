package com.aismp.aismpagent.terminal.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 调拨单视图对象（含明细）—— 对应前端"调拨单卡片"
 *
 * @author agent-platform
 */
@Data
public class TransferOrderVO implements Serializable {

    private String orderNo;

    private String operationType;

    private String fromWarehouse;

    private String fromWarehouseName;

    private String toWarehouse;

    private String toWarehouseName;

    private String status;

    /**
     * 状态中文标签（前端直接显示，避免各端各写一套映射）
     */
    private String statusLabel;

    /**
     * 当前"该谁动"的提示（如「待调出方账管确认」），终态为空
     */
    private String pendingAction;

    private Integer itemCount;

    private Integer totalQuantity;

    private Integer needApproval;

    private String creator;

    private String rejectReason;

    private String source;

    private String suggestionId;

    private String createdAt;

    private String outboundAt;

    private String inboundAt;

    /* ------ 账管/仓管工单闭环的时间线与操作人 ------ */

    private String sentAt;

    private String accountConfirmedAt;

    private String keeperConfirmedAt;

    private String senderUser;

    private String accountConfirmer;

    private String keeperConfirmer;

    /**
     * 已完成调拨的串码总数（仓管确认后回填，前端展示"实际调拨 N 台"）
     */
    private Integer movedQuantity;

    /**
     * 明细行
     */
    private List<Line> items;

    @Data
    public static class Line implements Serializable {
        private String materialCode;
        private String model;
        private String materialName;
        private Integer quantity;
        /**
         * 已出库的串码（出库后填充，供入库比对照抄）
         */
        private List<String> serialNos;

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}
