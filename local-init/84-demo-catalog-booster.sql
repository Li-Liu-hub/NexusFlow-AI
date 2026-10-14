-- ============================================================================
-- 84 · 管理台目录数据充实：技能包扩充 + MCP 服务扩充 + 编排层模型名修正
-- ============================================================================
-- 目标库：terminal_db（本机）；服务器为 systemd 直跑，terminal_db 仅 root 可访问，
--   执行时用凭据文件 /opt/agent-platform-secure/credentials.env 的 MYSQL_ROOT_PW。
--
-- 三件事：
--   1. 编排层 5 个 LLM 节点的 model_name 存量修正：
--      83 号种子登记时写的是 glm-5.2（z.ai 时代镜像），此后编排层 .env 已切回
--      mimo-v2.6-flash @ api.xiaomimimo.com（本机与服务器一致，key 来自 MIMO_API_KEY）。
--      ON DUPLICATE KEY UPDATE 只刷名称不刷模型列，故这里用 UPDATE 修正存量行，
--      让「数字员工」页展示的模型与实际运行一致。
--   2. 技能包 +7：均为「规范类」提示词片段，只入册不挂载（不改任何数字员工行为，
--      挂载动作留给管理台人工操作），让「技能管理」页形成完整的能力规范目录。
--   3. MCP 服务 +6：2 个真实公网 MCP server（可在管理台「探测」验证连通）+
--      4 个待接入示例（disabled，说明外部系统规划），让「MCP 服务」页从 1 条
--      扩成完整的接入台账。
--
-- 幂等：技能 ON DUPLICATE KEY UPDATE 刷新描述与片段；MCP 只刷名称（不覆盖
--   管理台后续人工编辑的状态 / 地址）；模型名修正可重复执行。
-- 执行（本机）：mysql -uroot -p123456 terminal_db < 84-demo-catalog-booster.sql
-- 执行（服务器）：见 deploy 脚本，mysql -uroot -p"$MYSQL_ROOT_PW" terminal_db < 84-...
-- ============================================================================

USE terminal_db;

-- ---------------------------------------------------------------------------
-- 1. 编排层 5 节点 model_name 修正（与 .env 实际值对齐）
-- ---------------------------------------------------------------------------
UPDATE a_agent
   SET model_name = 'mimo-v2.6-flash'
 WHERE agent_code IN ('intent_router', 'chart_planner', 'replenish_reporter',
                      'order_parser', 'quality_critic')
   AND model_name <> 'mimo-v2.6-flash';

-- ---------------------------------------------------------------------------
-- 2. 技能包 +7（只入册不挂载：不改变任何数字员工的运行时行为）
-- ---------------------------------------------------------------------------

-- 2.1 调拨工单流转规范（与调拨链路重构后的状态机一致）
INSERT INTO a_agent_skill (skill_code, skill_name, description, prompt_fragment, examples, status)
VALUES (
  'transfer_order_spec',
  '调拨工单流转规范',
  '调拨链路的状态机口径：A仓账管申请 → B仓建单 → B仓仓管导入SN确认出库 → A仓仓管确认/驳回入库',
  '【调拨工单流转规范】\n- 调拨链路固定四步：① A仓账管发起调拨申请；② B仓账管/仓管据申请创建调拨工单；③ B仓仓管收到调拨提醒后导入SN码并确认出库；④ A仓仓管收到入库申请后确认或驳回入库。\n- 状态口径：APPLIED（已申请）→ OUTBOUND（出库确认）→ DONE（完成），驳回则退回并可重新发起。\n- 事务性动作（确认出库 / 确认入库 / 驳回）必须由对应仓库的仓管人在页面上点按钮完成，助手只能讲解流程与状态，不能代替操作。\n- 用户问「单子到哪一步了」时，按上述四步解释当前状态含义，不要臆造中间状态。',
  '[{"问":"调拨单被驳回了怎么办","答":"解释驳回后可修改重新发起，并说明驳回发生在A仓入库确认环节"},{"问":"调拨流程怎么走","答":"按四步链路讲解，强调SN导入出库与入库确认两个仓管动作"}]',
  'active'
)
ON DUPLICATE KEY UPDATE skill_name = VALUES(skill_name), description = VALUES(description),
  prompt_fragment = VALUES(prompt_fragment), examples = VALUES(examples), update_time = CURRENT_TIMESTAMP;

