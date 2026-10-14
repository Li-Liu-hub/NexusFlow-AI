package com.aismp.aismpagent.terminal.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 仓位（四级仓储体系 + 智家虚拟五级）
 * <p>
 * 由 terminal 的仓位列派生去重生成，不单独录入。
 * 层级判定：哪一级仓位名有值就是哪一级。
 *
 * @author agent-platform
 */
@TableName(value = "warehouse")
@Data
public class Warehouse implements Serializable {

    /**
     * 仓位编码（业务主键，源数据里是 4 位码，如 7A00 / 7AZA）
     */
    @TableId(value = "warehouseCode", type = IdType.INPUT)
    private String warehouseCode;

    /**
     * 仓位名称
     */
    private String name;

    /**
     * 层级：1=省 2=地市 3=区县 4=网格 5=智家(虚拟)
     */
    private Integer level;

    /**
     * 上级仓位编码
     */
    private String parentCode;

    /**
     * 省
     */
    private String province;

    /**
     * 地市
     */
    private String city;

    /**
     * 区县
     */
    private String district;

    private Date createdAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
