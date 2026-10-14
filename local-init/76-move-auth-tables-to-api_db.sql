-- 76 · 门户账号/令牌/配额 5 张表从 terminal_db 迁到 api_db（平台账本与 AI 内核库分离）
-- 背景：登录 Token 签发 + Token 用量配额归 backend（平台账本），内核只保留「令牌校验」的只读依赖。
-- 幂等：重复执行 = 以 terminal_db 为准重建 api_db 同名表（先删后建）。
-- 注意：terminal_db 里的旧表暂不删除 —— 留一版用于回滚（回滚 = 把表拷回去 + 代码回退）。
USE api_db;

DROP TABLE IF EXISTS a_user, a_auth_token, a_token_quota, a_token_usage_daily, a_token_usage_monthly;

CREATE TABLE a_user                LIKE terminal_db.a_user;
CREATE TABLE a_auth_token          LIKE terminal_db.a_auth_token;
CREATE TABLE a_token_quota         LIKE terminal_db.a_token_quota;
CREATE TABLE a_token_usage_daily   LIKE terminal_db.a_token_usage_daily;
CREATE TABLE a_token_usage_monthly LIKE terminal_db.a_token_usage_monthly;

INSERT INTO a_user                 SELECT * FROM terminal_db.a_user;
INSERT INTO a_auth_token           SELECT * FROM terminal_db.a_auth_token;
INSERT INTO a_token_quota          SELECT * FROM terminal_db.a_token_quota;
INSERT INTO a_token_usage_daily    SELECT * FROM terminal_db.a_token_usage_daily;
INSERT INTO a_token_usage_monthly  SELECT * FROM terminal_db.a_token_usage_monthly;

SELECT 'a_user' t, COUNT(*) c FROM a_user
UNION ALL SELECT 'a_auth_token', COUNT(*) FROM a_auth_token
UNION ALL SELECT 'a_token_quota', COUNT(*) FROM a_token_quota
UNION ALL SELECT 'a_token_usage_daily', COUNT(*) FROM a_token_usage_daily
UNION ALL SELECT 'a_token_usage_monthly', COUNT(*) FROM a_token_usage_monthly;