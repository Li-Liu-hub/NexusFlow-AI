-- ============================================================
-- 40-terminal-schema.sql — 终端业务 5 张表（M1）
-- 对应契约：docs/contracts/01-数据模型.md
-- 幂等：全部 IF NOT EXISTS，可重复执行
-- 库：api_db
-- ============================================================

USE api_db;

-- ---------- 重建 ----------
-- 列名一律用**驼峰**：aismp-backend 的 mybatis-plus 配了
--   map-underscore-to-camel-case: false
-- 所以实体字段名必须与列名逐字相同（全项目的 DDL 都是这个约定，如 createTime/userId）。
-- 表名保持下划线（与 interface_info / user_interface_info 一致）。
DROP TABLE IF EXISTS terminal;
DROP TABLE IF EXISTS warehouse;
DROP TABLE IF EXISTS transfer_order;
DROP TABLE IF EXISTS transfer_order_item;
DROP TABLE IF EXISTS notice;
DROP TABLE IF EXISTS user_token_usage;
DROP TABLE IF EXISTS user_quota;

-- ---------- 1. 仓位（四级仓储体系 + 智家虚拟五级）----------
CREATE TABLE IF NOT EXISTS `warehouse` (
  `warehouseCode` VARCHAR(32)  NOT NULL COMMENT '仓位编码（业务主键，源数据 7A00/7AZA 这类 4 位码）',
  `name`           VARCHAR(128) NOT NULL COMMENT '仓位名称（源数据"归属仓位"列）',
  `level`          TINYINT      NOT NULL COMMENT '层级：1=省 2=地市 3=区县 4=网格 5=智家(虚拟)',
  `parentCode`    VARCHAR(32)      NULL COMMENT '上级仓位编码',
  `province`       VARCHAR(64)      NULL COMMENT '省',
  `city`           VARCHAR(64)      NULL COMMENT '地市',
  `district`       VARCHAR(64)      NULL COMMENT '区县',
  `createdAt`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`warehouseCode`),
  KEY `idx_warehouse_parent` (`parentCode`),
  KEY `idx_warehouse_level`  (`level`),
  KEY `idx_warehouse_name`   (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓位（四级仓储体系 + 智家虚拟五级）';

-- ---------- 2. 终端台账（串码级，33 列）----------
CREATE TABLE IF NOT EXISTS `terminal` (
  `serialNo`         VARCHAR(64)   NOT NULL COMMENT '终端串码（PK）',
  `bigType`          VARCHAR(64)       NULL COMMENT '终端大类',
  `smallType`        VARCHAR(64)       NULL COMMENT '终端小类',
  `vendor`            VARCHAR(64)       NULL COMMENT '终端厂家',
  `model`             VARCHAR(128)      NULL COMMENT '终端型号',
  `materialCode`     VARCHAR(64)       NULL COMMENT '物料编码',
  `materialName`     VARCHAR(256)      NULL COMMENT '物料名称',
  `purchaseNo`       VARCHAR(128)      NULL COMMENT '采购单号',
  `terminalStatus`   VARCHAR(32)   NOT NULL COMMENT '终端状态',
  `inboundType`      VARCHAR(32)       NULL COMMENT '入库类型',
  `businessType`     VARCHAR(32)       NULL COMMENT '业务类型',
  `purchaseType`     VARCHAR(32)       NULL COMMENT '采购类型',
  `statusChangedAt` DATETIME          NULL COMMENT '状态变更时间',
  `lv1Warehouse`     VARCHAR(128)      NULL COMMENT '一级仓位名（省公司）',
  `lv2Warehouse`     VARCHAR(128)      NULL COMMENT '二级仓位名（地市）',
  `lv3Warehouse`     VARCHAR(128)      NULL COMMENT '三级仓位名（区县）',
  `lv4Warehouse`     VARCHAR(128)      NULL COMMENT '四级仓位名（网格）',
  `ownerWarehouse`   VARCHAR(128)      NULL COMMENT '归属仓位名',
  `warehouseCode`    VARCHAR(32)       NULL COMMENT '仓位编码（FK → warehouse）',
  `targetWarehouse`  VARCHAR(128)      NULL COMMENT '目标仓位',
  `unitPrice`        DECIMAL(12,4)     NULL COMMENT '单价（元）',
  `customerPhone`    VARCHAR(64)       NULL COMMENT '客户号码',
  `receiver`          VARCHAR(64)       NULL COMMENT '领用人',
  `receiverNo`       VARCHAR(64)       NULL COMMENT '领用工号',
  `boxNo`            VARCHAR(64)       NULL COMMENT '箱号',
  `firstInboundAt`  DATETIME          NULL COMMENT '首次入库时间',
  `agingDays`        INT               NULL COMMENT '库龄（天）',
  `warrantyStart`    DATETIME          NULL COMMENT '保修起始时间',
  `warrantyEnd`      DATETIME          NULL COMMENT '保修结束时间',
  `operator`          VARCHAR(64)       NULL COMMENT '操作人',
  `operatorNo`       VARCHAR(64)       NULL COMMENT '操作人工号',
  `secondInboundAt` DATETIME          NULL COMMENT '二次入库时间',
  `lostCompensation` DECIMAL(12,4)     NULL COMMENT '丢失赔偿价格',
  `createdAt`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updatedAt`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`serialNo`),
  KEY `idx_terminal_wh_status` (`warehouseCode`, `terminalStatus`),
  KEY `idx_terminal_material`  (`materialCode`),
  KEY `idx_terminal_model`     (`model`),
  KEY `idx_terminal_status`    (`terminalStatus`),
  KEY `idx_terminal_aging`     (`agingDays`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='终端台账（串码级全生命周期）';

-- ---------- 3. 调拨单 ----------
CREATE TABLE IF NOT EXISTS `transfer_order` (
  `orderNo`        VARCHAR(64)  NOT NULL COMMENT '调拨单号',
  `operationType`  VARCHAR(16)  NOT NULL COMMENT 'TRANSFER 调拨 / ISSUE 下发 / RECALL 收回 / RETURN 回退',
  `fromWarehouse`  VARCHAR(32)  NOT NULL COMMENT '调出仓位编码',
  `toWarehouse`    VARCHAR(32)  NOT NULL COMMENT '调入仓位编码',
  `status`          VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/ACCOUNT_CONFIRMED/OUTBOUND/DONE/REJECTED',
  `itemCount`      INT          NOT NULL DEFAULT 0 COMMENT '明细种类数',
  `totalQuantity`  INT          NOT NULL DEFAULT 0 COMMENT '总数量',
  `needApproval`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否需要省级审批',
  `creator`         VARCHAR(64)      NULL COMMENT '创建人',
  `rejectReason`   VARCHAR(512)     NULL COMMENT '拒绝原因',
  `source`          VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL 手工 / AI 智能体生成',
  `suggestionId`   VARCHAR(64)      NULL COMMENT '关联的补货建议 ID（AI 建单时记录）',
  `createdAt`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `outboundAt`     DATETIME         NULL COMMENT '出库时间（手工路径）',
  `inboundAt`      DATETIME         NULL COMMENT '入库时间（手工路径）',
  -- 账管/仓管工单闭环（2026-10-06）：建单 → 发送 → 账管确认 → 仓管确认（库存变动）
  `sentAt`               DATETIME     NULL COMMENT '工单发送时间',
  `accountConfirmedAt`   DATETIME     NULL COMMENT '调出方账管确认时间',
  `keeperConfirmedAt`    DATETIME     NULL COMMENT '调出方仓管确认（执行调拨）时间',
  `senderUser`           VARCHAR(64)  NULL COMMENT '发送工单的人（账号）',
  `accountConfirmer`     VARCHAR(64)  NULL COMMENT '确认的账管账号',
  `keeperConfirmer`      VARCHAR(64)  NULL COMMENT '确认的仓管账号',
  PRIMARY KEY (`orderNo`),
  KEY `idx_order_from`   (`fromWarehouse`),
  KEY `idx_order_to`     (`toWarehouse`),
  KEY `idx_order_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨单';

-- ---------- 4. 调拨单明细 ----------
CREATE TABLE IF NOT EXISTS `transfer_order_item` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `orderNo`      VARCHAR(64)  NOT NULL COMMENT '调拨单号',
  `materialCode` VARCHAR(64)  NOT NULL COMMENT '物料编码',
  `model`         VARCHAR(128)     NULL COMMENT '规格型号',
  `materialName` VARCHAR(256)     NULL COMMENT '物料名称',
  `quantity`      INT          NOT NULL COMMENT '数量',
  `serialNos`    JSON             NULL COMMENT '串码明细',
  `createdAt`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_item_order`    (`orderNo`),
  KEY `idx_item_material` (`materialCode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨单明细';

-- ---------- 5. 站内通知 ----------
CREATE TABLE IF NOT EXISTS `notice` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `receiver`    VARCHAR(64)   NOT NULL COMMENT '接收方',
  `noticeType` VARCHAR(32)   NOT NULL COMMENT 'ORDER_CREATED/OUTBOUND/INBOUND/REJECTED/LOGISTICS',
  `title`       VARCHAR(128)  NOT NULL,
  `content`     VARCHAR(1024)     NULL,
  `orderNo`    VARCHAR(64)       NULL COMMENT '关联调拨单号',
  `isRead`     TINYINT       NOT NULL DEFAULT 0,
  `createdAt`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_notice_receiver` (`receiver`, `isRead`),
  KEY `idx_notice_order`    (`orderNo`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知';

-- ---------- 6. token 账本（M4）----------
CREATE TABLE IF NOT EXISTS `user_token_usage` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `userId`     VARCHAR(64) NOT NULL COMMENT '用户标识',
  `usageDate`  DATE        NOT NULL COMMENT '统计日期',
  `feature`     VARCHAR(32) NOT NULL COMMENT '功能点：kb/replenish/order',
  `calls`       INT         NOT NULL DEFAULT 0 COMMENT '调用次数',
  `tokensIn`   BIGINT      NOT NULL DEFAULT 0,
  `tokensOut`  BIGINT      NOT NULL DEFAULT 0,
  `blocked`     INT         NOT NULL DEFAULT 0 COMMENT '被限额拦截次数',
  `createdAt`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updatedAt`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_date_feature` (`userId`, `usageDate`, `feature`),
  KEY `idx_usage_date` (`usageDate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='token 用量账本（按人/天/功能点）';

-- ---------- 7. 限额配置（M4）----------
CREATE TABLE IF NOT EXISTS `user_quota` (
  `userId`       VARCHAR(64) NOT NULL COMMENT '用户标识',
  `dailyLimit`   BIGINT      NOT NULL DEFAULT 200000 COMMENT '每日 token 限额',
  `enabled`       TINYINT     NOT NULL DEFAULT 1,
  `updatedAt`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`userId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户每日 token 限额';

SELECT 'terminal schema ready' AS msg;
