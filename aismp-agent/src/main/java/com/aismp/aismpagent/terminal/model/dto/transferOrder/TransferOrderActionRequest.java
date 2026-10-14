package com.aismp.aismpagent.terminal.model.dto.transferOrder;

import lombok.Data;

import java.io.Serializable;

/**
 * 调拨单动作请求（入库确认 / 拒绝）
 *
 * @author agent-platform
 */
@Data
public class TransferOrderActionRequest implements Serializable {

    /**
     * 调拨单号
     */
    private String orderNo;

    /**
     * 操作人
     */
    private String operator;

    /**
     * 拒绝原因（仅 reject 时使用）
     */
    private String reason;

    private static final long serialVersionUID = 1L;
}
