-- 85: r12 门户闭环 E2E 前提修复（2026-10-08，幂等可重跑）
--
-- 背景：多日演示/对话调拨后，A 仓(9BZA)在 B 仓(9B00)富余（行可用≥200）的每个
-- 「物料×型号」上都已 ≥50 —— health/suggest 的阈值建议（本仓<50 才缺、对方≥200 才调）
-- 因此合法地返回空清单，_r12 的「A 缺货」前提失效。
--
-- 修法：把 A 仓可用 V175 动态回挪到恰好剩 49 台（<50 触发短缺）到 B 仓，
-- 不删任何数据、不造伪串码 —— 相当于这批货从未调出过。
-- 每次重跑 _r12 之前执行一次本脚本即可（V175 在 A 会回到 49）。
--
-- 附：清理 _r14 探针工单（若存在）。

SET @drain = GREATEST(0,
  (SELECT COUNT(*) FROM terminal
    WHERE warehouseCode='9BZA' AND terminalStatus='可用'
      AND materialCode='7450301883' AND model='V175') - 49);
SET @sql = 'UPDATE terminal SET warehouseCode = ''9B00''
            WHERE warehouseCode = ''9BZA'' AND terminalStatus = ''可用''
              AND materialCode = ''7450301883'' AND model = ''V175''
            LIMIT ?';
PREPARE stmt FROM @sql;
EXECUTE stmt USING @drain;
DEALLOCATE PREPARE stmt;

DELETE FROM transfer_order_item WHERE orderNo = 'TR202610083261';
DELETE FROM transfer_order WHERE orderNo = 'TR202610083261';

-- 核对（预期：A 侧 V175 可用 = 49，B 侧 = 281 + 累计回挪 - 历史调出）
SELECT warehouseCode, materialCode, model, COUNT(*) AS avail
FROM terminal
WHERE terminalStatus = '可用' AND materialCode = '7450301883' AND model = 'V175'
GROUP BY warehouseCode, materialCode, model;
