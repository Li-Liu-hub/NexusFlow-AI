package com.aismp.aismpagent.terminal.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 终端台账（串码级全生命周期）
 * <p>
 * 表 terminal，33 个业务字段，与源 Excel（长春市二级库 / 九台市分公司三级库）逐列对应，
 * 字段映射见 docs/contracts/01-数据模型.md 附录。
 * <p>
 * 注意：本项目的 mybatis-plus 配置了 map-underscore-to-camel-case=false，
 * 因此**列名与字段名必须逐字相同**（列名也是驼峰，如 serialNo），这一点与常见约定相反。
 *
 * @author agent-platform
 */
@TableName(value = "terminal")
@Data
public class Terminal implements Serializable {

    /**
     * 终端串码（主键）
     */
    @TableId(value = "serialNo", type = IdType.INPUT)
    private String serialNo;

    /**
     * 终端大类（光猫 / 机顶盒 / FTTR / 摄像头 / 路由器）
     */
    private String bigType;

    /**
     * 终端小类
     */
    private String smallType;

    /**
     * 终端厂家
     */
    private String vendor;

    /**
     * 终端型号
     */
    private String model;

    /**
     * 物料编码
     */
    private String materialCode;

    /**
     * 物料名称
     */
    private String materialName;

    /**
     * 采购单号
     */
    private String purchaseNo;

    /**
     * 终端状态：可用 / 占用 / 在途 / 待检 / 待报废 / 待回收 / 备机 / 待智家领用确认 / 领用退回
     */
    private String terminalStatus;

    /**
     * 入库类型
     */
    private String inboundType;

    /**
     * 业务类型
     */
    private String businessType;

    /**
     * 采购类型
     */
    private String purchaseType;

    /**
     * 状态变更时间
     */
    private Date statusChangedAt;

    /**
     * 一级仓位名（省公司）
     */
    private String lv1Warehouse;

    /**
     * 二级仓位名（地市分公司）
     */
    private String lv2Warehouse;

    /**
     * 三级仓位名（区县分公司）
     */
    private String lv3Warehouse;

    /**
     * 四级仓位名（网格）
     */
    private String lv4Warehouse;

    /**
     * 归属仓位名
     */
    private String ownerWarehouse;

    /**
     * 仓位编码（关联 warehouse.warehouseCode）
     */
    private String warehouseCode;

    /**
     * 目标仓位
     */
    private String targetWarehouse;

    /**
     * 单价（元）
     */
    private BigDecimal unitPrice;

    /**
     * 客户号码（敏感，对外接口一律脱敏）
     */
    private String customerPhone;

    /**
     * 领用人（敏感）
     */
    private String receiver;

    /**
     * 领用工号（敏感）
     */
    private String receiverNo;

    /**
     * 箱号
     */
    private String boxNo;

    /**
     * 首次入库时间
     */
    private Date firstInboundAt;

    /**
     * 库龄（天）
     */
    private Integer agingDays;

    /**
     * 保修起始时间
     */
    private Date warrantyStart;

    /**
     * 保修结束时间
     */
    private Date warrantyEnd;

    /**
     * 操作人（敏感）
     */
    private String operator;

    /**
     * 操作人工号（敏感）
     */
    private String operatorNo;

    /**
     * 二次入库时间
     */
    private Date secondInboundAt;

    /**
     * 丢失赔偿价格
     */
    private BigDecimal lostCompensation;

    /**
     * 创建时间
     */
    private Date createdAt;

    /**
     * 更新时间
     */
    private Date updatedAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
