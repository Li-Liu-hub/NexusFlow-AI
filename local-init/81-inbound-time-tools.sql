-- ============================================================================
-- 81-inbound-time-tools.sql — 「查数助手」入库时间维度工具包（2026-10-07）
-- 库：terminal_db
-- 幂等：DELETE 同名工具 + ON DUPLICATE KEY / INSERT IGNORE，可重复执行
--
-- 【背景】
--   查数助手原先 7 个工具全是「当前时点」口径（库存聚合/库龄/快照…），
--   用户要查「不同月份对比 / 年份 / 按日期」时无工具可用。
--   本次按【首次入库时间 firstInboundAt】补 7 个时间维度工具（全部 API_CALL，
--   调 aismp-agent 的新接口 /terminal/inventory/inboundTrend | monthCompare | inboundSummary）。
--
-- 【口径】历史视角：设备 2025-06 入库、后来调拨走，仍记在 2025-06（按首次入库时间归桶）。
-- 【为什么是 API_CALL 而不是 SQL_TEMPLATE】
--   内核不直连业务库（阶段2 架构约束）：业务数据统一由 aismp-agent 出口。
-- ============================================================================

USE terminal_db;

-- ---------- 0. 幂等清理（先清挂载关系，再删工具）----------
DELETE r FROM a_agent_tool_rel r JOIN a_llm_tool t ON t.id = r.tool_id
 WHERE t.tool_name IN (
   'queryInboundByMonth', 'queryInboundByYear', 'queryInboundByDay',
   'compareInboundByMonths', 'queryInboundByWarehouseByMonth',
   'queryInboundByModelByMonth', 'queryInboundSummary'
 );
DELETE FROM a_llm_tool WHERE tool_name IN (
   'queryInboundByMonth', 'queryInboundByYear', 'queryInboundByDay',
   'compareInboundByMonths', 'queryInboundByWarehouseByMonth',
   'queryInboundByModelByMonth', 'queryInboundSummary'
 );

-- ---------- 1. 按月入库量（趋势）----------
INSERT INTO a_llm_tool
  (tool_name, tool_desc, tool_type, input_schema, config_json, result_limit, status, remark)
VALUES (
  'queryInboundByMonth',
  '按月统计终端入库量（依据首次入库时间，历史视角）。用户问"近几个月入库情况""入库量趋势""每个月入库多少台"时用它。返回 totalCnt（合计）与 rows（每行 period=YYYY-MM、cnt=台数），数字可直接引用。可按仓位/型号/大类过滤；不传日期则返回全部历史月份。',
  'API_CALL',
  '{"type":"object","properties":{"from":{"type":"string","description":"起始日期 yyyy-MM-dd（含），可选；不传=全部历史"},"to":{"type":"string","description":"结束日期 yyyy-MM-dd（含），可选"},"warehouseCode":{"type":"string","description":"仓位编码，如 9BZA，可选"},"model":{"type":"string","description":"终端型号，如 F657，可选"},"bigType":{"type":"string","description":"终端大类：光猫/机顶盒/FTTR/摄像头/路由器，可选"}},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/inboundTrend","method":"GET","timeoutSeconds":30,"params":{"granularity":"month","from":"${from}","to":"${to}","warehouseCode":"${warehouseCode}","model":"${model}","bigType":"${bigType}"}}',
  500, 'active', '时间维度：入库月度趋势'
);

-- ---------- 2. 按年入库量（年度对比）----------
INSERT INTO a_llm_tool
  (tool_name, tool_desc, tool_type, input_schema, config_json, result_limit, status, remark)