-- 2.2 领用与回收规范
INSERT INTO a_agent_skill (skill_code, skill_name, description, prompt_fragment, examples, status)
VALUES (
  'issue_return_spec',
  '领用与回收规范',
  '终端领用、回收、退库的操作口径与状态变化说明',
  '【领用与回收规范】\n- 领用：终端从「在库可用（IN_STOCK）」变为「已领用（ISSUED）」，必须登记领用人与用途；营业厅领用按装机单批量领用。\n- 回收：回收后先入「待盘点（RECYCLED_PENDING）」，质检通过才回「在库可用」；质检不好转「维修/报废」分支。\n- 回答时讲清状态变化与需要的登记项，具体数字必须来自工具查询。',
  '[{"问":"营业厅领机流程","答":"讲清领用登记项与状态变化，提示走装机单批量领用"},{"问":"回收的机器还能再发吗","答":"说明回收后需质检回库才能再领用"}]',
  'active'
)
ON DUPLICATE KEY UPDATE skill_name = VALUES(skill_name), description = VALUES(description),
  prompt_fragment = VALUES(prompt_fragment), examples = VALUES(examples), update_time = CURRENT_TIMESTAMP;

-- 2.3 盘点作业规范
INSERT INTO a_agent_skill (skill_code, skill_name, description, prompt_fragment, examples, status)
VALUES (
  'inventory_count_spec',
  '盘点作业规范',
  '定期盘点、抽盘的操作口径：以系统为准还是以实盘为准、差异如何处理',
  '【盘点作业规范】\n- 盘点以系统台账为底稿、实盘为准：先按库位导出盘点清单，逐一扫码核对。\n- 差异处理：盘盈走「补录入库」登记来源；盘亏先查在途/借出再定差异，禁止直接改状态抹平。\n- 回答时提示盘点期间冻结该库位的调拨与领用操作，避免账实再度漂移。',
  '[{"问":"月底盘点怎么弄","答":"给出按库位导清单→扫码核对→差异处理的步骤"},{"问":"盘亏了两台怎么办","答":"先查在途与借出，再按盘亏流程登记，禁止直接改状态"}]',
  'active'
)
ON DUPLICATE KEY UPDATE skill_name = VALUES(skill_name), description = VALUES(description),
  prompt_fragment = VALUES(prompt_fragment), examples = VALUES(examples), update_time = CURRENT_TIMESTAMP;

-- 2.4 报废与利旧规范
INSERT INTO a_agent_skill (skill_code, skill_name, description, prompt_fragment, examples, status)
VALUES (
  'scrap_disposal_spec',
  '报废与利旧规范',
  '长库龄终端的报废条件与利旧复投口径（与切片验收语料同一口径）',
  '【报废与利旧规范】\n- 报废条件：在库且库龄超期（默认 >180 天）且质检不合格的终端方可申请报废；质检合格的优先利旧复投。\n- 利旧复投：长库龄但功能完好的终端优先投向新装机需求，复投后库龄重新起算并在备注登记原入库批次。\n- 口径与「利旧复投与折旧规则」语料保持一致，回答引用同一套规则，不要两套说法。',
  '[{"问":"库龄一年的机器怎么处理","答":"先质检：合格走利旧复投，不合格走报废申请"},{"问":"利旧之后库龄怎么算","答":"复投后库龄重新起算，备注登记原入库批次"}]',
  'active'
)
ON DUPLICATE KEY UPDATE skill_name = VALUES(skill_name), description = VALUES(description),
  prompt_fragment = VALUES(prompt_fragment), examples = VALUES(examples), update_time = CURRENT_TIMESTAMP;

