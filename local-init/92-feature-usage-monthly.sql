-- ============================================================================
-- 92：功能点调用次数加月份维度（2026-10-10）
-- ============================================================================
-- 目标库：api_db
--
-- 【为什么需要】
-- a_feature_usage 原来是「feature_code 唯一 = 累计数」，管理台「Token 用量分配」
-- 页的月份筛选（2026-10-10 上移为页面级）切到历史月时，功能调用次数卡不变，
-- 口径与同屏的用量/智能体卡不一致。改成「功能 × 月份」记账后，各卡统一随筛选联动。
--
-- 【改法】
--   1. 加 usage_month CHAR(7)（YYYY-MM）；存量行按 last_called_at 回填，缺省当月；
--   2. 唯一键 uk_feature(feature_code) → uk_feature_month(feature_code, usage_month)；
--   3. 补 2026-07 ~ 2026-09 三个月演示数据（2026-10 由 91 号种子提供）。
-- 【幂等】列/索引都经 information_schema 判断后动态 DDL，种子走 ON DUPLICATE KEY。
--
-- 注意：本迁移执行后，旧版 91 号种子的 4 列 INSERT 会因 usage_month 无默认值而报
-- 1364 —— 91 号已同步改为新表结构（含 usage_month），按序号重跑不受影响。
-- ============================================================================
USE api_db;

-- ① 加列（幂等：已存在则跳过）
SET @has_month_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'api_db' AND TABLE_NAME = 'a_feature_usage'
    AND COLUMN_NAME = 'usage_month');

SET @ddl := IF(@has_month_col = 0,
  'ALTER TABLE `a_feature_usage` ADD COLUMN `usage_month` CHAR(7) NULL COMMENT ''统计月份（YYYY-MM）'' AFTER `feature_name`',
  'SELECT ''usage_month 已存在，跳过加列'' AS note');

PREPARE st FROM @ddl;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ② 回填存量行：按最近调用时间所在月，缺省当月
UPDATE a_feature_usage
   SET usage_month = IFNULL(DATE_FORMAT(last_called_at, '%Y-%m'), DATE_FORMAT(CURDATE(), '%Y-%m'))
 WHERE usage_month IS NULL OR usage_month = '';

-- ③ 列收窄为 NOT NULL（回填后无 NULL；重复执行同定义无害）
ALTER TABLE a_feature_usage
  MODIFY COLUMN usage_month CHAR(7) NOT NULL COMMENT '统计月份（YYYY-MM）';

-- ④ 唯一键迁移：uk_feature → uk_feature_month（两步各自幂等）
SET @has_old_uk := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'api_db' AND TABLE_NAME = 'a_feature_usage'
    AND INDEX_NAME = 'uk_feature');

SET @ddl := IF(@has_old_uk > 0,
  'ALTER TABLE `a_feature_usage` DROP INDEX `uk_feature`',
  'SELECT ''uk_feature 不存在，跳过删除'' AS note');

PREPARE st FROM @ddl;
EXECUTE st;
DEALLOCATE PREPARE st;

SET @has_new_uk := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'api_db' AND TABLE_NAME = 'a_feature_usage'
    AND INDEX_NAME = 'uk_feature_month');

SET @ddl := IF(@has_new_uk = 0,
  'ALTER TABLE `a_feature_usage` ADD UNIQUE KEY `uk_feature_month` (`feature_code`, `usage_month`)',
  'SELECT ''uk_feature_month 已存在，跳过新增'' AS note');

PREPARE st FROM @ddl;
EXECUTE st;
DEALLOCATE PREPARE st;

