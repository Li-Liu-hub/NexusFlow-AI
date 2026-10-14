-- ============================================================================
-- 会话按数字员工隔离：a_chat_conversation / a_chat_message 加 agent_code
-- ============================================================================
-- 【为什么】三个数字员工各有独立会话：查数助手的历史不应出现在运维助手的
-- 会话列表里。前端按角色切换后，列表也要按 agent_code 过滤。
--
-- 【回填】存量会话都是 terminal_manager 的历史对话，统一回填该编码。
-- ============================================================================
USE terminal_db;

ALTER TABLE a_chat_conversation
  ADD COLUMN agent_code varchar(64) NOT NULL DEFAULT 'terminal_manager'
  COMMENT '所属数字员工编码' AFTER user_id,
  ADD INDEX idx_agent (agent_code);

ALTER TABLE a_chat_message
  ADD COLUMN agent_code varchar(64) NOT NULL DEFAULT 'terminal_manager'
  COMMENT '所属数字员工编码' AFTER user_id,
  ADD INDEX idx_agent_conv (agent_code, conversation_id);

-- 校验
SELECT 'a_chat_conversation' AS tbl, agent_code, COUNT(*) AS n
  FROM a_chat_conversation GROUP BY agent_code
UNION ALL
SELECT 'a_chat_message', agent_code, COUNT(*)
  FROM a_chat_message GROUP BY agent_code;
