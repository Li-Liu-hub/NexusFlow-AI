-- ============================================================================
-- 89-demo-data-polish.sql — 演示数据整理（2026-10-09，幂等可重跑）
-- 库：api_db（业务表）
--
-- 【目的】让演示画面有冲击力且自洽：
--   1. 型号 NULL 修复：20 台「IPTV机顶盒-运营商在线」补型号 LTZX-IPTV（图表不再出 NULL 切片）
--   2. 库龄分布重塑：此前 365+ 占 99.5%（库龄分布图一根孤柱、长库龄 2.8 万台失真），
--      重塑为漏斗形 0-30≈10% / 30-90≈20% / 90-180≈25% / 180-365≈25% / 365+≈20%，
--      长库龄（180+）约 1.27 万台 —— 「盘活」故事有分量不失真
--   3. firstInboundAt 与 agingDays 口径对齐：所有改龄行 firstInboundAt = 今天 - agingDays，
--      时间维度图表（入库趋势/月度对比/年度对比）与库龄报表数字自洽
--   4. 双 NULL（agingDays+firstInboundAt 均 NULL 的 106 台）→ 按串码哈希归入 1500~2999 天长尾
--   5. 补「在途」：从 F657/PT9500/B671（两仓对称、远离 0.6/1.4 阈值）匀 400 台置「在途」，
--      统计卡不再"可用=总数"；不碰 V175/K155/HN8346/B860AV/HG521（补货建议演示前提）
--   6. 工单残留清理：旧状态机遗留 PENDING/SENT 死单 + 历史演示 APPLIED 半开单共 24 张
--      （APPLIED 单与新链路共享库存语义，残留会干扰演示），保留 DONE/REJECTED 健康历史；
--      顺带清孤儿通知（orderNo 指向已删工单）
--
-- 【幂等性】所有 UPDATE 用差额驱动（GREATEST(0, 目标-现量)），重跑自动跳过已完成项；
--   随机数仅用于「抽样选行」与「桶内天数」，经派生表一次生成，agingDays 与日期同源不漂移。
-- ============================================================================

USE api_db;

-- ---------- 1. NULL 型号 → LTZX-IPTV ----------
UPDATE terminal SET model = 'LTZX-IPTV' WHERE model IS NULL;

-- ---------- 2. 双 NULL 库龄 → 按串码哈希归入长尾（1500~2999 天，确定性可重跑）----------
UPDATE terminal
SET agingDays = 1500 + MOD(CONV(SUBSTRING(MD5(serialNo), 1, 6), 16, 10), 1500)
WHERE agingDays IS NULL;

-- ---------- 3. 库龄漏斗重塑（只从 365+ 池抽差额；两字段同源一次写入）----------
-- 3a. 0-30 天目标 2835
SET @need = GREATEST(0, 2835 - (SELECT COUNT(*) FROM terminal WHERE agingDays < 30));
SET @sql = 'UPDATE terminal t JOIN (
              SELECT serialNo, FLOOR(RAND()*29) AS d FROM terminal
              WHERE agingDays >= 365 ORDER BY RAND() LIMIT ?) s ON t.serialNo = s.serialNo
            SET t.agingDays = s.d, t.firstInboundAt = TIMESTAMPADD(DAY, -s.d, NOW())';
PREPARE stmt FROM @sql; EXECUTE stmt USING @need; DEALLOCATE PREPARE stmt;

-- 3b. 30-90 天目标 5671
SET @need = GREATEST(0, 5671 - (SELECT COUNT(*) FROM terminal WHERE agingDays >= 30 AND agingDays < 90));
SET @sql = 'UPDATE terminal t JOIN (
              SELECT serialNo, 30 + FLOOR(RAND()*60) AS d FROM terminal
              WHERE agingDays >= 365 ORDER BY RAND() LIMIT ?) s ON t.serialNo = s.serialNo
            SET t.agingDays = s.d, t.firstInboundAt = TIMESTAMPADD(DAY, -s.d, NOW())';
PREPARE stmt FROM @sql; EXECUTE stmt USING @need; DEALLOCATE PREPARE stmt;

