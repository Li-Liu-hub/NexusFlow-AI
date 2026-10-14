-- ============================================================================
-- 94: 验收/演示用库存灌数据（2026-10-10，幂等可重跑）
-- ============================================================================
-- 背景：补货建议的分布口径算法（tools/algorithms.py）判定条件是
--     短缺：可用 < 同级均值 × LOW_STOCK_RATIO(0.6)
--     富余：可用 > 同级均值 × SURPLUS_RATIO(1.4)
--   两仓场景下 avg=(q00+qZA)/2，化简后**等价于要求 q00 > 2.33 × qZA**。
--
-- 现况：多轮演示调拨已把库存"调平"——主力型号的比值几乎全是 1.00
--   （F657 1.01 / PT9500 1.00 / B671 1.03 / F7615 1.00 / PT1570 1.00 /
--     IP810N 1.00 / G1650 1.00 / G100L 1.00 / K155 1.00 / V175 1.00 …）
--   → 算法合法地返回 0 条调拨建议，验收时"没有建议可点"。
--
-- 另一个硬约束（容易漏）：调拨只允许**上级→下级**
--   （algorithms.py：`if src.warehouseLevel == target.level: continue` +
--    `if src.warehouseLevel > target.level: continue`）
--   本库只有两个仓：9B00(level=2 地市) → 9BZA(level=3 区县)，方向只能是 9B00→9BZA。
--   所以必须让 **9B00 富余、9BZA 短缺**；反向（如 B860AV 现为 115 vs 536）只能转采购。
--
-- 第三个硬约束：两边必须有**共同 materialCode**（`_split_by_materials`），
--   否则 trace 记"两边无共同物料，无法履约"而跳过。
--   ⇒ 9BZA 不能清零，每型号保留 KEEP_QTY 台。
--
-- 做法（两步，都幂等）：
--   ① 回挪：把 9BZA 的可用库存挪回 9B00，9BZA 只留 KEEP_QTY 台
--   ② 新增：向 9B00 批量灌入 ADD_QTY 台/型号，使可调拨额度充足、可反复验收
-- 效果：比值 ≈ (原 9B00 + 原 9BZA − 60 + 300) / 60，远超 2.33
-- ============================================================================

USE api_db;

-- ---------------------------- 可调参数 ----------------------------
-- KEEP_QTY：每型号在 9BZA 保留的可用台数（要 >0，保证有共同物料）
-- ADD_QTY ：每型号向 9B00 新增的台数（"灌输多点"）
-- 改这两个值直接改下面的内联常量即可（见 §1 的 HAVING 与 §2 的 nums 上限）。

-- ============================ 0) 幂等清理 ============================
-- 本脚本生成的行用可辨识串码：'9B00' + 8 位十进制序号（12 位，与真实串码同长且合法十六进制）
-- 真实串码是随机 12 位十六进制，恰好全为 '9B00' 开头 + 全数字的概率可忽略
DELETE FROM terminal WHERE serialNo REGEXP '^9B00[0-9]{8}$';

-- ============================ 1) 回挪：9BZA → 9B00 ============================
-- 每个目标型号在 9BZA 只留 60 台（按 serialNo 升序，取前 60），其余挪到 9B00。
-- 同时把 lv3/lv4 清空、ownerWarehouse 对齐 9B00 的格式（9B00 的行 lv3 为空、owner=东宁市分公司）。
--
-- 注意 UPDATE ... JOIN (子查询同表) 会触发 MySQL ERROR 1093，
-- 必须把子查询再包一层派生表强制物化（下面对 SELECT 做了双重嵌套）。
UPDATE terminal t
JOIN (
  SELECT * FROM (
    SELECT serialNo,
           ROW_NUMBER() OVER (PARTITION BY model ORDER BY serialNo) AS rn
    FROM terminal
    WHERE warehouseCode = '9BZA'
      AND terminalStatus = '可用'
      AND model IN ('F657','PT9500','B671','F7615','PT1570',
                    'IP810N','IP811N','G1650','G100L','QJ82','B866','K155','V175')
  ) x
  WHERE x.rn > 60
) d ON d.serialNo = t.serialNo
SET t.warehouseCode   = '9B00',
    t.ownerWarehouse  = '东宁市分公司',
    t.lv3Warehouse    = NULL,
    t.lv4Warehouse    = NULL,
    t.updatedAt       = NOW();

