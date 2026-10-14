-- ============================================================
-- 73-approval-demo-tool.sql — 审批拦截演示：把 approval_required 挂到现有可用工具
-- 库：terminal_db
-- 幂等：可重复执行
--
-- 【背景】20 个老 SQL_TEMPLATE 工具已停用，其中唯一带审批标记的
--   queryZeroCostInbound（0元入库单，HIGH）也随之失效 —— 新工具集下
--   审批拦截没有任何可演示对象（验收 A19 因此失败，属基线漂移）。
--
-- 【用户决策（2026-10-06）】不补建工单/巡检/报废/采购等业务实体（真实数据源里没有），
--   把审批挂到现有可用的敏感查询上：queryAvailableSerialNos（串码明细，
--   导出设备串码属敏感操作，加人工放行说得通）。
--
-- 【生效方式】内核 AgentRegistry 靠 a_agent.version 判断是否重建装配结果，
--   只改工具定义不 bump version 的话，运行中的数字员工感知不到。
-- ============================================================

USE terminal_db;

-- ---------- 1. 串码明细 → 需审批（HIGH） ----------
UPDATE `a_llm_tool`
   SET `approval_required` = 1,
       `risk_level` = 'HIGH',
       `remark` = '阶段2 改为 API_CALL；审批演示：串码明细属敏感操作，需人工放行'
 WHERE `tool_name` = 'queryAvailableSerialNos';

-- ---------- 2. bump 所有挂载它的数字员工 version，触发热重建 ----------
UPDATE `a_agent` a
  JOIN `a_agent_tool_rel` r ON r.`agent_id` = a.`id`
  JOIN `a_llm_tool` t ON t.`id` = r.`tool_id`
   SET a.`version` = a.`version` + 1
 WHERE t.`tool_name` = 'queryAvailableSerialNos'
   AND t.`status` = 'active';

-- ---------- 结果 ----------
SELECT `tool_name`, `status`, `approval_required`, `risk_level`
  FROM `a_llm_tool`
 WHERE `approval_required` = 1 AND `status` = 'active';

SELECT a.`agent_code`, a.`version`
  FROM `a_agent` a
  JOIN `a_agent_tool_rel` r ON r.`agent_id` = a.`id`
  JOIN `a_llm_tool` t ON t.`id` = r.`tool_id`
 WHERE t.`tool_name` = 'queryAvailableSerialNos'
 ORDER BY a.`id`;