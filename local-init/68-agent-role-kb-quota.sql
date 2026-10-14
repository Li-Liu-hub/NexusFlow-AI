-- ============================================================================
-- 68 · 角色分配 / 智能路由 / token 用量配额 / 知识库挂载
-- ============================================================================
-- 目标库：terminal_db（本机）
--
-- 四件事：
--   1. 角色字典 + 角色↔数字员工分配（a_role_def / a_agent_role_rel）
--   2. token 月度配额（a_token_quota）—— 配合 a_token_usage_daily 落账后校验
--   3. 给 a_token_usage_daily 补 agent_code 参考列（可空，不改唯一键）
--   4. 演示种子：四角色分配到现有数字员工；示例配额；把「终端业务规范库」
--      挂给运维助手（知识库挂载走 a_agent.knowledge_base_id 单列）
-- 执行：mysql -uroot -p123456 terminal_db < 68-agent-role-kb-quota.sql
-- ============================================================================

USE terminal_db;

-- ---------------------------------------------------------------------------
-- 1. 角色字典
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_role_def` (
  `id`          bigint       NOT NULL AUTO_INCREMENT,
  `role_code`   varchar(32)  NOT NULL COMMENT '角色编码（portal 角色切换器用）',
  `role_name`   varchar(64)  NOT NULL COMMENT '显示名',
  `description` varchar(200)          DEFAULT NULL COMMENT '角色职责说明',
  `sort_order`  int          NOT NULL DEFAULT 0,
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务角色字典';

INSERT INTO `a_role_def` (`role_code`, `role_name`, `description`, `sort_order`) VALUES
  ('keeper',  '库管员',     '仓/库位日常收发存操作，关心库存明细与串码', 1),
  ('manager', '营业厅经理', '关心库存水位、补货建议与经营统计',           2),
  ('ops',     '运维专员',   '关心终端装机/回收/故障流程与业务规范',       3),
  ('admin',   '分公司管理员', '全部能力：查数、补仓、运维规范',           4)
ON DUPLICATE KEY UPDATE `role_name` = VALUES(`role_name`);

-- ---------------------------------------------------------------------------
-- 2. 角色 ↔ 数字员工 分配（多对多）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_agent_role_rel` (
  `agent_id`   bigint NOT NULL COMMENT '→ a_agent.id',
  `role_code`  varchar(32) NOT NULL COMMENT '→ a_role_def.role_code',
  `sort_order` int    NOT NULL DEFAULT 0 COMMENT '路由候选顺序（同分时靠前优先）',
  PRIMARY KEY (`agent_id`, `role_code`),
  KEY `idx_role` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-数字员工分配';

-- 分配策略：库管员 → 查数；经理 → 查数+补仓；运维 → 运维+查数；管理员 → 全部
INSERT IGNORE INTO `a_agent_role_rel` (`agent_id`, `role_code`, `sort_order`)
SELECT a.id, r.role_code,
       CASE r.role_code WHEN 'keeper' THEN 1 WHEN 'manager' THEN 1
                        WHEN 'ops' THEN 2 WHEN 'admin' THEN 1 ELSE 9 END
FROM `a_agent` a, `a_role_def` r
WHERE a.agent_code = 'query_assistant'
  AND r.role_code IN ('keeper','manager','ops','admin');

INSERT IGNORE INTO `a_agent_role_rel` (`agent_id`, `role_code`, `sort_order`)
SELECT a.id, r.role_code,
       CASE r.role_code WHEN 'manager' THEN 2 WHEN 'admin' THEN 2 ELSE 9 END
FROM `a_agent` a, `a_role_def` r
WHERE a.agent_code = 'replenish_advisor'
  AND r.role_code IN ('manager','admin');

INSERT IGNORE INTO `a_agent_role_rel` (`agent_id`, `role_code`, `sort_order`)
SELECT a.id, r.role_code,
       CASE r.role_code WHEN 'ops' THEN 1 WHEN 'admin' THEN 3 ELSE 9 END
FROM `a_agent` a, `a_role_def` r
WHERE a.agent_code = 'ops_assistant'
  AND r.role_code IN ('ops','admin');

-- ---------------------------------------------------------------------------
-- 3. token 月度配额
--    scope=USER   → target_code = user_id
--    scope=AGENT  → target_code = agent_code（按 a_agent_role_rel 之外维度约束）
--    scope=TENANT → target_code = ent_code
--    monthly_tokens = NULL 表示不限量（只记录不限制）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_token_quota` (
  `id`             bigint       NOT NULL AUTO_INCREMENT,
  `scope`          varchar(16)  NOT NULL COMMENT 'USER/AGENT/TENANT',
  `target_code`    varchar(64)  NOT NULL COMMENT '按 scope 解释：userId / agentCode / entCode',
  `monthly_tokens` bigint       NOT NULL COMMENT '月度 token 上限（0=封禁，NULL 不支持，用大数表示不限）',
  `status`         varchar(16)  NOT NULL DEFAULT 'active' COMMENT 'active/disabled',
  `remark`         varchar(200)          DEFAULT NULL,
  `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_target` (`scope`, `target_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='token 月度配额';

-- 示例：门户演示账号每月 200 万 token；管理员不限（9.9 亿）
INSERT INTO `a_token_quota` (`scope`, `target_code`, `monthly_tokens`, `remark`) VALUES
  ('USER', 'portal_user', 2000000, '门户演示账号月配额 200 万'),
  ('USER', 'admin',       999000000, '管理员不限量')
ON DUPLICATE KEY UPDATE `monthly_tokens` = VALUES(`monthly_tokens`), `remark` = VALUES(`remark`);

-- ---------------------------------------------------------------------------
-- 4. a_token_usage_daily 补 agent_code 参考列（可空，不动唯一键 ——
--    唯一键仍是 (ent,user,date,model)，agent 维度只做展示参考）
-- ---------------------------------------------------------------------------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA='terminal_db' AND TABLE_NAME='a_token_usage_daily'
               AND COLUMN_NAME='agent_code');
SET @ddl := IF(@col=0,
  'ALTER TABLE `a_token_usage_daily` ADD COLUMN `agent_code` varchar(64) NULL COMMENT ''产生用量的数字员工（参考维度）'' AFTER `model`',
  'SELECT ''agent_code 已存在，跳过''');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 5. 知识库挂载演示：a_agent.knowledge_base_id → a_knowledge_base.id
--    运维助手 / 终端管理助手 挂「终端业务规范库」（2 篇文档 34 分片）
-- ---------------------------------------------------------------------------
UPDATE `a_agent` SET `knowledge_base_id` = (SELECT id FROM `a_knowledge_base` WHERE name='终端业务规范库' LIMIT 1)
WHERE `agent_code` IN ('ops_assistant','terminal_manager') AND `knowledge_base_id` IS NULL;