VALUES (
  'queryInboundByYear',
  '按年份统计终端入库量（依据首次入库时间）。用户问"每年入库量""哪一年入库最多""2024 与 2025 年对比"时用它。返回 totalCnt（合计）与 rows（每行 period=YYYY、cnt=台数）。可按仓位/型号/大类过滤。',
  'API_CALL',
  '{"type":"object","properties":{"from":{"type":"string","description":"起始日期 yyyy-MM-dd（含），可选"},"to":{"type":"string","description":"结束日期 yyyy-MM-dd（含），可选"},"warehouseCode":{"type":"string","description":"仓位编码，可选"},"model":{"type":"string","description":"终端型号，可选"},"bigType":{"type":"string","description":"终端大类，可选"}},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/inboundTrend","method":"GET","timeoutSeconds":30,"params":{"granularity":"year","from":"${from}","to":"${to}","warehouseCode":"${warehouseCode}","model":"${model}","bigType":"${bigType}"}}',
  50, 'active', '时间维度：入库年度对比'
);

-- ---------- 3. 按天入库量（日期查询）----------
INSERT INTO a_llm_tool
  (tool_name, tool_desc, tool_type, input_schema, config_json, result_limit, status, remark)
VALUES (
  'queryInboundByDay',
  '按天统计终端入库量。用户问"某一天入库了多少""6月1日到6月30日每天入库多少""按日期查入库"时用它。必须给出 from 与 to（yyyy-MM-dd，跨度不超过 366 天）；只问某段时间的总量请用 queryInboundSummary。返回 totalCnt 与 rows（每行 period=YYYY-MM-DD、cnt=台数）。',
  'API_CALL',
  '{"type":"object","properties":{"from":{"type":"string","description":"起始日期 yyyy-MM-dd（含），必填"},"to":{"type":"string","description":"结束日期 yyyy-MM-dd（含），必填；与 from 跨度≤366 天"},"warehouseCode":{"type":"string","description":"仓位编码，可选"},"model":{"type":"string","description":"终端型号，可选"},"bigType":{"type":"string","description":"终端大类，可选"}},"required":["from","to"]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/inboundTrend","method":"GET","timeoutSeconds":30,"params":{"granularity":"day","from":"${from}","to":"${to}","warehouseCode":"${warehouseCode}","model":"${model}","bigType":"${bigType}"}}',
  400, 'active', '时间维度：按天（日期区间）'
);

-- ---------- 4. 指定月份对比（"不同月份的对比"）----------
INSERT INTO a_llm_tool
  (tool_name, tool_desc, tool_type, input_schema, config_json, result_limit, status, remark)
VALUES (
  'compareInboundByMonths',
  '对比指定若干个月的入库量（如 2025-06 与 2025-08）。用户问"X 月与 Y 月对比""哪个月入库最多""几个月比一比"时用它。months 用逗号分隔的 YYYY-MM（1~24 个）；缺失月份返回 cnt=0，结果按月份升序。返回 months（请求月份）、totalCnt（合计）与 rows（每月一行）。',
  'API_CALL',
  '{"type":"object","properties":{"months":{"type":"string","description":"要对比的月份，逗号分隔的 YYYY-MM，如 2025-06,2025-08；1~24 个"},"warehouseCode":{"type":"string","description":"仓位编码，可选"},"model":{"type":"string","description":"终端型号，可选"},"bigType":{"type":"string","description":"终端大类，可选"}},"required":["months"]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/monthCompare","method":"GET","timeoutSeconds":30,"params":{"months":"${months}","warehouseCode":"${warehouseCode}","model":"${model}","bigType":"${bigType}"}}',
  50, 'active', '时间维度：指定月份对比'
);

-- ---------- 5. 仓位 × 月份（交叉）----------
INSERT INTO a_llm_tool
  (tool_name, tool_desc, tool_type, input_schema, config_json, result_limit, status, remark)
VALUES (
  'queryInboundByWarehouseByMonth',
  '按"仓位 × 月份"交叉统计入库量（每个仓每个月的入库量）。用户问"各仓近几个月的入库分布""哪个仓入库最多"时用它。rows 每行 period=YYYY-MM、groupKey=仓位编码、groupName=仓位名、cnt=台数；同一月份内按 cnt 降序。',
  'API_CALL',
  '{"type":"object","properties":{"from":{"type":"string","description":"起始日期 yyyy-MM-dd（含），可选"},"to":{"type":"string","description":"结束日期 yyyy-MM-dd（含），可选"},"warehouseCode":{"type":"string","description":"只看某仓位时传，可选"},"model":{"type":"string","description":"终端型号，可选"},"bigType":{"type":"string","description":"终端大类，可选"}},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/inboundTrend","method":"GET","timeoutSeconds":30,"params":{"granularity":"month","groupBy":"warehouse","from":"${from}","to":"${to}","warehouseCode":"${warehouseCode}","model":"${model}","bigType":"${bigType}"}}',
  500, 'active', '时间维度：仓位×月份交叉'
);