-- ============================ 2) 新增：批量灌入 9B00 ============================
-- 每型号 300 台，字段从该型号在 9B00 的现有可用行"照抄"（保证 bigType/vendor/
-- materialName/unitPrice 等与真实数据一致），只改串码、库龄、时间。
-- 库龄刻意设成 200~499 天（≥ LONG_AGING_DAYS=180），
-- 让 9B00 被富余仓排序时命中"长库龄，优先盘活"分支。
INSERT INTO terminal (
  serialNo, bigType, smallType, vendor, model, materialCode, materialName,
  purchaseNo, terminalStatus, inboundType, businessType, purchaseType, statusChangedAt,
  lv1Warehouse, lv2Warehouse, lv3Warehouse, lv4Warehouse, ownerWarehouse, warehouseCode,
  targetWarehouse, unitPrice, customerPhone, receiver, receiverNo, boxNo,
  firstInboundAt, agingDays, warrantyStart, warrantyEnd, operator, operatorNo,
  secondInboundAt, lostCompensation, createdAt, updatedAt
)
SELECT
  CONCAT('9B00', LPAD(t.rn, 8, '0')),          -- 12 位可辨识串码
  s.bigType, s.smallType, s.vendor, s.model, s.materialCode, s.materialName,
  s.purchaseNo, '可用', '新终端', s.businessType, s.purchaseType, NOW(),
  s.lv1Warehouse, s.lv2Warehouse, NULL, NULL, s.ownerWarehouse, '9B00',
  NULL, s.unitPrice, NULL, NULL, NULL, NULL,
  DATE_SUB(NOW(), INTERVAL 400 DAY),           -- 首次入库
  200 + (t.rn % 300),                          -- 库龄 200~499 天（≥180，算长库龄）
  DATE_SUB(NOW(), INTERVAL 500 DAY),
  DATE_ADD(NOW(), INTERVAL 300 DAY),
  '-', NULL, NULL, NULL, NOW(), NOW()
FROM (
  SELECT m.model, n.n, ROW_NUMBER() OVER (ORDER BY m.model, n.n) AS rn
  FROM (
    SELECT 'F657' AS model UNION ALL SELECT 'PT9500' UNION ALL SELECT 'B671'
    UNION ALL SELECT 'F7615' UNION ALL SELECT 'PT1570' UNION ALL SELECT 'IP810N'
    UNION ALL SELECT 'IP811N' UNION ALL SELECT 'G1650' UNION ALL SELECT 'G100L'
    UNION ALL SELECT 'QJ82' UNION ALL SELECT 'B866' UNION ALL SELECT 'K155'
    UNION ALL SELECT 'V175'
  ) m
  JOIN (
    WITH RECURSIVE nums(n) AS (
      SELECT 1 UNION ALL SELECT n + 1 FROM nums WHERE n < 300
    )
    SELECT n FROM nums
  ) n
) t
JOIN (   -- 每型号取一个 9B00 的可用行当模板
  SELECT model, MIN(serialNo) AS tpl
  FROM terminal
  WHERE warehouseCode = '9B00' AND terminalStatus = '可用'
    AND model IN ('F657','PT9500','B671','F7615','PT1570',
                  'IP810N','IP811N','G1650','G100L','QJ82','B866','K155','V175')
  GROUP BY model
) tp ON tp.model = t.model
JOIN terminal s ON s.serialNo = tp.tpl;

-- ============================ 3) 校验 ============================
SELECT '--- 灌完后各型号 9B00 vs 9BZA（比值需 > 2.33） ---' AS x;
SELECT CONCAT(RPAD(model, 10, ' '),
              ' 9B00=', RPAD(SUM(warehouseCode = '9B00' AND terminalStatus = '可用'), 6, ' '),
              ' 9BZA=', RPAD(SUM(warehouseCode = '9BZA' AND terminalStatus = '可用'), 6, ' '),
              ' 比值=', ROUND(SUM(warehouseCode = '9B00' AND terminalStatus = '可用')
                          / GREATEST(SUM(warehouseCode = '9BZA' AND terminalStatus = '可用'), 1), 2))
FROM terminal
GROUP BY model
HAVING SUM(warehouseCode = '9BZA' AND terminalStatus = '可用') > 0
ORDER BY SUM(warehouseCode = '9B00' AND terminalStatus = '可用') DESC
LIMIT 16;

SELECT '--- 总量 ---' AS x;
SELECT CONCAT('终端总数 = ', COUNT(*), '，其中可用 = ', SUM(terminalStatus = '可用')) FROM terminal;
SELECT CONCAT('本脚本生成的行 = ', COUNT(*)) FROM terminal WHERE serialNo REGEXP '^9B00[0-9]{8}$';
