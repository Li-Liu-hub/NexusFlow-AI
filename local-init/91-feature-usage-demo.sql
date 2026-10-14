-- ============================================================================
-- 管理台 · 用户端功能点调用次数（演示账本）
-- ============================================================================
-- 目标库：api_db（与 a_token_usage_daily / a_token_quota 同库，backend 直读）
--
-- 【为什么需要】
-- 管理台「Token 用量分配」页第二行左卡由「全部用户」改为「功能调用次数」视图：
-- 展示用户端「终端管理」各功能入口被调用的次数排行。演示阶段先落一批虚拟数据，
-- 后续接真实埋点（前端点击上报）时只需替换 call_count 的写入方，读接口不变。
--
-- 【设计】
--   feature_code + usage_month 唯一（2026-10-10 起按「功能 × 月份」记账，页面月份
--   筛选可切历史月；旧版只有 feature_code 唯一 = 累计数，见 92 号迁移）；
--   种子幂等（重复执行只刷新名称/最近时间，不重置计数）。
-- ============================================================================
USE api_db;

CREATE TABLE IF NOT EXISTS a_feature_usage (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  feature_code   VARCHAR(64)  NOT NULL COMMENT '功能编码（门户终端管理入口）',
  feature_name   VARCHAR(64)  NOT NULL COMMENT '功能中文名',
  usage_month    CHAR(7)      NOT NULL COMMENT '统计月份（YYYY-MM）',
  call_count     BIGINT       NOT NULL DEFAULT 0 COMMENT '当月调用次数',
  last_called_at DATETIME     NULL COMMENT '最近调用时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_feature_month (feature_code, usage_month)
) COMMENT '用户端功能点调用次数（演示账本，按功能 × 月份）';

-- 虚拟演示数据：终端管理 13 个功能入口 · 2026-10（幂等，只补不覆盖已有计数；
-- 2026-07 ~ 2026-09 的演示数据见 92 号迁移）
INSERT INTO a_feature_usage (feature_code, feature_name, usage_month, call_count, last_called_at) VALUES
  ('home',            '首页',         '2026-10', 1863, '2026-10-09 10:21:00'),
  ('inventory',       '库存管理',     '2026-10', 2417, '2026-10-09 11:05:00'),
  ('requisition',     '领用管理',     '2026-10',  986, '2026-10-08 16:42:00'),
  ('install_out',     '装机出库',     '2026-10', 1245, '2026-10-09 09:33:00'),
  ('recycle',         '回收管理',     '2026-10',  873, '2026-10-08 15:17:00'),
  ('reuse',           '利旧',         '2026-10',  412, '2026-10-07 14:08:00'),
  ('terminal_maint',  '终端信息维护', '2026-10',  654, '2026-10-08 10:56:00'),
  ('scrap',           '终端报废管理', '2026-10',  521, '2026-10-07 17:29:00'),
  ('repair',          '故障返厂维修', '2026-10',  489, '2026-10-08 09:44:00'),
  ('query',           '终端查询',     '2026-10', 2130, '2026-10-09 11:41:00'),
  ('stocktake',       '终端盘点',     '2026-10',  356, '2026-10-06 18:02:00'),
  ('sys_admin',       '系统管理',     '2026-10',  298, '2026-10-06 09:15:00'),
  ('report',          '报表管理',     '2026-10',  742, '2026-10-09 08:58:00')
ON DUPLICATE KEY UPDATE feature_name = VALUES(feature_name);

-- 自检
SELECT feature_code, feature_name, usage_month, call_count, last_called_at
  FROM a_feature_usage WHERE usage_month = '2026-10' ORDER BY call_count DESC;