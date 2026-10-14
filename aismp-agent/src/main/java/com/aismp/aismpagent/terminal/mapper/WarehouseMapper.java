package com.aismp.aismpagent.terminal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aismp.aismpagent.terminal.model.entity.Warehouse;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 仓位 Mapper
 *
 * @author agent-platform
 */
public interface WarehouseMapper extends BaseMapper<Warehouse> {

    /**
     * 按层级升序列出全部仓位（地市 → 区县 → 网格）
     */
    @Select("SELECT * FROM warehouse ORDER BY level, warehouseCode")
    List<Warehouse> listAllOrdered();
}
