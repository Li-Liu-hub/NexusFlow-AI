-- ============================================================================
-- 数字员工内核 · P1 数据模型
-- ============================================================================
-- 目标库：terminal_db（本机）
--
-- 设计原则：**只新增，不修改任何已有表**
--   已有的 a_llm_tool（19 条声明式工具定义）、a_knowledge_base、a_chat_conversation、
--   a_token_usage_* 等全部原样复用；本文件只补「把工具和知识库组装成数字员工」缺的那一块。
--
-- 执行：mysql -uroot -p123456 terminal_db < 60-digital-human-schema.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 数字员工定义（核心表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_agent` (
  `id`                bigint       NOT NULL AUTO_INCREMENT,
  `agent_code`        varchar(64)  NOT NULL COMMENT '数字员工编码，运行时按它装配',
  `agent_name`        varchar(128) NOT NULL COMMENT '显示名',
  `avatar`            varchar(255)          DEFAULT NULL COMMENT '头像 URL',
  `description`       varchar(500)          DEFAULT NULL COMMENT '一句话介绍',
  `system_prompt`     text         NOT NULL COMMENT '人设提示词（基底）',
  `model_name`        varchar(64)  NOT NULL DEFAULT 'mimo-v2.6-flash' COMMENT '模型名',
  `temperature`       decimal(3,2) NOT NULL DEFAULT 0.70,
  `max_turns`         int          NOT NULL DEFAULT 10 COMMENT 'ReAct 最大轮次',
  `knowledge_base_id` bigint                DEFAULT NULL COMMENT '绑定知识库 → a_knowledge_base.id',
  `status`            varchar(16)  NOT NULL DEFAULT 'active' COMMENT 'active/disabled',
  `version`           int          NOT NULL DEFAULT 1 COMMENT '配置版本号，任何变更 +1（热更新依据）',
  `tenant_code`       varchar(32)           DEFAULT NULL COMMENT '租户编码，空=不限',
  `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_code` (`agent_code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数字员工定义';

-- ---------------------------------------------------------------------------
-- 2. 技能包（提示词片段 + Few-shot，可跨数字员工复用）
--    对应「创建 skill」：一段可复用的能力描述，挂给任意数字员工
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_agent_skill` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `skill_code`      varchar(64)  NOT NULL COMMENT '技能编码',
  `skill_name`      varchar(128) NOT NULL COMMENT '技能名',
  `description`     varchar(500)          DEFAULT NULL COMMENT '技能描述（渐进披露时给 LLM 看）',
  `prompt_fragment` text                  DEFAULT NULL COMMENT '追加到 system_prompt 之后',
  `examples`        text                  DEFAULT NULL COMMENT 'Few-shot 示例（JSON 数组）',
  `status`          varchar(16)  NOT NULL DEFAULT 'active',
  `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_code` (`skill_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技能包';

-- ---------------------------------------------------------------------------
-- 3. 挂载关系（多对多 + 顺序）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_agent_skill_rel` (
  `agent_id`   bigint NOT NULL,
  `skill_id`   bigint NOT NULL,
  `sort_order` int    NOT NULL DEFAULT 0 COMMENT '拼接顺序',
  PRIMARY KEY (`agent_id`, `skill_id`),
  KEY `idx_skill` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数字员工-技能 挂载';

CREATE TABLE IF NOT EXISTS `a_agent_tool_rel` (
  `agent_id`   bigint NOT NULL,
  `tool_id`    bigint NOT NULL COMMENT '→ a_llm_tool.id',
  `sort_order` int    NOT NULL DEFAULT 0,
  PRIMARY KEY (`agent_id`, `tool_id`),
  KEY `idx_tool` (`tool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数字员工-工具 挂载';

-- ---------------------------------------------------------------------------
-- 4. 种子数据：一个能查库的「终端管理数字员工」
-- ---------------------------------------------------------------------------

INSERT INTO `a_agent`
  (`agent_code`, `agent_name`, `description`, `system_prompt`,
   `model_name`, `temperature`, `max_turns`, `status`, `version`, `tenant_code`)
