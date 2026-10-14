-- ============================================================================
-- 93：Token 用量分配页演示数据增强（2026-10-10）
-- ============================================================================
-- 目标库：api_db（a_token_usage_daily / a_token_quota，backend 直读）
--
-- 【为什么需要】
-- 管理台「Token 用量分配」页此前只有：
--   ① 82 号的 4 天用量（dn_kg 126 万等，日粒度只有 4 个点）——调整弹窗的
--      日用量点线图大半是 0，Top5 条形只有「不限」蓝色与个别橙色；
--   ② 5 行配额样本——配额规则表很短，「已使用」列百分之几的梯度不明显；
--   ③ 账本里 order_parser 一条都没有——智能体卡永远有一行「本月暂无对话计量」。
--
-- 【本脚本做什么】
--   ① 配额补齐 6 行（dn_kg / xn_kg / dnop01 / dnop02 / dnop03 / dnop05），
--      让配额规则表凑齐 11 行、覆盖 正常/接近上限/已超限/不限 四态，
--      Top5 条形同时拿到「紫色=正常 / 橙色=接近上限」的混合配色；
--   ② 当月近 10 天（含今天）重铺 9 个业务账号用量：日权重 06→13 的波浪曲线、
--      每用户每天 2 行（主助手 72% + 副智能体 28%），点线图/饼图都铺满；
--   ③ 补一单 order_parser 用量（dn_zg 建单节点），智能体卡 9 个全点亮；
--   ④ portal_user 只补 4 个空档日（不 DELETE、不覆盖真实行）——它是对外
--      「真实计量」展示账号，历史行保持原样；
--   ⑤ 上月 25~30 日铺一层（约为当月 55% 的量），月份筛选切上月不空。
--
-- 【口径】
--   ent_code=default、model=mimo-v2.6-flash（与真实落账一致）；
--   prompt/completion 按 70/30 拆、请求数按 ≈4200 token/次 折算。
--
-- 【幂等】9 个业务账号「先删当月再铺」= 可重复执行；portal_user 走
--   ON DUPLICATE KEY UPDATE；配额走 uk_scope_target 幂等 upsert。
--   注意（同 82 号）：日期按「今天往前推」铺，月初运行时前几天会落到上月，
--   当月合计随之变小——演示前重跑本脚本即可。
--
-- 【演示账号月用量设计（10 月）】
--   portal_user ~300 万（真实 210 万 + 补铺 90 万）/ 配额 1000 万 → 30% 正常
--   dn_kg      154 万 / 配额 200 万  → 77% 正常
--   xn_zg       85 万 / 配额 100 万  → 85% 接近上限
--   dn_zg       88 万 / 配额 200 万  → 44% 正常
--   dnop01      60 万 / 配额 70 万   → 86% 接近上限
--   dnop02      42 万 / 配额 48 万   → 88% 接近上限
--   xn_kg       41 万 / 配额 55 万   → 75% 正常
--   dnop03      17.5 万 / 配额 40 万 → 44% 正常
--   dnop05      7.2 万 / 配额 10 万  → 72% 正常
--   dnop04      4 万 / 配额 3 万     → 133% 已超限（演示拦截）
--   以上均低于各自配额（dnop04 除外，刻意超限）——不会误拦正常对话。
-- ============================================================================
USE api_db;

-- ------------------------------------------------------------
-- ① 配额补齐（幂等：uk_scope_target 冲突即刷新）
-- ------------------------------------------------------------
INSERT INTO a_token_quota (scope, target_code, monthly_tokens, status, remark) VALUES
  ('USER', 'dn_kg',    2000000, 'active', '东宁仓管：正常区间'),
  ('USER', 'xn_kg',     550000, 'active', '新区仓管：正常区间'),
  ('USER', 'dnop01',    700000, 'active', '东宁操作员A：接近上限'),
  ('USER', 'dnop02',    480000, 'active', '东宁操作员B：接近上限'),
  ('USER', 'dnop03',    400000, 'active', '东宁操作员C：正常区间'),
  ('USER', 'dnop05',    100000, 'active', '东宁操作员E：正常区间')
ON DUPLICATE KEY UPDATE
  monthly_tokens = VALUES(monthly_tokens),
  status         = VALUES(status),
  remark         = VALUES(remark);

-- ------------------------------------------------------------
-- ② 当月近 10 天重铺：9 个业务账号
--    日权重（k=距今天数，9=最早）：06 08 09 10 11 12 08 10 13 13（%）
--    每用户每天 2 行：主助手 72% / 副智能体 28%（uk_daily 含 agent_code，互不冲突）
-- ------------------------------------------------------------
DELETE FROM a_token_usage_daily
 WHERE user_id IN ('dn_kg', 'xn_zg', 'dn_zg', 'dnop01', 'dnop02', 'dnop03', 'dnop04', 'dnop05', 'xn_kg')
   AND usage_date >= DATE_FORMAT(CURDATE(), '%Y-%m-01');

