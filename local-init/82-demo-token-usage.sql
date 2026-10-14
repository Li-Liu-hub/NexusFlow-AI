-- ============================================================
-- 82-demo-token-usage.sql
-- 管理台「Token 用量分配」演示数据：新增业务账号 + 铺本月用量 + 配额样本
--   库：api_db（账号表与用量表在 76 号脚本已迁至 api_db）
-- 目的：让用量视图有正常的 Top5 排名可看 —— 此前本月用量里
--       只有 portal_user 与验收脚本账号，dn_kg/dn_zg/xn_kg/xn_zg
--       等业务演示账号一条用量都没有。
-- 幂等：可重复执行（账号判存在；用量先删当月种子行再插）
-- 口径：与真实计量一致 —— ent_code=default、model=mimo-v2.6-flash、
--       按日一行（uk_daily: ent_code+user_id+usage_date+model 唯一）、
--       estimated_cost 沿用现有行的 0.0000
-- 注意：用量铺在「最近 4 天」，跨月执行时月初几天会落到上月，
--       当月合计随之变小 —— 演示前重跑本脚本即可。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 新增演示账号：东宁操作员A-E（虚拟人物；口令 123456，与既有演示账号一致）
-- ------------------------------------------------------------
INSERT INTO api_db.a_user (user_code, user_name, password_hash, role, status, warehouse_code, warehouse_name)
SELECT * FROM (
  SELECT 'dnop01' AS user_code, '东宁操作员A' AS user_name, SHA2('123456', 256) AS password_hash,
         'WAREHOUSE_KEEPER' AS role, 'ACTIVE' AS status, '9B00' AS warehouse_code, '东宁市分公司' AS warehouse_name
  UNION ALL SELECT 'dnop02', '东宁操作员B', SHA2('123456', 256), 'WAREHOUSE_KEEPER', 'ACTIVE', '9B00', '东宁市分公司'
  UNION ALL SELECT 'dnop03', '东宁操作员C', SHA2('123456', 256), 'WAREHOUSE_KEEPER', 'ACTIVE', '9B00', '东宁市分公司'
  UNION ALL SELECT 'dnop04', '东宁操作员D', SHA2('123456', 256), 'WAREHOUSE_KEEPER', 'ACTIVE', '9BZA', '东宁市新区分公司'
  UNION ALL SELECT 'dnop05', '东宁操作员E', SHA2('123456', 256), 'WAREHOUSE_KEEPER', 'ACTIVE', '9BZA', '东宁市新区分公司'
) v
WHERE NOT EXISTS (SELECT 1 FROM api_db.a_user u WHERE u.user_code = v.user_code);

-- ------------------------------------------------------------
-- 2. 本月用量：9 个业务账号 × 最近 4 天（权重 35/30/20/15，合计=目标月用量）
--    排名梯度（token）：dn_kg 126万 > xn_zg 88万 > dn_zg 64万 > dnop01 42万 > xn_kg 26万 > …
-- ------------------------------------------------------------
DELETE FROM api_db.a_token_usage_daily
 WHERE user_id IN ('dn_kg', 'xn_zg', 'dn_zg', 'dnop01', 'xn_kg', 'dnop02', 'dnop03', 'dnop04', 'dnop05')
   AND usage_date >= DATE_FORMAT(CURDATE(), '%Y-%m-01');

INSERT INTO api_db.a_token_usage_daily
  (ent_code, user_id, usage_date, model, agent_code, request_count,
   total_prompt_tokens, total_completion_tokens, total_tokens, total_tool_calls, estimated_cost)
SELECT 'default', s.user_code, DATE_SUB(CURDATE(), INTERVAL d.k DAY), 'mimo-v2.6-flash', s.agent_code,
       ROUND(s.req * d.w),
       ROUND(s.tok * d.w * 0.7),
       ROUND(s.tok * d.w * 0.3),
       ROUND(s.tok * d.w),
       ROUND(s.req * d.w * 1.6),
       0.0000
  FROM (
              SELECT 'dn_kg'  AS user_code, 'terminal_manager' AS agent_code, 52 AS req, 1260000 AS tok
    UNION ALL SELECT 'xn_zg',  'query_assistant',  44,  880000
    UNION ALL SELECT 'dn_zg',  'terminal_manager', 38,  640000
    UNION ALL SELECT 'dnop01', 'terminal_manager', 30,  420000
    UNION ALL SELECT 'xn_kg',  'terminal_manager', 22,  260000
    UNION ALL SELECT 'dnop02', 'query_assistant',  16,  180000
    UNION ALL SELECT 'dnop03', 'terminal_manager', 11,   95000
    UNION ALL SELECT 'dnop04', 'query_assistant',   6,   40000
    UNION ALL SELECT 'dnop05', 'terminal_manager',  3,   12000
  ) s
  JOIN (
              SELECT 1 AS k, 0.35 AS w
    UNION ALL SELECT 2, 0.30
    UNION ALL SELECT 3, 0.20
    UNION ALL SELECT 4, 0.15
  ) d;

-- ------------------------------------------------------------
-- 3. 配额样本：覆盖 正常 / 接近上限 / 已超限 三种状态
--    （portal_user 既有 200 万配额，用量 194 万 → 接近上限；admin 不限量）
--    已超限的账号只影响该账号下一轮对话被拒，用于演示配额拦截。
-- ------------------------------------------------------------
INSERT INTO api_db.a_token_quota (scope, target_code, monthly_tokens, status, remark)
SELECT * FROM (
  SELECT 'USER' AS scope, 'dn_zg' AS target_code, 2000000 AS monthly_tokens, 'active' AS status, '东宁账管：正常区间' AS remark
  UNION ALL SELECT 'USER', 'dnop01', 1000000, 'active', '东宁操作员A：正常区间'
  UNION ALL SELECT 'USER', 'xn_zg',  1000000, 'active', '新区账管：接近上限'
  UNION ALL SELECT 'USER', 'dnop04',   30000, 'active', '东宁操作员D：演示已超限'
) v
WHERE NOT EXISTS (SELECT 1 FROM api_db.a_token_quota q WHERE q.scope = v.scope AND q.target_code = v.target_code);

-- ------------------------------------------------------------
-- 4. 自检：本月用量排名（应与管理台「用户用量 Top 5」一致）
-- ------------------------------------------------------------
SELECT user_id, SUM(request_count) AS requests, SUM(total_tokens) AS total_tokens
  FROM api_db.a_token_usage_daily
 WHERE usage_date >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
 GROUP BY user_id
 ORDER BY total_tokens DESC;