-- ---------- 6. 型号 × 月份（交叉）----------
INSERT INTO a_llm_tool
  (tool_name, tool_desc, tool_type, input_schema, config_json, result_limit, status, remark)
VALUES (
  'queryInboundByModelByMonth',
  '按"型号 × 月份"交叉统计入库量。用户问"各型号入库趋势""某型号各月入库量""F657 每个月进来多少"时用它。rows 每行 period=YYYY-MM、groupKey=型号、cnt=台数；同一月份内按 cnt 降序。',
  'API_CALL',
  '{"type":"object","properties":{"from":{"type":"string","description":"起始日期 yyyy-MM-dd（含），可选"},"to":{"type":"string","description":"结束日期 yyyy-MM-dd（含），可选"},"warehouseCode":{"type":"string","description":"仓位编码，可选"},"model":{"type":"string","description":"只看某型号时传，可选"},"bigType":{"type":"string","description":"终端大类，可选"}},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/inboundTrend","method":"GET","timeoutSeconds":30,"params":{"granularity":"month","groupBy":"model","from":"${from}","to":"${to}","warehouseCode":"${warehouseCode}","model":"${model}","bigType":"${bigType}"}}',
  500, 'active', '时间维度：型号×月份交叉'
);

-- ---------- 7. 入库量汇总（总量/按仓/按型号，不分时间趋势）----------
INSERT INTO a_llm_tool
  (tool_name, tool_desc, tool_type, input_schema, config_json, result_limit, status, remark)
VALUES (
  'queryInboundSummary',
  '统计入库总量（不按时间分桶）。用户问"一共入库多少台""某段时间入库总量""按仓看入库分布（总量）""某型号累计入库多少"时用它。groupBy=none 只给总量；warehouse 给各仓总量排名；model 给各型号总量排名。不传日期=全部历史。返回 totalCnt 与 groups（groupKey/groupName/cnt）。',
  'API_CALL',
  '{"type":"object","properties":{"from":{"type":"string","description":"起始日期 yyyy-MM-dd（含），可选；不传=全部历史"},"to":{"type":"string","description":"结束日期 yyyy-MM-dd（含），可选"},"warehouseCode":{"type":"string","description":"仓位编码，可选"},"model":{"type":"string","description":"终端型号，可选"},"bigType":{"type":"string","description":"终端大类，可选"},"groupBy":{"type":"string","enum":["none","warehouse","model"],"description":"分组口径：none=只给总量（默认）；warehouse=按仓；model=按型号"}},"required":[]}',
  '{"serviceName":"aismp-agent","endpoint":"/terminal/inventory/inboundSummary","method":"GET","timeoutSeconds":30,"params":{"from":"${from}","to":"${to}","warehouseCode":"${warehouseCode}","model":"${model}","bigType":"${bigType}","groupBy":"${groupBy}"}}',
  200, 'active', '时间维度：入库总量汇总'
);

