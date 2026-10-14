-- ============================================================
-- 70-tools-api-call.sql — 内核查库工具改为「调 aismp-agent 接口」（架构重构阶段2）
-- 库：terminal_db
-- 幂等：先 DELETE 同名工具再 INSERT，可重复执行
--
-- 【为什么改】
--   原先 20 个 SQL_TEMPLATE 工具让内核直连 terminal_db，而业务实现（aismp-agent）
--   查的是 api_db —— 同一份终端业务两个数据出口，口径必然漂移。
--   架构调整后内核只提供 agent 能力，业务数据统一由 aismp-agent 出口，
--   所以工具改成 API_CALL（调 agent 的 HTTP 接口，走 Nacos 服务发现）。
--
-- 【为什么是 7 个而不是把 20 个逐个改】
--   老的 20 个工具是基于【旧数据模型】设计的，很多在【新模型里根本没有对应实体】：
--     工单(t_work_order) / 巡检(t_inspection) / 报废(t_scrap_order) / 采购(t_purchase_order)
--     / 库存流转(t_stock_movement) / 入库单(t_inbound_order)
--   真实数据源（长春市二级库.xlsx + 九台市分公司三级库.xlsx，33 列）只含【终端库存】信息，
--   api_db 也只建了 terminal / warehouse / transfer_order / transfer_order_item / notice 5 张表。
--   所以这 7 个工具是按 agent 实际提供的接口重新设计的，不是硬迁。
--   老工具的处理见文件末尾的「停用」段。
-- ============================================================

USE terminal_db;

-- 顺带把 tool_type 的注释更新为实际支持的类型
ALTER TABLE `a_llm_tool`
  MODIFY COLUMN `tool_type` VARCHAR(32) NOT NULL DEFAULT 'SQL_TEMPLATE'
  COMMENT '工具类型：SQL_TEMPLATE 内核查库 / API_CALL 调业务接口 / KB_RETRIEVAL 知识检索 / MCP（预留）';

-- ---------- 清理待重建的工具名（幂等）----------
DELETE FROM `a_llm_tool` WHERE `tool_name` IN (
  'queryInventoryByWarehouse', 'queryInventoryByModel', 'queryInventoryAging',
  'queryInventorySnapshot', 'queryStatCards', 'queryWarehouseList', 'queryAvailableSerialNos'
);

-- ---------- 1. 按仓位查库存聚合 ----------
-- 对应 GET /terminal/inventory/aggregate
INSERT INTO `a_llm_tool`
  (`tool_name`, `tool_desc`, `tool_type`, `input_schema`, `config_json`, `result_limit`, `status`, `remark`)
VALUES (
  'queryInventoryByWarehouse',
  '按库位统计终端库存。用户问"某库位/某分公司有多少台终端""库存分布"时用它。返回各型号的可用数量、在途数量、库龄。不传仓位编码则返回全部仓位。',
  'API_CALL',
  '{"type":"object","properties":{"warehouseCode":{"type":"string","description":"仓位编码，如 9B00（东宁市分公司）、9BZA（东宁市新区分公司）。不传则统计全部仓位"}},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/aggregate","method":"GET","timeoutSeconds":30,"params":{"warehouseCode":"${warehouseCode}"}}',
  200, 'active', '阶段2：改为调 aismp-agent 接口'
);

-- ---------- 2. 按型号跨仓位汇总 ----------
-- 对应 GET /terminal/inventory/byModel
INSERT INTO `a_llm_tool`
  (`tool_name`, `tool_desc`, `tool_type`, `input_schema`, `config_json`, `result_limit`, `status`, `remark`)
VALUES (
  'queryInventoryByModel',
  '按终端型号跨全部仓位汇总库存。判断"某型号全省还有多少货""是否只能走采购"时用它。返回每个型号的总量、可用量、在途量与平均库龄。',
  'API_CALL',
  '{"type":"object","properties":{},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/byModel","method":"GET","timeoutSeconds":30}',
  200, 'active', '阶段2：改为调 aismp-agent 接口'
);

-- ---------- 3. 库龄分布 ----------
-- 对应 GET /terminal/inventory/aging
INSERT INTO `a_llm_tool`
  (`tool_name`, `tool_desc`, `tool_type`, `input_schema`, `config_json`, `result_limit`, `status`, `remark`)
VALUES (
  'queryInventoryAging',
  '查询终端库龄分布（按 0-30 / 30-90 / 90-180 / 180-365 / 365+ 天分段统计数量）。分析"长库龄积压""该优先盘活哪些货"时用它。',
  'API_CALL',
  '{"type":"object","properties":{"warehouseCode":{"type":"string","description":"仓位编码，可选；不传则统计全部仓位"}},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/aging","method":"GET","timeoutSeconds":30,"params":{"warehouseCode":"${warehouseCode}"}}',
  50, 'active', '阶段2：改为调 aismp-agent 接口'
);