-- 3c. 90-180 天目标 7089
SET @need = GREATEST(0, 7089 - (SELECT COUNT(*) FROM terminal WHERE agingDays >= 90 AND agingDays < 180));
SET @sql = 'UPDATE terminal t JOIN (
              SELECT serialNo, 90 + FLOOR(RAND()*90) AS d FROM terminal
              WHERE agingDays >= 365 ORDER BY RAND() LIMIT ?) s ON t.serialNo = s.serialNo
            SET t.agingDays = s.d, t.firstInboundAt = TIMESTAMPADD(DAY, -s.d, NOW())';
PREPARE stmt FROM @sql; EXECUTE stmt USING @need; DEALLOCATE PREPARE stmt;

-- 3d. 180-365 天目标 7089
SET @need = GREATEST(0, 7089 - (SELECT COUNT(*) FROM terminal WHERE agingDays >= 180 AND agingDays < 365));
SET @sql = 'UPDATE terminal t JOIN (
              SELECT serialNo, 180 + FLOOR(RAND()*185) AS d FROM terminal
              WHERE agingDays >= 365 ORDER BY RAND() LIMIT ?) s ON t.serialNo = s.serialNo
            SET t.agingDays = s.d, t.firstInboundAt = TIMESTAMPADD(DAY, -s.d, NOW())';
PREPARE stmt FROM @sql; EXECUTE stmt USING @need; DEALLOCATE PREPARE stmt;

-- ---------- 4. firstInboundAt 空值回填（= 今天 - agingDays，只填 NULL 行）----------
UPDATE terminal
SET firstInboundAt = TIMESTAMPADD(DAY, -agingDays, NOW())
WHERE agingDays IS NOT NULL AND firstInboundAt IS NULL;

-- ---------- 5. 补「在途」400 台（两仓对称，只动远离阈值的大型号）----------
-- F657 每仓 120
SET @need = GREATEST(0, 120 - (SELECT COUNT(*) FROM terminal
  WHERE terminalStatus='在途' AND model='F657' AND warehouseCode='9B00'));
SET @sql = 'UPDATE terminal SET terminalStatus=''在途''
            WHERE terminalStatus=''可用'' AND model=''F657'' AND warehouseCode=''9B00''
            ORDER BY RAND() LIMIT ?';
PREPARE stmt FROM @sql; EXECUTE stmt USING @need; DEALLOCATE PREPARE stmt;
SET @need = GREATEST(0, 120 - (SELECT COUNT(*) FROM terminal
  WHERE terminalStatus='在途' AND model='F657' AND warehouseCode='9BZA'));
SET @sql = 'UPDATE terminal SET terminalStatus=''在途''
            WHERE terminalStatus=''可用'' AND model=''F657'' AND warehouseCode=''9BZA''
            ORDER BY RAND() LIMIT ?';
PREPARE stmt FROM @sql; EXECUTE stmt USING @need; DEALLOCATE PREPARE stmt;

-- PT9500 每仓 80
SET @need = GREATEST(0, 80 - (SELECT COUNT(*) FROM terminal
  WHERE terminalStatus='在途' AND model='PT9500' AND warehouseCode='9B00'));
SET @sql = 'UPDATE terminal SET terminalStatus=''在途''
            WHERE terminalStatus=''可用'' AND model=''PT9500'' AND warehouseCode=''9B00''
            ORDER BY RAND() LIMIT ?';
PREPARE stmt FROM @sql; EXECUTE stmt USING @need; DEALLOCATE PREPARE stmt;
SET @need = GREATEST(0, 80 - (SELECT COUNT(*) FROM terminal
  WHERE terminalStatus='在途' AND model='PT9500' AND warehouseCode='9BZA'));
SET @sql = 'UPDATE terminal SET terminalStatus=''在途''
            WHERE terminalStatus=''可用'' AND model=''PT9500'' AND warehouseCode=''9BZA''
            ORDER BY RAND() LIMIT ?';
PREPARE stmt FROM @sql; EXECUTE stmt USING @need; DEALLOCATE PREPARE stmt;