INSERT INTO a_token_usage_daily
  (ent_code, user_id, usage_date, model, agent_code, request_count,
   total_prompt_tokens, total_completion_tokens, total_tokens, total_tool_calls, estimated_cost)
SELECT 'default', t.user_code, t.dt, 'mimo-v2.6-flash', t.agent_code,
       ROUND(t.tpart / 4200),
       ROUND(t.tpart * 0.7),
       ROUND(t.tpart * 0.3),
       t.tpart,
       ROUND(t.tpart / 4200 * 1.6),
       0.0000
  FROM (
        SELECT s.user_code,
               DATE_SUB(CURDATE(), INTERVAL d.k DAY) AS dt,
               IF(p.i = 0, s.agent_main, s.agent_alt) AS agent_code,
               ROUND(s.tok * d.w * p.w) AS tpart
          FROM (
                      SELECT 'dn_kg'  AS user_code, 'terminal_manager' AS agent_main, 'query_assistant'   AS agent_alt, 1540000 AS tok
            UNION ALL SELECT 'xn_zg',  'query_assistant',  'intent_router',      850000
            UNION ALL SELECT 'dn_zg',  'query_assistant',  'replenish_advisor',  880000
            UNION ALL SELECT 'dnop01', 'terminal_manager', 'query_assistant',    600000
            UNION ALL SELECT 'dnop02', 'query_assistant',  'intent_router',      420000
            UNION ALL SELECT 'dnop03', 'query_assistant',  'chart_planner',      175000
            UNION ALL SELECT 'dnop04', 'query_assistant',  'ops_assistant',       40000
            UNION ALL SELECT 'dnop05', 'terminal_manager', 'quality_critic',      72000
            UNION ALL SELECT 'xn_kg',  'terminal_manager', 'query_assistant',    410000
          ) s
          JOIN (
                      SELECT 9 AS k, 0.060 AS w   -- 最早（今天-9）
            UNION ALL SELECT 8, 0.080
            UNION ALL SELECT 7, 0.090
            UNION ALL SELECT 6, 0.100
            UNION ALL SELECT 5, 0.110
            UNION ALL SELECT 4, 0.120
            UNION ALL SELECT 3, 0.080
            UNION ALL SELECT 2, 0.100
            UNION ALL SELECT 1, 0.130
            UNION ALL SELECT 0, 0.130   -- 今天
          ) d
          JOIN (
                      SELECT 0 AS i, 0.72 AS w
            UNION ALL SELECT 1, 0.28
          ) p
       ) t;

-- ------------------------------------------------------------
-- ③ 补一单 order_parser（dn_zg 建单节点）——智能体卡 9 个全点亮
--    （放在 ② 之后：重建当月数据后重新补，重复执行幂等）
-- ------------------------------------------------------------
INSERT INTO a_token_usage_daily
  (ent_code, user_id, usage_date, model, agent_code, request_count,
   total_prompt_tokens, total_completion_tokens, total_tokens, total_tool_calls, estimated_cost)
SELECT 'default', 'dn_zg', DATE_SUB(CURDATE(), INTERVAL 9 DAY), 'mimo-v2.6-flash', 'order_parser',
       3, 8400, 3600, 12000, 5, 0.0000;

-- ------------------------------------------------------------
-- ④ portal_user 只补空档日（今天-4~-1 与 -9~-6 已有真实行，不碰）
--    补 4 个早期空档（-9~-6）与 2 个近端空档（-1、今天），合计约 90 万
-- ------------------------------------------------------------
INSERT INTO a_token_usage_daily
  (ent_code, user_id, usage_date, model, agent_code, request_count,
   total_prompt_tokens, total_completion_tokens, total_tokens, total_tool_calls, estimated_cost)
SELECT 'default', 'portal_user', t.dt, 'mimo-v2.6-flash', t.agent_code,
       ROUND(t.tpart / 4200),
       ROUND(t.tpart * 0.7),
       ROUND(t.tpart * 0.3),
       t.tpart,
       ROUND(t.tpart / 4200 * 1.6),
       0.0000
  FROM (
        SELECT DATE_SUB(CURDATE(), INTERVAL d.k DAY) AS dt,
               IF(p.i = 0, 'terminal_manager', 'query_assistant') AS agent_code,
               ROUND(d.tok * p.w) AS tpart
          FROM (
                      SELECT 9 AS k, 120000 AS tok
            UNION ALL SELECT 8, 130000
            UNION ALL SELECT 7, 140000
            UNION ALL SELECT 6, 150000
            UNION ALL SELECT 1, 180000
            UNION ALL SELECT 0, 180000
          ) d
          JOIN (
                      SELECT 0 AS i, 0.72 AS w
            UNION ALL SELECT 1, 0.28
          ) p
       ) t