-- 2.5 库存告警处置规范
INSERT INTO a_agent_skill (skill_code, skill_name, description, prompt_fragment, examples, status)
VALUES (
  'alarm_response_spec',
  '库存告警处置规范',
  '低水位/长库龄告警的处置顺序：先看建议卡再建调拨，通知同仓只刷新不新增',
  '【库存告警处置规范】\n- 收到低水位告警：先在库存健康卡查看补货建议（同层均值比较口径），再从建议卡发起调拨申请；调拨不足转采购。\n- 长库龄告警：对照「报废与利旧规范」处置，优先盘活。\n- 通知口径：同仓未读告警只刷新不新增，处理完成后标记已读；助手解释通知含义时不重复报数。',
  '[{"问":"收到缺货告警该干嘛","答":"先看库存健康卡建议，再发起调拨申请，不足转采购"},{"问":"为什么告警通知不增加条数","答":"解释同仓未读只刷新不新增的机制"}]',
  'active'
)
ON DUPLICATE KEY UPDATE skill_name = VALUES(skill_name), description = VALUES(description),
  prompt_fragment = VALUES(prompt_fragment), examples = VALUES(examples), update_time = CURRENT_TIMESTAMP;

-- 2.6 数据口径与脱敏规范
INSERT INTO a_agent_skill (skill_code, skill_name, description, prompt_fragment, examples, status)
VALUES (
  'data_caliber_spec',
  '数据口径与脱敏规范',
  '库存数字的统计口径与演示数据脱敏要求：伪串码、预设机构与操作员',
  '【数据口径与脱敏规范】\n- 库存数字口径：默认统计 status=IN_STOCK；总量=在库+在途；库龄按首次入库时间起算，与时间维度工具口径一致。\n- 演示环境数据全部为虚拟数据：串码为 md5 伪串码（如 CPE202600001），机构仅限东宁市相关分公司，操作人仅限东宁操作员A-E。\n- 被问到真实用户信息时，说明系统数据已脱敏，不提供真实串码与人名。',
  '[{"问":"能查真实用户的串码吗","答":"说明演示数据已脱敏，仅提供伪串码"},{"问":"库存总量怎么算的","答":"解释在库+在途的口径"}]',
  'active'
)
ON DUPLICATE KEY UPDATE skill_name = VALUES(skill_name), description = VALUES(description),
  prompt_fragment = VALUES(prompt_fragment), examples = VALUES(examples), update_time = CURRENT_TIMESTAMP;

-- 2.7 工具异常应对规范
INSERT INTO a_agent_skill (skill_code, skill_name, description, prompt_fragment, examples, status)
VALUES (
  'tool_failure_fallback',
  '工具异常应对规范',
  '工具调用失败/超时/返空时的兜底话术：如实说明、建议换问法、不编数字',
  '【工具异常应对规范】\n- 工具返回空：如实说「没有查到」，并提示核对库位名/串码格式后重试。\n- 工具超时或报错：说明「数据服务暂时不可用」，建议稍后重试，禁止编造数字兜底。\n- 连续失败：引导用户换一种问法（如把模糊库位名换成仓库名），或建议直接在终端管理页查询。',
  '[{"问":"(工具超时后)到底多少台","答":"说明服务暂时不可用，不编数字，建议稍后重试"},{"问":"查不到这个库位","答":"提示库位名支持模糊匹配，建议换关键字重试"}]',
  'active'
)
ON DUPLICATE KEY UPDATE skill_name = VALUES(skill_name), description = VALUES(description),
  prompt_fragment = VALUES(prompt_fragment), examples = VALUES(examples), update_time = CURRENT_TIMESTAMP;