-- ---------- 4. 库存快照（一次取全，供算法用） ----------
-- 对应 GET /terminal/inventory/snapshot
INSERT INTO `a_llm_tool`
  (`tool_name`, `tool_desc`, `tool_type`, `input_schema`, `config_json`, `result_limit`, `status`, `remark`)
VALUES (
  'queryInventorySnapshot',
  '一次性取回库存全量视图（按型号汇总 + 库龄分布 + 在途），保证同一时刻的一致口径。做补货测算、缺口分析时优先用它，避免多次查询之间的数据时间差。',
  'API_CALL',
  '{"type":"object","properties":{"warehouseCode":{"type":"string","description":"仓位编码，可选"},"model":{"type":"string","description":"终端型号关键字，可选，如 F657"}},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/snapshot","method":"GET","timeoutSeconds":30,"params":{"warehouseCode":"${warehouseCode}","model":"${model}"}}',
  500, 'active', '阶段2：改为调 aismp-agent 接口'
);

-- ---------- 5. 首页统计卡片 ----------
-- 对应 GET /terminal/stat/cards
INSERT INTO `a_llm_tool`
  (`tool_name`, `tool_desc`, `tool_type`, `input_schema`, `config_json`, `result_limit`, `status`, `remark`)
VALUES (
  'queryStatCards',
  '查询终端管理首页的四个统计指标：总库存量、长库龄数量、昨日回收、昨日装机。用户问"总体情况""有多少终端"时用它。',
  'API_CALL',
  '{"type":"object","properties":{},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/stat/cards","method":"GET","timeoutSeconds":30}',
  10, 'active', '阶段2：改为调 aismp-agent 接口'
);

-- ---------- 6. 仓位列表 ----------
-- 对应 GET /terminal/warehouse/list
INSERT INTO `a_llm_tool`
  (`tool_name`, `tool_desc`, `tool_type`, `input_schema`, `config_json`, `result_limit`, `status`, `remark`)
VALUES (
  'queryWarehouseList',
  '查询全部仓位（库位）列表，含仓位编码、名称、层级、上级仓位。需要知道有哪些库位、某库位的编码是什么时用它。',
  'API_CALL',
  '{"type":"object","properties":{},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/warehouse/list","method":"GET","timeoutSeconds":30}',
  200, 'active', '阶段2：改为调 aismp-agent 接口'
);

-- ---------- 7. 可用串码清单（出库环节用） ----------
-- 对应 GET /terminal/inventory/serialNos
INSERT INTO `a_llm_tool`
  (`tool_name`, `tool_desc`, `tool_type`, `input_schema`, `config_json`, `result_limit`, `status`, `remark`)
VALUES (
  'queryAvailableSerialNos',
  '列出某仓位某物料下可用的终端串码（按库龄倒序，优先出长库龄的货）。调拨出库需要挑具体串码时用它。',
  'API_CALL',
  '{"type":"object","properties":{"warehouseCode":{"type":"string","description":"仓位编码，必填"},"materialCode":{"type":"string","description":"物料编码，必填"},"limit":{"type":"integer","description":"返回条数，默认 10，最大 500"}},"required":["warehouseCode","materialCode"]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/serialNos","method":"GET","timeoutSeconds":30,"params":{"warehouseCode":"${warehouseCode}","materialCode":"${materialCode}","limit":"${limit}"}}',
  500, 'active', '阶段2：改为调 aismp-agent 接口'
);

-- ============================================================
-- 【停用】旧的 SQL_TEMPLATE 工具
-- ============================================================
-- 这 20 个工具直接查 terminal_db 的 t_* 表，与「内核不直连业务库」的目标冲突。
-- 其中多数在新数据模型里没有对应实体（工单/巡检/报废/采购/流转），
-- 保留它们只会让模型查到另一套口径的数据，造成前后矛盾。
--
-- 回滚方式：UPDATE a_llm_tool SET status='active' WHERE tool_type='SQL_TEMPLATE';
UPDATE `a_llm_tool`
   SET `status` = 'disabled',
       `remark` = CONCAT(COALESCE(`remark`, ''), ' [阶段2停用：内核不再直连业务库，改由 aismp-agent 出口]')
 WHERE `tool_type` = 'SQL_TEMPLATE'
   AND `status` = 'active';

SELECT CONCAT('api-call tools ready: ', COUNT(*)) AS msg
  FROM `a_llm_tool` WHERE `tool_type` = 'API_CALL' AND `status` = 'active';
