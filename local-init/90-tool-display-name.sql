-- ============================================================================
-- 数字员工内核 · 工具中文显示名
-- ============================================================================
-- 目标库：terminal_db
--
-- 【为什么需要】
-- 管理台各处（工具管理 / 数字员工配置挂载区 / 运行预览 / 调用审计）原先直接显示
-- tool_name（queryInventoryByWarehouse 这类英文技术名），运营同学看不懂。
-- 给 a_llm_tool 加 display_name（中文显示名），管理台一律显示中文名；
-- LLM 的 function name 仍是 tool_name（模型 function calling 对字符集有限制，
-- 不能改成中文），两者解耦。
--
-- 【设计】
--   display_name 允许为空 —— 为空时前端回退显示 tool_name（MCP 自动生成的工具
--   就属于这种，名称来自 MCP Server，无法预先写死映射）。
--   下方 UPDATE 覆盖存量内置工具；新增工具在管理台「新建工具」表单里填。
-- ============================================================================
USE terminal_db;

DROP PROCEDURE IF EXISTS add_display_name_if_absent;
DELIMITER //
CREATE PROCEDURE add_display_name_if_absent()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'a_llm_tool'
           AND column_name = 'display_name'
    ) THEN
        ALTER TABLE a_llm_tool
            ADD COLUMN display_name varchar(64) NULL
            COMMENT '中文显示名（管理台展示用；LLM 调用名仍是 tool_name）'
            AFTER tool_name;
    END IF;
END //
DELIMITER ;
CALL add_display_name_if_absent();
DROP PROCEDURE IF EXISTS add_display_name_if_absent;

-- 存量内置工具补中文名（幂等：只补空的，不覆盖人工已改的）
UPDATE a_llm_tool SET display_name = CASE tool_name
    -- 遗留 SQL 模板工具（终端管理）
    WHEN 'queryTerminalByCode'          THEN '按串码查终端详情'
    WHEN 'queryStockByLocation'         THEN '按库位查库存明细'
    WHEN 'countStockByStatus'           THEN '按状态统计库存'
    WHEN 'countStockByCity'             THEN '按地市统计库存'
    WHEN 'countStockByModel'            THEN '按型号统计库存'
    WHEN 'queryTerminalsByStatus'       THEN '按状态查终端列表'
    WHEN 'queryMovementsByCode'         THEN '按串码查流转记录'
    WHEN 'queryRecentMovements'         THEN '查最近流转记录'
    WHEN 'queryPendingInspection'       THEN '查待质检待返修'
    WHEN 'queryStaffAllocated'          THEN '查装维人员在手终端'
    WHEN 'queryWorkOrdersByType'        THEN '按工单类型查工单'
    WHEN 'queryWorkOrdersByStatus'      THEN '按状态查工单列表'
    WHEN 'queryScrapRecords'            THEN '查报废单记录'
    WHEN 'queryFailedInspections'       THEN '查质检不通过记录'
    WHEN 'queryPassedInspections'       THEN '查质检通过记录'
    WHEN 'queryPurchaseOrders'          THEN '查采购单进度'
    WHEN 'queryLocationTree'            THEN '查四级库位结构'
    WHEN 'queryLowStockLocations'       THEN '查低库存库位'
    WHEN 'queryZeroCostInbound'         THEN '查零成本入库'
    WHEN 'queryCrossNetworkTransfers'   THEN '查跨网调拨'
    WHEN 'searchHandbook'               THEN '操作手册知识检索'
    -- API_CALL 工具（业务接口）
    WHEN 'queryInventoryByWarehouse'    THEN '按库位查库存'
    WHEN 'queryInventoryByModel'        THEN '按型号查库存'
    WHEN 'queryInventoryAging'          THEN '库龄分布查询'
    WHEN 'queryInventorySnapshot'       THEN '库存快照查询'
    WHEN 'queryStatCards'               THEN '统计指标卡查询'
    WHEN 'queryWarehouseList'           THEN '仓位列表查询'
    WHEN 'queryAvailableSerialNos'      THEN '可用串码查询'
    -- 入库时间维度工具
    WHEN 'queryInboundByMonth'          THEN '按月查入库量'
    WHEN 'queryInboundByYear'           THEN '按年查入库量'
    WHEN 'queryInboundByDay'            THEN '按日查入库量'
    WHEN 'compareInboundByMonths'       THEN '多月入库对比'
    WHEN 'queryInboundByWarehouseByMonth' THEN '各仓按月入库'
    WHEN 'queryInboundByModelByMonth'   THEN '各型号按月入库'
    WHEN 'queryInboundSummary'          THEN '入库汇总查询'
    ELSE display_name
END
WHERE display_name IS NULL OR display_name = '';

-- 自检：仍有空显示名的工具（MCP 自动生成的允许为空，其余需人工补）
SELECT id, tool_name, display_name, tool_type
  FROM a_llm_tool
 WHERE display_name IS NULL OR display_name = ''
 ORDER BY id;
