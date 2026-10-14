-- ============================================================
-- 71-tool-mount-refresh.sql — 把新的 API_CALL 工具挂到数字员工上（阶段2 收尾）
-- 库：terminal_db
-- 幂等：可重复执行
--
-- 【为什么需要】
--   70 号脚本把 20 个 SQL_TEMPLATE 工具停用、新建 7 个 API_CALL 工具，
--   但 a_agent_tool_rel 里的挂载关系还指向旧工具 ID —— 那些工具已 disabled，
--   装配时会被跳过，数字员工等于「一个工具都没有」。
--
-- 【挂载策略（按数字员工角色分工）】
--   query_assistant   查数助手    → 全部 7 个查询工具
--   replenish_advisor 补仓推荐师  → 快照 / 库龄 / 按型号 / 统计卡片（补货分析四件套）
--   terminal_manager  终端管理助手→ 按仓位查 / 仓位列表 / 统计 / 串码 / 快照（管理类）
--   ops_assistant     运维助手    → 保持 searchHandbook（知识检索，不动）
--
-- 【为什么最后要 bump version】
--   内核 AgentRegistry 靠 a_agent.version 比对判断是否需要重建装配结果，
--   挂载关系变了但 version 没变的话，运行中的数字员工不会感知到新工具。
-- ============================================================

USE terminal_db;

-- ---------- 1. 卸载指向「已停用工具」的挂载 ----------
DELETE r FROM `a_agent_tool_rel` r
  JOIN `a_llm_tool` t ON t.id = r.tool_id
 WHERE t.`status` = 'disabled';

-- ---------- 2. query_assistant：挂全部 API_CALL 工具 ----------
INSERT IGNORE INTO `a_agent_tool_rel` (`agent_id`, `tool_id`, `sort_order`)
SELECT a.id, t.id, 10
  FROM `a_agent` a
  JOIN `a_llm_tool` t
 WHERE a.`agent_code` = 'query_assistant'
   AND t.`tool_type` = 'API_CALL'
   AND t.`status` = 'active';

-- ---------- 3. replenish_advisor：补货分析四件套 ----------
INSERT IGNORE INTO `a_agent_tool_rel` (`agent_id`, `tool_id`, `sort_order`)
SELECT a.id, t.id, 20
  FROM `a_agent` a
  JOIN `a_llm_tool` t
 WHERE a.`agent_code` = 'replenish_advisor'
   AND t.`tool_name` IN (
       'queryInventorySnapshot',   -- 一致口径的全量快照，算法基础
       'queryInventoryAging',      -- 库龄分段，判断积压
       'queryInventoryByModel',    -- 跨仓位汇总，判断"只能采购"还是"可省内调拨"
       'queryStatCards'            -- 总体规模
   )
   AND t.`status` = 'active';

-- ---------- 4. terminal_manager：管理类工具 ----------
INSERT IGNORE INTO `a_agent_tool_rel` (`agent_id`, `tool_id`, `sort_order`)
SELECT a.id, t.id, 30
  FROM `a_agent` a
  JOIN `a_llm_tool` t
 WHERE a.`agent_code` = 'terminal_manager'
   AND t.`tool_name` IN (
       'queryInventoryByWarehouse',
       'queryWarehouseList',
       'queryStatCards',
       'queryAvailableSerialNos',  -- 调拨出库挑串码
       'queryInventorySnapshot'
   )
   AND t.`status` = 'active';

-- ---------- 5. bump version，触发内核热重建 ----------
UPDATE `a_agent`
   SET `version` = `version` + 1
 WHERE `agent_code` IN ('query_assistant', 'replenish_advisor', 'terminal_manager');

-- ---------- 结果 ----------
SELECT a.`agent_code`,
       a.`version`,
       COUNT(t.id) AS active_tools   -- 统计 join 上来的「有效工具」，不是所有挂载行
  FROM `a_agent` a
  LEFT JOIN `a_agent_tool_rel` r ON r.`agent_id` = a.id
  LEFT JOIN `a_llm_tool` t ON t.id = r.`tool_id` AND t.`status` = 'active'
 WHERE a.`status` = 'active'
 GROUP BY a.`agent_code`, a.`version`
 ORDER BY a.`id`;
