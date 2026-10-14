package com.aismp.aismpagent.terminal.model.dto.transferOrder;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 调拨出库请求
 * <p>
 * 业务规则（开发文档 1.4 闭环条款）：出库必须按调拨单的**数量 / 型号**导入串码，
 * 服务层会做三重比对（型号匹配、数量一致、状态为可用），全部通过才扣减库存。
 *
 * @author agent-platform
 */
@Data
public class TransferOrderOutboundRequest implements Serializable {

    /**
     * 调拨单号
     */
    private String orderNo;

    /**
     * 本次出库的串码清单（从 Excel 解析后传入）
     */
    private List<String> serialNos;

    /**
     * 操作人
     */
    private String operator;

    private static final long serialVersionUID = 1L;
}
