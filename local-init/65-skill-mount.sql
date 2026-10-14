-- ============================================================================
-- 补挂技能（64 脚本只挂了工具，漏了技能 —— 验收 A3 发现）
-- ============================================================================
USE terminal_db;

-- 查数助手：库存查询规范 + 设备溯源规范
INSERT IGNORE INTO a_agent_skill_rel (agent_id, skill_id, sort_order)
SELECT a.id, s.id,
       ROW_NUMBER() OVER (ORDER BY s.id)
FROM a_agent a JOIN a_agent_skill s
  ON s.skill_code IN ('inventory_query','trace_terminal')
 WHERE a.agent_code = 'query_assistant';

-- 补仓推荐师：库存查询规范
INSERT IGNORE INTO a_agent_skill_rel (agent_id, skill_id, sort_order)
SELECT a.id, s.id, 1
FROM a_agent a JOIN a_agent_skill s
  ON s.skill_code = 'inventory_query'
 WHERE a.agent_code = 'replenish_advisor';

-- 运维助手：暂无专属技能（流程规范技能待建），保持无技能不报错
SELECT a.agent_code, a.version, COUNT(r.skill_id) AS 技能数, COUNT(DISTINCT t.id) AS 工具数
FROM a_agent a
LEFT JOIN a_agent_skill_rel r ON r.agent_id = a.id
LEFT JOIN a_agent_tool_rel tr ON tr.agent_id = a.id
LEFT JOIN a_llm_tool t ON t.id = tr.tool_id
GROUP BY a.id ORDER BY a.id;
