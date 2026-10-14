package com.aismp.aismpagent.terminal.model.dto.transferOrder;

import com.aismp.aismpagent.terminal.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 调拨单分页查询请求
 *
 * @author agent-platform
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class TransferOrderQueryRequest extends PageRequest implements Serializable {

    /**
     * 调拨单号（精确）
     */
    private String orderNo;

    /**
     * 状态：PENDING / OUTBOUND / DONE / REJECTED
     */
    private String status;

    /**
     * 创建时间起（yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss，含）
     */
    private String startTime;

    /**
     * 创建时间止（yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss；只给日期时含当天整天）
     */
    private String endTime;

    /**
     * 操作类型：TRANSFER / ISSUE / RECALL / RETURN
     */
    private String operationType;

    /**
     * 调出仓位编码
     */
    private String fromWarehouse;

    /**
     * 调入仓位编码
     */
    private String toWarehouse;

    private static final long serialVersionUID = 1L;
}
