-- 88: 补货建议演示前提（2026-10-08，幂等可重跑）
--
-- 背景：多轮演示调拨把 9B00/9BZA 各型号库存"调平"（多数型号两仓数量近乎相同，
-- 比值≈1.0），分布口径算法（可用 < 同级均值×0.6 短缺 / > ×1.4 富余）合法地返回
-- procure_only（0 条调拨），演示「补货建议→申请调拨→出库→入库」链路时无调拨建议可点。
--
-- 修法：把部分可用终端从下级仓 9BZA 回挪到上级仓 9B00（相当于这批货从未调出），
-- 人为拉开分布（调拨只允许上级→下级，故必须让 9B00 富余、9BZA 短缺）：
--   V175(7450301883): 9BZA 165→65，9B00 165→265 → 建议调拨 100 台 9B00→9BZA
--   K155(7450301877): 9BZA 204→104，9B00 205→305 → 建议调拨 100 台 9B00→9BZA
--   HN8346           : 9BZA 55→42，9B00 7→20   → 消除一条 9B00 侧反向缺口的采购噪音
--   B860AV(116 vs 699)、HG521(27 vs 6，两仓无共同物料编码) 保持不动 →
--   门户视角保留 1 条 HG521×10 采购建议，整体策略 mixed（先调拨后采购），叙事最完整。
--
-- 演示跑完一轮调拨闭环后建议会再次被"调平"（V175 回到 165/265），重跑本脚本即可恢复。

-- V175 → 9BZA 剩 65
SET @drain = GREATEST(0,
  (SELECT COUNT(*) FROM terminal
    WHERE warehouseCode='9BZA' AND terminalStatus='可用'
      AND materialCode='7450301883' AND model='V175') - 65);
SET @sql = 'UPDATE terminal SET warehouseCode = ''9B00''
            WHERE warehouseCode = ''9BZA'' AND terminalStatus = ''可用''
              AND materialCode = ''7450301883'' AND model = ''V175''
            LIMIT ?';
PREPARE stmt FROM @sql;
EXECUTE stmt USING @drain;
DEALLOCATE PREPARE stmt;

-- K155 → 9BZA 剩 104
SET @drain = GREATEST(0,
  (SELECT COUNT(*) FROM terminal
    WHERE warehouseCode='9BZA' AND terminalStatus='可用'
      AND materialCode='7450301877' AND model='K155') - 104);
SET @sql = 'UPDATE terminal SET warehouseCode = ''9B00''
            WHERE warehouseCode = ''9BZA'' AND terminalStatus = ''可用''
              AND materialCode = ''7450301877'' AND model = ''K155''
            LIMIT ?';
PREPARE stmt FROM @sql;
EXECUTE stmt USING @drain;
DEALLOCATE PREPARE stmt;

-- HN8346 → 9BZA 剩 42（两仓 20/42，同级均值 31，双双落回 [0.6, 1.4] 区间）
SET @drain = GREATEST(0,
  (SELECT COUNT(*) FROM terminal
    WHERE warehouseCode='9BZA' AND terminalStatus='可用'
      AND model='HN8346') - 42);
SET @sql = 'UPDATE terminal SET warehouseCode = ''9B00''
            WHERE warehouseCode = ''9BZA'' AND terminalStatus = ''可用''
              AND model = ''HN8346''
            LIMIT ?';
PREPARE stmt FROM @sql;
EXECUTE stmt USING @drain;
DEALLOCATE PREPARE stmt;

-- 核对（预期：V175 265/65，K155 305/104，HN8346 20/42，B860AV 116/699，HG521 27/6）
SELECT model,
       SUM(warehouseCode='9B00') AS wh_9B00,
       SUM(warehouseCode='9BZA') AS wh_9BZA
FROM terminal
WHERE terminalStatus='可用' AND model IN ('V175','K155','HN8346','B860AV','HG521')
GROUP BY model;