-- ⑤ 补 2026-07 ~ 2026-09 演示数据（幂等：重复执行只刷新名称/最近时间，不重置计数）
INSERT INTO a_feature_usage (feature_code, feature_name, usage_month, call_count, last_called_at) VALUES
  -- 2026-09
  ('home',            '首页',         '2026-09', 1712, '2026-09-30 17:12:00'),
  ('inventory',       '库存管理',     '2026-09', 2246, '2026-09-30 16:48:00'),
  ('requisition',     '领用管理',     '2026-09',  903, '2026-09-29 15:36:00'),
  ('install_out',     '装机出库',     '2026-09', 1138, '2026-09-30 09:21:00'),
  ('recycle',         '回收管理',     '2026-09',  795, '2026-09-29 14:07:00'),
  ('reuse',           '利旧',         '2026-09',  366, '2026-09-28 11:42:00'),
  ('terminal_maint',  '终端信息维护', '2026-09',  592, '2026-09-29 10:19:00'),
  ('scrap',           '终端报废管理', '2026-09',  478, '2026-09-28 16:55:00'),
  ('repair',          '故障返厂维修', '2026-09',  452, '2026-09-29 09:08:00'),
  ('query',           '终端查询',     '2026-09', 1958, '2026-09-30 17:41:00'),
  ('stocktake',       '终端盘点',     '2026-09',  324, '2026-09-27 18:26:00'),
  ('sys_admin',       '系统管理',     '2026-09',  265, '2026-09-26 09:37:00'),
  ('report',          '报表管理',     '2026-09',  684, '2026-09-30 08:44:00'),
  -- 2026-08
  ('home',            '首页',         '2026-08', 1543, '2026-08-31 17:05:00'),
  ('inventory',       '库存管理',     '2026-08', 1976, '2026-08-31 16:22:00'),
  ('requisition',     '领用管理',     '2026-08',  828, '2026-08-30 15:14:00'),
  ('install_out',     '装机出库',     '2026-08', 1024, '2026-08-31 09:47:00'),
  ('recycle',         '回收管理',     '2026-08',  712, '2026-08-30 14:31:00'),
  ('reuse',           '利旧',         '2026-08',  331, '2026-08-29 11:18:00'),
  ('terminal_maint',  '终端信息维护', '2026-08',  545, '2026-08-30 10:02:00'),
  ('scrap',           '终端报废管理', '2026-08',  425, '2026-08-29 16:37:00'),
  ('repair',          '故障返厂维修', '2026-08',  398, '2026-08-30 09:25:00'),
  ('query',           '终端查询',     '2026-08', 1742, '2026-08-31 17:19:00'),
  ('stocktake',       '终端盘点',     '2026-08',  296, '2026-08-28 18:41:00'),
  ('sys_admin',       '系统管理',     '2026-08',  241, '2026-08-27 09:52:00'),
  ('report',          '报表管理',     '2026-08',  613, '2026-08-31 08:37:00'),
  -- 2026-07
  ('home',            '首页',         '2026-07', 1386, '2026-07-31 17:23:00'),
  ('inventory',       '库存管理',     '2026-07', 1729, '2026-07-31 16:09:00'),
  ('requisition',     '领用管理',     '2026-07',  742, '2026-07-30 15:02:00'),
  ('install_out',     '装机出库',     '2026-07',  917, '2026-07-31 09:16:00'),
  ('recycle',         '回收管理',     '2026-07',  649, '2026-07-30 14:28:00'),
  ('reuse',           '利旧',         '2026-07',  298, '2026-07-29 11:07:00'),
  ('terminal_maint',  '终端信息维护', '2026-07',  487, '2026-07-30 10:35:00'),
  ('scrap',           '终端报废管理', '2026-07',  376, '2026-07-29 16:11:00'),
  ('repair',          '故障返厂维修', '2026-07',  351, '2026-07-30 09:39:00'),
  ('query',           '终端查询',     '2026-07', 1533, '2026-07-31 17:08:00'),
  ('stocktake',       '终端盘点',     '2026-07',  264, '2026-07-28 18:14:00'),
  ('sys_admin',       '系统管理',     '2026-07',  214, '2026-07-27 09:26:00'),
  ('report',          '报表管理',     '2026-07',  552, '2026-07-31 08:51:00')
ON DUPLICATE KEY UPDATE feature_name = VALUES(feature_name), last_called_at = VALUES(last_called_at);

-- 自检：各月行数 + 当月合计
SELECT usage_month, COUNT(*) AS features, SUM(call_count) AS calls
  FROM a_feature_usage GROUP BY usage_month ORDER BY usage_month;