-- =====================================================================
-- 67-tool-approval.sql
-- 工具权限字段 + 审批闭环：
--   1) a_llm_tool 增加 approval_required / risk_level —— 一条工具记录可以
--      声明「调用我需要人工审批」；
--   2) 新表 a_tool_approval —— 模型调用被拦下时生成审批单（存下当时的入参），
--      用户在前端点「放行」后按原参执行，全程留痕；
--   3) 标记两个敏感工具做演示：0 元入库（财务敏感）、跨网调拨（审计敏感）。
-- =====================================================================
USE terminal_db;

ALTER TABLE a_llm_tool
  ADD COLUMN approval_required TINYINT NOT NULL DEFAULT 0 COMMENT '1=调用需人工审批放行',
  ADD COLUMN risk_level VARCHAR(20) NOT NULL DEFAULT 'LOW' COMMENT '风险等级 LOW/MEDIUM/HIGH';

CREATE TABLE IF NOT EXISTS a_tool_approval (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  approval_id VARCHAR(64) NOT NULL COMMENT '审批单号（对外暴露）',
  agent_code VARCHAR(64) NOT NULL COMMENT '发起调用的数字员工',
  tool_name VARCHAR(128) NOT NULL COMMENT '被拦下的工具',
  conversation_id VARCHAR(64) NULL COMMENT '发生的会话',
  user_id VARCHAR(64) NULL COMMENT '发起人',
  arguments_json TEXT NULL COMMENT '待执行入参（放行后原样执行）',
  risk_level VARCHAR(20) NOT NULL DEFAULT 'LOW' COMMENT '快照自 a_llm_tool',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/EXECUTED/REJECTED/FAILED',
  result_preview TEXT NULL COMMENT '执行结果摘要（放行后回填）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at DATETIME NULL,
  KEY idx_status (status),
  KEY idx_conv (conversation_id),
  UNIQUE KEY uk_approval_id (approval_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工具调用审批单';

-- 演示：两个敏感工具要求审批
UPDATE a_llm_tool SET approval_required = 1, risk_level = 'HIGH'   WHERE tool_name = 'queryZeroCostInbound';
UPDATE a_llm_tool SET approval_required = 1, risk_level = 'MEDIUM' WHERE tool_name = 'queryCrossNetworkTransfers';