-- ---------------------------------------------------------------------------
-- 3. MCP 服务 +6（2 个真实公网服务示例 + 4 个待接入示例，均 disabled：
--    公网两条在服务器出网受限时探测不通，启用前先在管理台验证连通）
--    只刷名称：不覆盖管理台后续人工编辑的状态 / 地址 / 鉴权配置。
-- ---------------------------------------------------------------------------

-- 3.1 DeepWiki（真实公网 MCP：GitHub 仓库文档查询；服务器出网受限探测不通，
--     与 api.z.ai 直连被干扰同理，本地挂代理环境可启用探测）
INSERT INTO a_mcp_server (server_code, server_name, transport, base_url, headers_json, auth_type, status)
VALUES ('deepwiki-mcp', 'DeepWiki 仓库文档（公网示例）', 'SSE', 'https://mcp.deepwiki.com/sse', NULL, 'NONE', 'disabled')
ON DUPLICATE KEY UPDATE server_name = VALUES(server_name);

-- 3.2 Context7（真实公网 MCP：框架/库文档检索；同上，探测需代理出网）
INSERT INTO a_mcp_server (server_code, server_name, transport, base_url, headers_json, auth_type, status)
VALUES ('context7-mcp', 'Context7 库文档检索（公网示例）', 'SSE', 'https://mcp.context7.com/sse', NULL, 'NONE', 'disabled')
ON DUPLICATE KEY UPDATE server_name = VALUES(server_name);

-- 3.3 高德地图（待接入：需申请服务 key 后启用，走 header 带凭证）
INSERT INTO a_mcp_server (server_code, server_name, transport, base_url, headers_json, auth_type, status)
VALUES ('amap-mcp', '高德地图服务（待接入）', 'SSE', 'https://mcp.amap.com/sse', NULL, 'HEADER', 'disabled')
ON DUPLICATE KEY UPDATE server_name = VALUES(server_name);

-- 3.4 钉钉工作通知（待接入：营业厅装机通知等场景）
INSERT INTO a_mcp_server (server_code, server_name, transport, base_url, headers_json, auth_type, status)
VALUES ('dingtalk-mcp', '钉钉工作通知（待接入）', 'SSE', 'https://api.dingtalk.com/v1/mcp',
        '{"x-acs-dingtalk-access-token":"<开通后填写>"}', 'HEADER', 'disabled')
ON DUPLICATE KEY UPDATE server_name = VALUES(server_name);

-- 3.5 本地文件系统 MCP（待接入：工单附件归档等场景）
INSERT INTO a_mcp_server (server_code, server_name, transport, base_url, headers_json, auth_type, status)
VALUES ('fs-local-mcp', '本地文件系统（待接入）', 'HTTP', 'http://127.0.0.1:9100/mcp', NULL, 'NONE', 'disabled')
ON DUPLICATE KEY UPDATE server_name = VALUES(server_name);

-- 3.6 报表导出 MCP（待接入：EasyExcel 报表渲染服务）
INSERT INTO a_mcp_server (server_code, server_name, transport, base_url, headers_json, auth_type, status)
VALUES ('report-export-mcp', '报表导出服务（待接入）', 'HTTP', 'http://127.0.0.1:9101/mcp', NULL, 'NONE', 'disabled')
ON DUPLICATE KEY UPDATE server_name = VALUES(server_name);

-- ---------------------------------------------------------------------------
-- 校验
-- ---------------------------------------------------------------------------
SELECT '=== 编排层模型名（应全部 mimo-v2.6-flash）===' AS check_item;
SELECT agent_code, model_name FROM a_agent
 WHERE agent_code IN ('intent_router', 'chart_planner', 'replenish_reporter',
                      'order_parser', 'quality_critic');

SELECT '=== 技能总数 ===' AS check_item;
SELECT COUNT(*) AS skill_total FROM a_agent_skill;

SELECT '=== MCP 服务台账 ===' AS check_item;
SELECT server_code, server_name, transport, status FROM a_mcp_server ORDER BY id;
