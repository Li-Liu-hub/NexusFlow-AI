-- ============================================================
-- 86：账本唯一键纳入 agent_code（2026-10-08 编排层落账改造配套）
-- ============================================================
-- 背景：uk_daily(ent_code,user_id,usage_date,model) 不含 agent_code，
-- 同一用户同天调用多个智能体（门户对话落 terminal_manager、意图识别落
-- intent_router、报告落 replenish_reporter…）会被合并成一行，agent_code
-- 只保留首笔值 —— 管理台「智能体 token 用量」按 agent_code 聚合时，
-- 后到的节点恒为 0（表现为计量盲区）。
-- 改法：agent_code 纳入唯一键，各智能体各占一行（更细粒度只会拆行，
-- 对存量数据无冲突）；存量已合并的行保持原样（历史口径，不再拆分）。
-- 幂等：information_schema 查到 uk_daily 已含 agent_code 即跳过。
USE api_db;

SET @has_agent_col := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'api_db' AND TABLE_NAME = 'a_token_usage_daily'
    AND INDEX_NAME = 'uk_daily' AND COLUMN_NAME = 'agent_code');

SET @ddl := IF(@has_agent_col = 0,
  'ALTER TABLE `a_token_usage_daily` DROP INDEX `uk_daily`, ADD UNIQUE KEY `uk_daily` (`ent_code`, `user_id`, `usage_date`, `model`, `agent_code`)',
  'SELECT ''uk_daily 已含 agent_code，跳过'' AS note');

PREPARE st FROM @ddl;
EXECUTE st;
DEALLOCATE PREPARE st;

-- 核对
SHOW INDEX FROM a_token_usage_daily WHERE Key_name = 'uk_daily';
