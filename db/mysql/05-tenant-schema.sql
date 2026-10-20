-- ============================================================
-- NexusFlow AI · 多租户（公司）数据模型增量脚本
-- 目标库：api_db（MySQL 8.0+）
-- 特性：幂等，可重复执行；不改动既有表结构语义，只做新增
--
-- 设计口径（与库里既有表保持一致）：
--   公司 = 租户，统一用 ent_code 标识（varchar(32)）
--   已有 a_token_quota(scope='TENANT', target_code=ent_code) 直接复用，本脚本不重建
--   已有 a_token_usage_daily.ent_code 直接复用，本脚本不重建
--
-- 跨库说明：feature_type='NODE' 时，feature_code 指向编排层
--   PG 库 terminal_kb 的 public.orch_node_def.id（varchar(96)），跨库故不建外键。
--
-- 生效链路：
--   公司提交工单 → 总部审批通过 → 写 a_tenant_feature
--   → 该公司画布的可用节点 = orch_node_def 内置节点 ∪ 该公司 a_tenant_feature(NODE)
--   → 门户/管理台可见页面 = a_tenant_page.visible=1 的页面
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. 公司（租户）主表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_tenant` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `ent_code`     varchar(32)  NOT NULL COMMENT '公司编码（租户标识，全局唯一）',
  `ent_name`     varchar(128) NOT NULL COMMENT '公司全称',
  `short_name`   varchar(64)  DEFAULT NULL COMMENT '公司简称（列表/抬头用）',
  `status`       varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  `contact_name` varchar(64)  DEFAULT NULL COMMENT '对接人',
  `contact_phone` varchar(32) DEFAULT NULL COMMENT '对接人电话',
  `remark`       varchar(255) DEFAULT NULL,
  `created_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ent_code` (`ent_code`),
  KEY `idx_tenant_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司（租户）主表';

