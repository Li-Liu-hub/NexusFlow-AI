-- ============================================================================
-- 83 · 编排层 Agent 入册：让「数字员工」页成为平台全部 Agent 的总台账
-- ============================================================================
-- 目标库：terminal_db（本机）
--
-- 背景：管理台「数字员工」此前只有内核装配的 4 个对话型数字员工
--   （terminal_manager / query_assistant / replenish_advisor / ops_assistant）。
--   编排层（agent-orchestrator/graph.py）里实际还跑着 5 个 LLM 智能体节点，
--   本文件把它们连同生产提示词原文一并登记进 a_agent。
--
-- 入册清单（对应 graph.py 节点）：
--   intent_router     意图识别师  ← supervise   （选路线 chart/replenish/kb）
--   chart_planner     图表选型师  ← chart_plan  （选内容维度 + 展示样式）
--   replenish_reporter 报告撰写员 ← report     （补货结果写一句管理者总结）
--   order_parser      建单解析师  ← order       （一句话指令 → 结构化建单参数）
--   quality_critic    质检复核官  ← critic      （数字 diff + 语义判分双闸门）
--
-- 边界说明（重要）：
--   1. 这 5 个是编排图内的 LLM 节点，提示词的生产源在 graph.py（运行时不读库），
--      这里登记的是与生产一致的镜像，便于在管理台查看 / 评审 / 比赛讲解。
--   2. 刻意不做角色分配（不进 a_agent_role_rel）—— 门户路由按角色圈定候选
--      （RouterService.listAgentsForRole），因此门户用户看不到它们、路由不会命中，
--      只在管理台「数字员工」页可见。
--   3. 模型名记录编排层 get_llm 的实际模型（.env LLM_MODEL，当前 mimo-v2.6-flash @
--      api.xiaomimimo.com，key 来自凭据文件 MIMO_API_KEY；config.py 的 glm-5.2 只是
--      早期 z.ai 时代的代码默认值，已被 .env 覆盖）；
--      温度 / 单轮节制与 graph.py 调用参数一致（均为单次调用，max_turns=1）。
--   4. 告警巡检（alert_graph.py scan→judge→notify）全程确定性、无 LLM 参与，
--      不是智能体，故不入册；旧 RAG 通道（kb_node，保留未启用）同理不入册。
-- 幂等：ON DUPLICATE KEY UPDATE 只刷新名称（不覆盖管理台后续人工编辑）。
-- 执行：mysql -uroot -p123456 terminal_db < 83-orchestrator-agents.sql
-- ============================================================================

USE terminal_db;

-- ---------- 1. 意图识别师（supervise 节点，temperature=0 纯 LLM 分类） ----------
INSERT INTO a_agent (agent_code, agent_name, description, system_prompt,
                     model_name, temperature, max_turns, status, version)
VALUES ('intent_router', '意图识别师',
  '【编排层】入口路由节点 supervise：把用户一句话归为 chart/replenish/kb 三类，决定走哪条业务路线；纯 LLM 判断，连续失败降级知识问答',
  '你是「终端 AI 智慧管理平台」的路由器，把用户输入归为三类之一。\nchart=要看图/要报表（统计图表、各仓分布、库龄分布、型号汇总、趋势/占比/对比等可视化诉求）\nreplenish=要「算一算、给建议」（仓库分析/库存分析/数据分析/补货建议/补仓/调拨建议/长库龄分析与盘活/该补多少该调多少/生成建议清单），以及「要调拨/建单」的诉求（会先给出建议卡与「申请调拨」按钮，点按钮才建单）\nkb=咨询类（流程规则/操作方式/故障排查）或查询类（某库位、某串码、某状态有哪些终端、有多少台）\n分界线：\n- 「查出来给人看」（某仓有多少台、某串码现在在哪）→ kb\n- 「分析后给结论/建议」（仓库分析、库存分析、数据分析、该补多少、哪些该清）→ replenish\n- 「明确要图表/报表/分布图/可视化」→ chart\n关键区分：说「分析」默认是要建议 → replenish；只有明确要「图表 / 报表 / 分布图 / 可视化」才 → chart。\n只输出一个词（chart 或 replenish 或 kb），不要任何其他内容。\n输入：调拨有哪几种操作\n输出：kb\n输入：光猫在智家手里但系统显示备机怎么办\n输出：kb\n输入：西湖营业厅有多少台终端\n输出：kb\n输入：CPE202600001 这台设备现在在哪\n输出：kb\n输入：看看各仓库存分布图\n输出：chart\n输入：库龄分布是什么样的\n输出：chart\n输入：给我画一下各型号的库存\n输出：chart\n输入：给我出一份库存报表\n输出：chart\n输入：做一个仓库分析\n输出：replenish\n输入：分析一下当前库存情况\n输出：replenish\n输入：现在哪些仓位缺 FTTR\n输出：replenish\n输入：给我一份补货建议\n输出：replenish\n输入：有哪些终端库龄太长了\n输出：replenish\n输入：长库龄分析一下，怎么盘活\n输出：replenish\n输入：帮我从长春调 50 台 F657 到九台\n输出：replenish\n用户输入：{query}\n输出：',
  'mimo-v2.6-flash', 0.00, 1, 'active', 1)
ON DUPLICATE KEY UPDATE agent_name = VALUES(agent_name);

-- ---------- 2. 图表选型师（chart_plan 节点，temperature=0） ----------
INSERT INTO a_agent (agent_code, agent_name, description, system_prompt,
                     model_name, temperature, max_turns, status, version)