-- ---------- 8. 技能包：时间维度查询规范 ----------
INSERT INTO a_agent_skill (skill_code, skill_name, description, prompt_fragment, examples, status)
VALUES (
  'inbound_time_query',
  '入库时间维度查询规范',
  '按月/年/日期查入库量时的口径与工具选择：趋势用月、对比用月对比、日粒度要区间',
  '【入库时间维度查询规范】\n- 时间维度查的是「首次入库时间」（历史视角）：设备 2025-06 入库、后来调拨走，仍记在 2025-06。\n- 工具选择：\n  · 按月看趋势 → queryInboundByMonth\n  · 按年对比 → queryInboundByYear\n  · 指定某几天/某段日期 → queryInboundByDay（必须给 from/to，跨度≤366 天）\n  · 指定 2~3 个月两两对比 → compareInboundByMonths\n  · 仓位×月份交叉 → queryInboundByWarehouseByMonth\n  · 型号×月份交叉 → queryInboundByModelByMonth\n  · 只问总量/按仓按型号排名（不分时间）→ queryInboundSummary\n- 用户没给年份只说"6 月"：用最近一个可查到的口径并在回答里写清实际月份（如 2025-06），不要臆造。\n- 引用数字只能来自工具返回的 cnt / totalCnt，不要自己求和或估算。\n- 回答先给结论（哪个月最多、差距多少台），再按时间顺序列明细；对比类问题给一张小表格。',
  '[{"问":"6月和8月入库量对比","答":"调用 compareInboundByMonths(months=\"2025-06,2025-08\")"},{"问":"近半年每个月的入库量","答":"调用 queryInboundByMonth(from=\"2025-04-01\")"},{"问":"2025年6月1日到6月30日每天入库多少","答":"调用 queryInboundByDay(from=\"2025-06-01\",to=\"2025-06-30\")"},{"问":"这两年哪年入库多","答":"调用 queryInboundByYear()"}]',
  'active'
)
ON DUPLICATE KEY UPDATE skill_name = VALUES(skill_name), description = VALUES(description),
  prompt_fragment = VALUES(prompt_fragment), examples = VALUES(examples), update_time = CURRENT_TIMESTAMP;

-- ---------- 9. 挂载到「查数助手」 ----------
-- 7 个时间维度工具（排序 20 起，排在原有 7 个时点工具之后）
INSERT IGNORE INTO a_agent_tool_rel (agent_id, tool_id, sort_order)
SELECT a.id, t.id, 20 + ROW_NUMBER() OVER (ORDER BY t.id)
FROM a_agent a JOIN a_llm_tool t
  ON t.tool_name IN ('queryInboundByMonth', 'queryInboundByYear', 'queryInboundByDay',
                     'compareInboundByMonths', 'queryInboundByWarehouseByMonth',
                     'queryInboundByModelByMonth', 'queryInboundSummary')
 WHERE a.agent_code = 'query_assistant';

-- 技能挂载
INSERT IGNORE INTO a_agent_skill_rel (agent_id, skill_id, sort_order)
SELECT a.id, s.id, 10
FROM a_agent a JOIN a_agent_skill s ON s.skill_code = 'inbound_time_query'
 WHERE a.agent_code = 'query_assistant';

-- ---------- 10. 描述更新 + 版本号 +1（触发热更新，AgentRegistry 按 version 装配）----------
UPDATE a_agent
   SET description = '面向库管员的查数助手：查库存、查串码、看分布统计，支持按月/按年/按日期查入库量',
       version = version + 1
 WHERE agent_code = 'query_assistant';

-- ---------- 校验 ----------
SELECT a.agent_code AS 数字员工, a.version AS 版本, COUNT(t.id) AS 挂载工具数
FROM a_agent a
LEFT JOIN a_agent_tool_rel r ON r.agent_id = a.id
LEFT JOIN a_llm_tool t ON t.id = r.tool_id AND t.status = 'active'
WHERE a.agent_code = 'query_assistant'
GROUP BY a.id;

SELECT t.tool_name AS 时间维度工具, t.status AS 状态
FROM a_llm_tool t
WHERE t.tool_name LIKE 'queryInbound%' OR t.tool_name = 'compareInboundByMonths'
ORDER BY t.id;

SELECT CONCAT('查数助手挂载：工具 ', (SELECT COUNT(*) FROM a_agent_tool_rel r
        JOIN a_agent a ON a.id = r.agent_id WHERE a.agent_code = 'query_assistant'),
       ' 个；技能 ', (SELECT COUNT(*) FROM a_agent_skill_rel r
        JOIN a_agent a ON a.id = r.agent_id WHERE a.agent_code = 'query_assistant'), ' 个') AS 汇总;