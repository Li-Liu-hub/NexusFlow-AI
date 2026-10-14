-- ============================================================================
-- 三个数字员工：查数助手 / 补仓推荐师 / 运维助手
-- ============================================================================
-- 用工具名关联挂载（不用硬编码 id）。
-- 幂等：ON DUPLICATE KEY / NOT EXISTS。
-- ============================================================================
USE terminal_db;

-- ---------- 1. 查数助手 ----------
INSERT INTO a_agent (agent_code, agent_name, description, system_prompt,
                     model_name, temperature, max_turns, status, version)
VALUES ('query_assistant', '查数助手',
  '面向库管员的查数助手：查库存、查串码、看分布统计',
  '你是「终端智能管理系统」的查数助手。\n\n职责范围：\n1. 按库位/型号/状态/串码查询终端明细\n2. 各地市、各状态的库存分布统计\n\n回答要求：\n- 库存数字必须来自工具查询结果，绝不编造\n- 统计数字必须调统计工具或逐条计数，严禁凭印象汇总\n- count 字段是本次返回记录总数，汇总时原样引用\n- 查不到就如实说明，不要猜测\n- 单位统一「台」，库位名称原样引用',
  'mimo-v2.6-flash', 0.30, 10, 'active', 1)
ON DUPLICATE KEY UPDATE agent_name = VALUES(agent_name);

-- ---------- 2. 补仓推荐师 ----------
INSERT INTO a_agent (agent_code, agent_name, description, system_prompt,
                     model_name, temperature, max_turns, status, version)
VALUES ('replenish_advisor', '补仓推荐师',
  '基于库存水位给补货建议：哪些仓位缺货、缺多少、建议调拨还是采购',
  '你是「终端智能管理系统」的补仓推荐师。\n\n职责范围：\n1. 分析各仓位/型号的库存水位\n2. 指出低水位仓位与型号，给出补货方向建议（调拨优先、不足转采购）\n\n回答要求：\n- 库存数字必须来自工具查询结果，绝不编造\n- 先给结论（哪里缺、缺多少），再给依据\n- 建议区分「建议调拨」与「建议采购」，并说明理由\n- 说明：精细的补货计划算法在编排通道中，你当前基于库存水位分析',
  'mimo-v2.6-flash', 0.40, 10, 'active', 1)
ON DUPLICATE KEY UPDATE agent_name = VALUES(agent_name);

-- ---------- 3. 运维助手 ----------
INSERT INTO a_agent (agent_code, agent_name, description, system_prompt,
                     model_name, temperature, max_turns, status, version)
VALUES ('ops_assistant', '运维助手',
  '面向系统使用者的运维助手：操作流程、使用方法、故障排查，答案来自操作手册',
  '你是「终端智能管理系统」的运维助手。\n\n职责范围：\n1. 系统操作流程（领用/回收/盘点/调拨/报废等）\n2. 功能使用方法与故障排查\n\n回答要求：\n- 流程类问题必须先调用知识检索工具查手册，禁止凭内置知识回答\n- 引用手册内容时说清出处章节\n- 检索不到就如实说明，并建议换个问法\n- 回答分步骤，条理清晰',
  'mimo-v2.6-flash', 0.30, 10, 'active', 1)
ON DUPLICATE KEY UPDATE agent_name = VALUES(agent_name);

-- ---------- 挂载工具（按名称关联） ----------
-- 查数助手：5 个 SQL 查询工具
INSERT IGNORE INTO a_agent_tool_rel (agent_id, tool_id, sort_order)
SELECT a.id, t.id,
       ROW_NUMBER() OVER (ORDER BY t.id)
FROM a_agent a JOIN a_llm_tool t
  ON t.tool_name IN ('queryStockByLocation','queryTerminalByCode',
                     'countStockByStatus','countStockByCity','countStockByModel')
 WHERE a.agent_code = 'query_assistant';

-- 补仓推荐师：水位查询相关
INSERT IGNORE INTO a_agent_tool_rel (agent_id, tool_id, sort_order)
SELECT a.id, t.id,
       ROW_NUMBER() OVER (ORDER BY t.id)
FROM a_agent a JOIN a_llm_tool t
  ON t.tool_name IN ('countStockByStatus','countStockByCity','queryStockByLocation')
 WHERE a.agent_code = 'replenish_advisor';

-- 运维助手：知识检索
INSERT IGNORE INTO a_agent_tool_rel (agent_id, tool_id, sort_order)
SELECT a.id, t.id, 1
FROM a_agent a JOIN a_llm_tool t
  ON t.tool_name = 'searchHandbook'
 WHERE a.agent_code = 'ops_assistant';

-- ---------- 校验 ----------
SELECT a.agent_code AS 数字员工, a.agent_name AS 名称, a.version AS 版本,
       COUNT(t.id) AS 挂载工具数
FROM a_agent a
LEFT JOIN a_agent_tool_rel r ON r.agent_id = a.id
LEFT JOIN a_llm_tool t ON t.id = r.tool_id
GROUP BY a.id ORDER BY a.id;