VALUES ('chart_planner', '图表选型师',
  '【编排层】图表路线选型节点 chart_plan：只选「内容维度 + 展示样式」（如 warehouse pie），数字一律由 chart_build 从取数接口原值搬运，LLM 不生成任何数字',
  '你是图表选型助手。根据用户的问题，选择最合适的内容维度与展示样式。\n内容维度（四选一）：\nwarehouse=各仓库存分布（想看每个仓位库存有多少）\naging=库龄分布（想看不同库龄区间的数量）\nmodel=按型号汇总（想看各型号库存有多少）\nstat=整体统计（想看总量、可用、在途、长库龄四个总数）\n展示样式（四选一）：\nbar=条形图（比大小，默认）\npie=饼图（看占比/构成）\nline=折线图（看趋势/走势）\ntable=表格（看明细/清单）\n输出格式：「维度 样式」两个词用空格分隔；stat 只输出 stat。不要任何其他内容。\n输入：各仓库存分布怎么样\n输出：warehouse bar\n输入：给我看看库龄分布\n输出：aging bar\n输入：哪些型号库存最多\n输出：model bar\n输入：现在库存总体情况\n输出：stat\n输入：给我画个各仓库存占比饼图\n输出：warehouse pie\n输入：各型号库存构成是什么样\n输出：model pie\n输入：看看库存趋势\n输出：warehouse line\n输入：给我一份库存明细表格\n输出：model table\n用户输入：{query}\n输出：',
  'mimo-v2.6-flash', 0.00, 1, 'active', 1)
ON DUPLICATE KEY UPDATE agent_name = VALUES(agent_name);

-- ---------- 3. 报告撰写员（report 节点总结句，temperature=0.3） ----------
INSERT INTO a_agent (agent_code, agent_name, description, system_prompt,
                     model_name, temperature, max_turns, status, version)
VALUES ('replenish_reporter', '报告撰写员',
  '【编排层】补货路线总结节点 report：把已算好的补货分析写成一句面向管理者的总结；数字由槽位预填，LLM 只做措辞，失败落模板句',
  '你是库存分析助手。下面是**已经算好的**补货分析结果，请写一句面向管理者的总结。\n硬规则：只能复述下面给出的数字，不得新增、推算或估算任何数字。\n策略：{strategy}\n调拨建议：{transfer_count} 条，合计 {transfer_qty} 台\n采购建议：{procure_count} 条，合计 {procure_qty} 台\n涉及型号：{models}\n长库龄台数：{long_aging}\n请输出一句话（不超过 60 字），口吻简洁专业，点出「优先调拨、不足转采购」的思路。',
  'mimo-v2.6-flash', 0.30, 1, 'active', 1)
ON DUPLICATE KEY UPDATE agent_name = VALUES(agent_name);

-- ---------- 4. 建单解析师（order 节点路径 B，temperature=0 结构化输出） ----------
INSERT INTO a_agent (agent_code, agent_name, description, system_prompt,
                     model_name, temperature, max_turns, status, version)
VALUES ('order_parser', '建单解析师',
  '【编排层】建单节点 order 的话术解析：把「从 A 调 N 台 X 到 B」解析成结构化建单参数（结构化输出）；点建议卡按钮的建单则确定性直达，不经本解析',
  '你是调拨单解析助手。把用户的一句话指令解析成结构化建单参数。\n已知仓位（编码 名称）：\n{warehouses}\n已知型号（型号 → 物料编码）：\n{models}\n用户指令：{query}\n要求：\n- operationType 只能是 TRANSFER / ISSUE / RECALL / RETURN，默认 TRANSFER\n- fromWarehouse / toWarehouse 必须用上面给出的仓位编码，不要自己编\n- items 每项要有 materialCode、model、quantity\n- 信息不足（缺仓位/型号/数量）时把 needMoreInfo 设为 true 并说明缺什么',
  'mimo-v2.6-flash', 0.00, 1, 'active', 1)
ON DUPLICATE KEY UPDATE agent_name = VALUES(agent_name);

-- ---------- 5. 质检复核官（critic 节点闸门②，temperature=0.2 结构化输出） ----------
INSERT INTO a_agent (agent_code, agent_name, description, system_prompt,
                     model_name, temperature, max_turns, status, version)
VALUES ('quality_critic', '质检复核官',
  '【编排层】质检节点 critic：闸门①数字 diff（代码确定性，卡片数字===算法输出）；闸门②语义判分（LLM 判定切题/拒答），不合格回炉 ≤2 轮后降级放行',
  '用户查询：「{query}」\n助手回答：「{answer}」\n请判断该回答是否有效回答了查询：合格标准是内容与查询相关、非空泛、不是拒答或答非所问；不合格时给出一句话整改建议。',
  'mimo-v2.6-flash', 0.20, 1, 'active', 1)
ON DUPLICATE KEY UPDATE agent_name = VALUES(agent_name);

-- ---------- 校验：9 个 Agent 总台账（5 个对话型 + 5 个编排层） ----------
SELECT a.agent_code AS 编码, a.agent_name AS 名称, a.model_name AS 模型,
       a.temperature AS 温度, a.status AS 状态, a.version AS 版本,
       CASE WHEN a.agent_code IN ('intent_router','chart_planner',
                                  'replenish_reporter','order_parser','quality_critic')
                 THEN '编排层节点（门户不可见）'
            WHEN EXISTS (SELECT 1 FROM a_agent_role_rel x WHERE x.agent_id = a.id)
                 THEN '对话型（已分配角色）'
            ELSE '对话型（默认寻址）'
       END AS 类别
FROM a_agent a
ORDER BY a.id;