ON DUPLICATE KEY UPDATE
  request_count           = VALUES(request_count),
  total_prompt_tokens     = VALUES(total_prompt_tokens),
  total_completion_tokens = VALUES(total_completion_tokens),
  total_tokens            = VALUES(total_tokens),
  total_tool_calls        = VALUES(total_tool_calls);

-- ------------------------------------------------------------
-- ⑤ 上月 25~30 日铺一层（约当月 55% 的量）——月份筛选切上月不空
-- ------------------------------------------------------------
SET @prev_first := DATE_SUB(DATE_FORMAT(CURDATE(), '%Y-%m-01'), INTERVAL 1 MONTH);

DELETE FROM a_token_usage_daily
 WHERE user_id IN ('portal_user', 'dn_kg', 'xn_zg', 'dn_zg', 'dnop01', 'dnop02', 'dnop03', 'dnop04', 'dnop05', 'xn_kg')
   AND usage_date >= @prev_first
   AND usage_date <  DATE_FORMAT(CURDATE(), '%Y-%m-01');

INSERT INTO a_token_usage_daily
  (ent_code, user_id, usage_date, model, agent_code, request_count,
   total_prompt_tokens, total_completion_tokens, total_tokens, total_tool_calls, estimated_cost)
SELECT 'default', t.user_code, t.dt, 'mimo-v2.6-flash', t.agent_code,
       ROUND(t.tpart / 4200),
       ROUND(t.tpart * 0.7),
       ROUND(t.tpart * 0.3),
       t.tpart,
       ROUND(t.tpart / 4200 * 1.6),
       0.0000
  FROM (
        SELECT s.user_code,
               DATE_ADD(@prev_first, INTERVAL d.off DAY) AS dt,
               IF(p.i = 0, s.agent_main, s.agent_alt) AS agent_code,
               ROUND(s.tok * d.w * p.w) AS tpart
          FROM (
                      SELECT 'portal_user' AS user_code, 'terminal_manager' AS agent_main, 'query_assistant'   AS agent_alt, 550000 AS tok
            UNION ALL SELECT 'dn_kg',  'terminal_manager', 'query_assistant',    850000
            UNION ALL SELECT 'xn_zg',  'query_assistant',  'intent_router',      470000
            UNION ALL SELECT 'dn_zg',  'query_assistant',  'replenish_advisor',  480000
            UNION ALL SELECT 'dnop01', 'terminal_manager', 'query_assistant',    330000
            UNION ALL SELECT 'dnop02', 'query_assistant',  'intent_router',      230000
            UNION ALL SELECT 'xn_kg',  'terminal_manager', 'query_assistant',    225000
            UNION ALL SELECT 'dnop03', 'query_assistant',  'chart_planner',       96000
            UNION ALL SELECT 'dnop04', 'query_assistant',  'ops_assistant',       22000
            UNION ALL SELECT 'dnop05', 'terminal_manager', 'quality_critic',      40000
          ) s
          JOIN (
                      SELECT 24 AS off, 0.140 AS w   -- 上月 25 日
            UNION ALL SELECT 25, 0.160
            UNION ALL SELECT 26, 0.170
            UNION ALL SELECT 27, 0.160
            UNION ALL SELECT 28, 0.180
            UNION ALL SELECT 29, 0.190             -- 上月 30 日
          ) d
          JOIN (
                      SELECT 0 AS i, 0.72 AS w
            UNION ALL SELECT 1, 0.28
          ) p
       ) t;

-- ------------------------------------------------------------
-- ⑥ 自检：当月每用户合计 + 配额状态；智能体覆盖
-- ------------------------------------------------------------
SELECT user_id,
       SUM(request_count) AS requests,
       SUM(total_tokens)  AS total_tokens
  FROM a_token_usage_daily
 WHERE usage_date >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
 GROUP BY user_id
 ORDER BY total_tokens DESC;

SELECT q.target_code, q.monthly_tokens,
       IFNULL(u.tok, 0) AS used_tokens,
       ROUND(IFNULL(u.tok, 0) / q.monthly_tokens * 100) AS used_pct
  FROM a_token_quota q
  LEFT JOIN (
        SELECT user_id, SUM(total_tokens) AS tok
          FROM a_token_usage_daily
         WHERE usage_date >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
         GROUP BY user_id
       ) u ON u.user_id COLLATE utf8mb4_0900_ai_ci = q.target_code  -- 两表排序规则不同（usage 表 unicode_ci），显式对齐
 WHERE q.scope = 'USER'
 ORDER BY q.target_code;

SELECT agent_code, SUM(total_tokens) AS tok, COUNT(DISTINCT user_id) AS users
  FROM a_token_usage_daily
 WHERE usage_date >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
 GROUP BY agent_code
 ORDER BY tok DESC;