-- ------------------------------------------------------------
-- 2. 员工归属公司：a_user 增加 ent_code
--    MySQL 8.0 的 ADD COLUMN 不支持 IF NOT EXISTS，用 information_schema 守卫
-- ------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'a_user' AND COLUMN_NAME = 'ent_code'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `a_user` ADD COLUMN `ent_code` varchar(32) DEFAULT NULL COMMENT ''所属公司编码，关联 a_tenant.ent_code''',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'a_user' AND INDEX_NAME = 'idx_user_ent'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `a_user` ADD KEY `idx_user_ent` (`ent_code`)',
  'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. 公司已开通的能力（节点 / 页面 / 工具）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_tenant_feature` (
  `id`           bigint      NOT NULL AUTO_INCREMENT,
  `ent_code`     varchar(32) NOT NULL COMMENT '公司编码',
  `feature_type` varchar(16) NOT NULL COMMENT 'NODE=画布节点 / PAGE=页面 / TOOL=工具',
  `feature_code` varchar(96) NOT NULL COMMENT 'NODE 时为 orch_node_def.id；PAGE 时为 page_code',
  `feature_name` varchar(128) DEFAULT NULL COMMENT '冗余名称，列表直接显示',
  `ticket_no`    varchar(32) DEFAULT NULL COMMENT '来源工单号（内置开通可为空）',
  `granted_by`   varchar(64) DEFAULT NULL COMMENT '开通操作人',
  `granted_at`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ent_feature` (`ent_code`,`feature_type`,`feature_code`),
  KEY `idx_feature_code` (`feature_type`,`feature_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司已开通能力';

-- ------------------------------------------------------------
-- 4. 功能申请工单（公司 → 总部）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_feature_ticket` (
  `id`             bigint       NOT NULL AUTO_INCREMENT,
  `ticket_no`      varchar(32)  NOT NULL COMMENT '工单号 FTyyyyMMddNNNN',
  `ent_code`       varchar(32)  NOT NULL COMMENT '申请公司',
  `feature_type`   varchar(16)  NOT NULL COMMENT 'NODE / PAGE / TOOL',
  `feature_code`   varchar(96)  NOT NULL COMMENT '申请的目标能力编码',
  `feature_name`   varchar(128) DEFAULT NULL COMMENT '申请的能力名称（展示用）',
  `title`          varchar(128) NOT NULL COMMENT '工单标题',
  `reason`         varchar(500) DEFAULT NULL COMMENT '申请理由 / 使用场景',
  `status`         varchar(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED / WITHDRAWN',
  `applicant_code` varchar(64)  NOT NULL COMMENT '申请人账号 a_user.user_code',
  `reviewer_code`  varchar(64)  DEFAULT NULL COMMENT '审批人账号（总部）',
  `review_comment` varchar(500) DEFAULT NULL COMMENT '审批意见',
  `submitted_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `reviewed_at`    datetime     DEFAULT NULL,
  `created_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  KEY `idx_ticket_ent_status` (`ent_code`,`status`),
  KEY `idx_ticket_status_time` (`status`,`submitted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='功能申请工单';

-- ------------------------------------------------------------
-- 5. 工单流转日志（谁在什么时候做了什么，审批留痕）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_feature_ticket_log` (
  `id`            bigint       NOT NULL AUTO_INCREMENT,
  `ticket_no`     varchar(32)  NOT NULL,
  `action`        varchar(16)  NOT NULL COMMENT 'SUBMIT / APPROVE / REJECT / WITHDRAW',
  `operator_code` varchar(64)  NOT NULL,
  `comment`       varchar(500) DEFAULT NULL,
  `created_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_log_ticket` (`ticket_no`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单流转日志';

-- ------------------------------------------------------------
-- 6. 公司页面可见性（每家公司的门户/管理台长得不一样）
--    page_code 约定：PORTAL_HOME / STOCK / TRANSFER / NOTICE / REPORT /
--                    ADMIN_AGENT / ADMIN_CANVAS / ADMIN_QUOTA / ADMIN_AUDIT
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_tenant_page` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `ent_code`   varchar(32) NOT NULL COMMENT '公司编码',
  `page_code`  varchar(64) NOT NULL COMMENT '页面编码',
  `page_name`  varchar(64) DEFAULT NULL COMMENT '页面名称（导航显示）',
  `visible`    tinyint     NOT NULL DEFAULT '1' COMMENT '1=可见 0=隐藏',
  `sort_order` int         NOT NULL DEFAULT '100' COMMENT '导航排序',
  `created_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ent_page` (`ent_code`,`page_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司页面可见性配置';

-- ------------------------------------------------------------
-- 7. 种子：两家示例公司 + 员工归属 + 页面差异 + 一条已授权节点
--    只用 INSERT IGNORE，重复执行不会报错
-- ------------------------------------------------------------
INSERT IGNORE INTO `a_tenant` (`ent_code`,`ent_name`,`short_name`,`status`,`remark`) VALUES
  ('JLCC','联通长春分公司','长春','ACTIVE','二级库主体，全功能'),
  ('JLJT','联通九台分公司','九台','ACTIVE','三级库主体，按需开通');

-- 员工归属（示例账号存在才会生效）
UPDATE `a_user` SET `ent_code` = 'JLCC' WHERE `user_code` IN ('xn_zg','xn_kg','admin') AND (`ent_code` IS NULL OR `ent_code` = '');
UPDATE `a_user` SET `ent_code` = 'JLJT' WHERE `user_code` IN ('dn_zg','dn_kg') AND (`ent_code` IS NULL OR `ent_code` = '');

-- 页面差异：长春全开，九台只开业务页（不给报表/审计）
INSERT IGNORE INTO `a_tenant_page` (`ent_code`,`page_code`,`page_name`,`visible`,`sort_order`) VALUES
  ('JLCC','PORTAL_HOME','首页',1,10),
  ('JLCC','STOCK','库存',1,20),
  ('JLCC','TRANSFER','调拨',1,30),
  ('JLCC','NOTICE','通知',1,40),
  ('JLCC','REPORT','报表',1,50),
  ('JLCC','ADMIN_CANVAS','画布编排',1,60),
  ('JLCC','ADMIN_QUOTA','配额管理',1,70),
  ('JLCC','ADMIN_AUDIT','调用审计',1,80),
  ('JLJT','PORTAL_HOME','首页',1,10),
  ('JLJT','STOCK','库存',1,20),
  ('JLJT','TRANSFER','调拨',1,30),
  ('JLJT','NOTICE','通知',1,40),
  ('JLJT','REPORT','报表',0,50),
  ('JLJT','ADMIN_CANVAS','画布编排',1,60),
  ('JLJT','ADMIN_QUOTA','配额管理',0,70),
  ('JLJT','ADMIN_AUDIT','调用审计',0,80);

-- token 配额：按公司维度分配（复用既有 a_token_quota，scope=TENANT）
INSERT IGNORE INTO `a_token_quota` (`scope`,`target_code`,`monthly_tokens`,`status`,`remark`) VALUES
  ('TENANT','JLCC',5000000,'active','长春分公司月度额度'),
  ('TENANT','JLJT',1500000,'active','九台分公司月度额度');

-- ------------------------------------------------------------
-- 8. 常用查询（写代码时对照，非 DDL）
-- ------------------------------------------------------------
-- (a) 某公司画布可用节点 = 内置节点 ∪ 已授权节点
--     SELECT d.* FROM public.orch_node_def d WHERE d.enabled
--       AND (d.builtin OR d.id IN (SELECT feature_code FROM a_tenant_feature
--                                  WHERE ent_code = ? AND feature_type = 'NODE'));
-- (b) 某公司可见页面
--     SELECT page_code, page_name FROM a_tenant_page
--      WHERE ent_code = ? AND visible = 1 ORDER BY sort_order;
-- (c) 某公司本月 token 用量 / 剩余
--     SELECT q.monthly_tokens,
--            COALESCE(SUM(u.total_tokens),0) AS used
--       FROM a_token_quota q
--       LEFT JOIN a_token_usage_daily u
--              ON u.ent_code = q.target_code
--             AND u.usage_date >= DATE_FORMAT(CURDATE(),'%Y-%m-01')
--      WHERE q.scope='TENANT' AND q.target_code = ?
--      GROUP BY q.monthly_tokens;
