-- ============================================================
-- 75-role-users-and-workflow.sql
-- 调拨工单闭环：账管/仓管双角色 + 工单流程时间线
--   库1：terminal_db（内核的账号表 a_user）
--   库2：api_db（业务表 transfer_order）
-- 幂等：可重复执行（加列前先判存在）
-- ============================================================

-- ------------------------------------------------------------
-- 1. a_user 增加"所属仓库"（每个仓库有账管与仓管）
-- ------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema='terminal_db' AND table_name='a_user' AND column_name='warehouse_code');
SET @s := IF(@c = 0,
  'ALTER TABLE terminal_db.a_user ADD COLUMN warehouse_code VARCHAR(32) NULL COMMENT ''所属仓位编码（业务角色用）''',
  'SELECT ''warehouse_code 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema='terminal_db' AND table_name='a_user' AND column_name='warehouse_name');
SET @s := IF(@c = 0,
  'ALTER TABLE terminal_db.a_user ADD COLUMN warehouse_name VARCHAR(64) NULL COMMENT ''所属仓位名（冗余存储，前端直接显示）''',
  'SELECT ''warehouse_name 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 2. 四个业务账号：东宁市分公司 / 东宁市新区分公司 各一个账管 + 一个仓管
--    口令统一 123456（sha256 hex，与内核 AuthRepository 的哈希口径一致）
-- ------------------------------------------------------------
INSERT INTO terminal_db.a_user (user_code, user_name, password_hash, role, status, warehouse_code, warehouse_name)
SELECT * FROM (
  SELECT 'dn_zg' AS user_code, '东宁账管' AS user_name,
         '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92' AS password_hash,
         'ACCOUNT_MANAGER' AS role, 'ACTIVE' AS status,
         '9B00' AS warehouse_code, '东宁市分公司' AS warehouse_name
  UNION ALL SELECT 'dn_kg', '东宁仓管',
         '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92',
         'WAREHOUSE_KEEPER', 'ACTIVE', '9B00', '东宁市分公司'
  UNION ALL SELECT 'xn_zg', '新区账管',
         '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92',
         'ACCOUNT_MANAGER', 'ACTIVE', '9BZA', '东宁市新区分公司'
  UNION ALL SELECT 'xn_kg', '新区仓管',
         '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92',
         'WAREHOUSE_KEEPER', 'ACTIVE', '9BZA', '东宁市新区分公司'
) AS v
WHERE NOT EXISTS (SELECT 1 FROM terminal_db.a_user u WHERE u.user_code = v.user_code);

-- ------------------------------------------------------------
-- 3. transfer_order 增加工单流程时间线（建单 → 发送 → 账管确认 → 仓管确认）
-- ------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='sentAt');
SET @s := IF(@c = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN sentAt DATETIME NULL COMMENT ''工单发送时间''',
  'SELECT ''sentAt 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='accountConfirmedAt');
SET @s := IF(@c = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN accountConfirmedAt DATETIME NULL COMMENT ''调出方账管确认时间''',
  'SELECT ''accountConfirmedAt 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='keeperConfirmedAt');
SET @s := IF(@c = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN keeperConfirmedAt DATETIME NULL COMMENT ''调出方仓管确认（出库执行）时间''',
  'SELECT ''keeperConfirmedAt 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='senderUser');
SET @s := IF(@c = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN senderUser VARCHAR(64) NULL COMMENT ''发送工单的人（账号）''',
  'SELECT ''senderUser 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='accountConfirmer');
SET @s := IF(@c = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN accountConfirmer VARCHAR(64) NULL COMMENT ''确认的账管账号''',
  'SELECT ''accountConfirmer 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='keeperConfirmer');
SET @s := IF(@c = 0,
  'ALTER TABLE api_db.transfer_order ADD COLUMN keeperConfirmer VARCHAR(64) NULL COMMENT ''确认的仓管账号''',
  'SELECT ''keeperConfirmer 已存在''');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 4. status 列扩宽：ACCOUNT_CONFIRMED 是 17 个字符，varchar(16) 会截断报
--    "Data too long for column 'status'"（实测踩过）
-- ------------------------------------------------------------
ALTER TABLE api_db.transfer_order MODIFY COLUMN status VARCHAR(32) NOT NULL
  COMMENT 'PENDING/SENT/ACCOUNT_CONFIRMED/OUTBOUND/DONE/REJECTED';

-- ------------------------------------------------------------
-- 5. 校验
-- ------------------------------------------------------------
SELECT user_code, user_name, role, warehouse_code, warehouse_name, status
  FROM terminal_db.a_user ORDER BY id;

SELECT column_name FROM information_schema.columns
 WHERE table_schema='api_db' AND table_name='transfer_order'
   AND column_name IN ('sentAt','accountConfirmedAt','keeperConfirmedAt','senderUser','accountConfirmer','keeperConfirmer');