-- ============================================================
-- 75b：远端专用 —— transfer_order 工单流程列 + status 扩宽
-- ============================================================
-- 与 local-init/75 的差异：75 里还有 terminal_db.a_user 的角色账号部分，
-- 那是**内核侧**的数据（账管/仓管账号）；远端目前未部署内核（无 terminal_db），
-- 所以远端只应用业务侧这一半。等远端部署内核时再用 75 的账号部分。
--
-- 幂等：加列前判存在；status 扩宽可重复执行。
-- ============================================================
USE api_db;

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='sentAt') = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN sentAt DATETIME NULL COMMENT ''工单发送时间''',
  'SELECT ''sentAt 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='accountConfirmedAt') = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN accountConfirmedAt DATETIME NULL COMMENT ''调出方账管确认时间''',
  'SELECT ''accountConfirmedAt 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='keeperConfirmedAt') = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN keeperConfirmedAt DATETIME NULL COMMENT ''调出方仓管确认时间''',
  'SELECT ''keeperConfirmedAt 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='senderUser') = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN senderUser VARCHAR(64) NULL COMMENT ''发送工单的人''',
  'SELECT ''senderUser 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='accountConfirmer') = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN accountConfirmer VARCHAR(64) NULL COMMENT ''确认的账管账号''',
  'SELECT ''accountConfirmer 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s := IF((SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='keeperConfirmer') = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN keeperConfirmer VARCHAR(64) NULL COMMENT ''确认的仓管账号''',
  'SELECT ''keeperConfirmer 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ACCOUNT_CONFIRMED 是 17 字符，varchar(16) 会截断（本地实测踩过）
ALTER TABLE api_db.transfer_order MODIFY COLUMN status VARCHAR(32) NOT NULL
  COMMENT 'PENDING/SENT/ACCOUNT_CONFIRMED/OUTBOUND/DONE/REJECTED';

SELECT column_name, column_type FROM information_schema.columns
 WHERE table_schema='api_db' AND table_name='transfer_order'
   AND column_name IN ('sentAt','accountConfirmedAt','keeperConfirmedAt','senderUser','accountConfirmer','keeperConfirmer','status')
 ORDER BY column_name;