VALUES (
  'terminal_manager',
  '终端管理助手',
  '面向终端库存管理的数字员工：生成报表、生成补仓建议、回答流程问题',
  '你是「终端 AI 智慧管理平台」的终端管理助手。\n\n能力范围：\n1. 生成报表：库存统计与分布（总库存、可用、长库龄、各仓位、各型号、库龄分布）\n2. 生成补仓建议：识别库存偏低的仓位与型号，给出「建议调拨 / 建议采购」的方向与数量依据\n3. 流程问答：领用、回收、盘点、调拨等终端管理操作规范\n\n回答要求：\n- 涉及库存数字时，必须调用工具查询真实数据，绝不编造\n- 需要统计数字（多少台、各状态分布）时，必须调用对应的统计工具，或基于工具返回的完整记录逐条计数；严禁凭印象给出汇总数字\n- 工具返回的 count 字段是本次返回的记录总数，汇总时应原样引用\n- 报表类问题：用表格列出关键数字，并给一句简短解读（如长库龄占比、分布不均衡点）\n- 补仓建议类问题：先取各仓库存水位，再指出「哪个仓缺、缺多少、可从哪个仓调入」，调拨不足的部分再建议采购\n- 流程类问题：必须先调用知识检索工具查操作手册，禁止凭内置知识回答\n- 工具返回空结果时，如实说明「没有查到」，不要猜测\n- 回答简洁，先给结论再给细节\n- 单位统一用「台」，库位名称原样引用工具返回值',
  'mimo-v2.6-flash',
  0.70,
  10,
  'active',
  1,
  NULL
)
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP;

INSERT INTO `a_agent_skill`
  (`skill_code`, `skill_name`, `description`, `prompt_fragment`, `examples`, `status`)
VALUES (
  'inventory_query',
  '库存查询规范',
  '按库位/型号/状态查询终端库存时的口径与表达规范',
  '【库存查询规范】\n- 「库存」默认指 status = IN_STOCK（在库可用）的终端，除非用户明确要求其他状态\n- 库位名称支持模糊匹配，用户说「西湖」时按关键字检索，不要要求用户给全称\n- 查询结果超过 20 条时，先给汇总数字，再问用户是否要看明细',
  '[{"问":"西湖有哪些终端","答":"调用 queryStockByLocation(locationName=\"西湖\")"},{"问":"库存情况怎么样","答":"调用 countStockByStatus()"}]',
  'active'
)
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP;

INSERT INTO `a_agent_skill`
  (`skill_code`, `skill_name`, `description`, `prompt_fragment`, `examples`, `status`)
VALUES (
  'trace_terminal',
  '设备溯源规范',
  '按串码追溯单台设备的位置、状态与归属',
  '【设备溯源规范】\n- 用户给出串码时，用 queryTerminalByCode 查详情\n- 返回时要说清「当前在哪（库位）+ 什么状态 + 归谁」，这是用户最关心的三件事\n- 串码查不到时，提示用户核对串码格式（如 CPE202600001）',
  '[{"问":"CPE202600001 现在在哪","答":"调用 queryTerminalByCode(terminalCode=\"CPE202600001\")"}]',
  'active'
)
ON DUPLICATE KEY UPDATE `update_time` = CURRENT_TIMESTAMP;

-- 挂载关系：数字员工 ← 2 个技能 + 4 个查库工具
INSERT IGNORE INTO `a_agent_skill_rel` (`agent_id`, `skill_id`, `sort_order`)
SELECT a.id, s.id, 1 FROM `a_agent` a, `a_agent_skill` s
WHERE a.agent_code = 'terminal_manager' AND s.skill_code = 'inventory_query';

INSERT IGNORE INTO `a_agent_skill_rel` (`agent_id`, `skill_id`, `sort_order`)
SELECT a.id, s.id, 2 FROM `a_agent` a, `a_agent_skill` s
WHERE a.agent_code = 'terminal_manager' AND s.skill_code = 'trace_terminal';

INSERT IGNORE INTO `a_agent_tool_rel` (`agent_id`, `tool_id`, `sort_order`)
SELECT a.id, t.id, 1 FROM `a_agent` a, `a_llm_tool` t
WHERE a.agent_code = 'terminal_manager' AND t.tool_name = 'queryStockByLocation';

INSERT IGNORE INTO `a_agent_tool_rel` (`agent_id`, `tool_id`, `sort_order`)
SELECT a.id, t.id, 2 FROM `a_agent` a, `a_llm_tool` t
WHERE a.agent_code = 'terminal_manager' AND t.tool_name = 'queryTerminalByCode';

INSERT IGNORE INTO `a_agent_tool_rel` (`agent_id`, `tool_id`, `sort_order`)
SELECT a.id, t.id, 3 FROM `a_agent` a, `a_llm_tool` t
WHERE a.agent_code = 'terminal_manager' AND t.tool_name = 'countStockByStatus';

INSERT IGNORE INTO `a_agent_tool_rel` (`agent_id`, `tool_id`, `sort_order`)
SELECT a.id, t.id, 4 FROM `a_agent` a, `a_llm_tool` t
WHERE a.agent_code = 'terminal_manager' AND t.tool_name = 'countStockByCity';