-- B671 每仓 50
SET @need = GREATEST(0, 50 - (SELECT COUNT(*) FROM terminal
  WHERE terminalStatus='在途' AND model='B671' AND warehouseCode='9B00'));
SET @sql = 'UPDATE terminal SET terminalStatus=''在途''
            WHERE terminalStatus=''可用'' AND model=''B671'' AND warehouseCode=''9B00''
            ORDER BY RAND() LIMIT ?';
PREPARE stmt FROM @sql; EXECUTE stmt USING @need; DEALLOCATE PREPARE stmt;
SET @need = GREATEST(0, 50 - (SELECT COUNT(*) FROM terminal
  WHERE terminalStatus='在途' AND model='B671' AND warehouseCode='9BZA'));
SET @sql = 'UPDATE terminal SET terminalStatus=''在途''
            WHERE terminalStatus=''可用'' AND model=''B671'' AND warehouseCode=''9BZA''
            ORDER BY RAND() LIMIT ?';
PREPARE stmt FROM @sql; EXECUTE stmt USING @need; DEALLOCATE PREPARE stmt;

-- ---------- 6. 工单残留清理（死单/半开单 + 明细 + 孤儿通知）----------
DELETE FROM transfer_order_item
WHERE orderNo IN (SELECT orderNo FROM transfer_order
                  WHERE status IN ('PENDING', 'SENT', 'APPLIED'));
DELETE FROM transfer_order WHERE status IN ('PENDING', 'SENT', 'APPLIED');
DELETE n FROM notice n
LEFT JOIN transfer_order t ON n.orderNo = t.orderNo
WHERE n.orderNo IS NOT NULL AND t.orderNo IS NULL;

-- ---------- 校验 ----------
SELECT '== 库龄分布（预期漏斗形，NULL 归零）==' AS section;
SELECT CASE WHEN agingDays < 30  THEN '0-30'
            WHEN agingDays < 90  THEN '30-90'
            WHEN agingDays < 180 THEN '90-180'
            WHEN agingDays < 365 THEN '180-365'
            ELSE '365+' END AS band,
       COUNT(*) AS cnt
FROM terminal
GROUP BY band
ORDER BY FIELD(band, '0-30', '30-90', '90-180', '180-365', '365+');

SELECT '== 终端状态分布（预期：可用 27954 / 在途 400）==' AS section;
SELECT terminalStatus, COUNT(*) AS cnt FROM terminal GROUP BY terminalStatus;

SELECT '== 残留 NULL 检查（预期全 0）==' AS section;
SELECT
  (SELECT COUNT(*) FROM terminal WHERE model IS NULL) AS null_model,
  (SELECT COUNT(*) FROM terminal WHERE agingDays IS NULL) AS null_aging,
  (SELECT COUNT(*) FROM terminal WHERE firstInboundAt IS NULL) AS null_date;

SELECT '== 口径自洽抽查（DATEDIFF 与 agingDays 不一致的行数，预期 0）==' AS section;
SELECT COUNT(*) AS inconsistent
FROM terminal
WHERE DATEDIFF(NOW(), firstInboundAt) <> agingDays;

SELECT '== 补货建议演示前提回归（预期 V175 265/65，K155 305/104）==' AS section;
SELECT model,
       SUM(warehouseCode='9B00' AND terminalStatus='可用') AS wh_9B00,
       SUM(warehouseCode='9BZA' AND terminalStatus='可用') AS wh_9BZA
FROM terminal
WHERE model IN ('V175','K155','HN8346','B860AV','HG521')
GROUP BY model;

SELECT '== 工单状态（预期仅 DONE / REJECTED）==' AS section;
SELECT status, COUNT(*) AS cnt FROM transfer_order GROUP BY status;

SELECT '== 孤儿通知（预期 0）==' AS section;
SELECT COUNT(*) AS orphan_notices
FROM notice n LEFT JOIN transfer_order t ON n.orderNo = t.orderNo
WHERE n.orderNo IS NOT NULL AND t.orderNo IS NULL;
