package com.aismp.aismpagent.terminal.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 调拨单明细
 *
 * @author agent-platform
 */
@TableName(value = "transfer_order_item")
@Data
public class TransferOrderItem implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 调拨单号
     */
    private String orderNo;

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
     * 数量
     */
    private Integer quantity;

    /**
     * 串码明细（JSON 数组）。
     * 出库时按单导入串码 Excel，服务层会做「型号 / 数量 / 状态=可用」三重比对，
     * 全部通过才写入该字段并扣减库存。
     */
    private String serialNos;

    private Date createdAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
