-- ============================================================
--  终端 AI 智慧管理平台 · 平台库 api_db（仅表结构，不含数据）
-- ------------------------------------------------------------
--  生成方式：mysqldump --no-data，MySQL 8.0
--  用途：新环境建库。数据请另跑 10-seed-*.sql（配置类种子）与导入脚本（业务数据）
--  注意：本文件不含任何业务数据、账号口令、密钥
-- ============================================================
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE DATABASE IF NOT EXISTS `api_db`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `api_db`;

CREATE TABLE `a_auth_token` (
  `token` char(36) NOT NULL COMMENT 'UUID',
  `user_code` varchar(64) NOT NULL,
  `role` varchar(16) NOT NULL COMMENT '签发时角色快照，校验不再回查账号',
  `expires_at` datetime NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`token`),
  KEY `idx_token_user` (`user_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录令牌（24h 过期）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_token_quota` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `scope` varchar(16) NOT NULL COMMENT 'USER/AGENT/TENANT',
  `target_code` varchar(64) NOT NULL COMMENT '按 scope 解释：userId / agentCode / entCode',
  `monthly_tokens` bigint NOT NULL COMMENT '月度 token 上限（0=封禁，NULL 不支持，用大数表示不限）',
  `status` varchar(16) NOT NULL DEFAULT 'active' COMMENT 'active/disabled',
  `remark` varchar(200) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_target` (`scope`,`target_code`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='token 月度配额';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_token_usage_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码',
  `user_id` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户ID',
  `usage_date` date NOT NULL COMMENT '统计日期',
  `model` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型名称',
  `agent_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '产生用量的数字人（参考维度）',
  `request_count` int NOT NULL DEFAULT '0' COMMENT '请求次数',
  `total_prompt_tokens` int NOT NULL DEFAULT '0' COMMENT '累计提示词 token',
  `total_completion_tokens` int NOT NULL DEFAULT '0' COMMENT '累计生成 token',
  `total_tokens` int NOT NULL DEFAULT '0' COMMENT '累计总 token',
  `total_tool_calls` int NOT NULL DEFAULT '0' COMMENT '累计工具调用次数',
  `estimated_cost` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '估算费用（元）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_daily` (`ent_code`,`user_id`,`usage_date`,`model`),
  KEY `idx_ent_date` (`ent_code`,`usage_date`)
) ENGINE=InnoDB AUTO_INCREMENT=220 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每日 token 用量汇总表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_token_usage_monthly` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码',
  `usage_month` varchar(7) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '统计月份（yyyy-MM）',
  `model` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型名称',
  `request_count` int NOT NULL DEFAULT '0' COMMENT '请求次数',
  `total_prompt_tokens` bigint NOT NULL DEFAULT '0' COMMENT '累计提示词 token',
  `total_completion_tokens` bigint NOT NULL DEFAULT '0' COMMENT '累计生成 token',
  `total_tokens` bigint NOT NULL DEFAULT '0' COMMENT '累计总 token',
  `active_users` int NOT NULL DEFAULT '0' COMMENT '活跃用户数',
  `estimated_cost` decimal(12,4) NOT NULL DEFAULT '0.0000' COMMENT '估算费用（元）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_monthly` (`ent_code`,`usage_month`,`model`),
  KEY `idx_usage_month` (`usage_month`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每月 token 用量汇总表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_code` varchar(64) NOT NULL COMMENT '登录名，同时是对话链路的 userId',
  `user_name` varchar(64) NOT NULL COMMENT '显示名',
  `password_hash` char(64) NOT NULL COMMENT 'SHA2(password,256) hex',
  `role` varchar(16) NOT NULL COMMENT 'ADMIN / USER / SERVICE',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `warehouse_code` varchar(32) DEFAULT NULL COMMENT '所属仓位编码（业务角色用）',
  `warehouse_name` varchar(64) DEFAULT NULL COMMENT '所属仓位名（冗余存储，前端直接显示）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_code` (`user_code`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录账号（内核鉴权用）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `interface_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(256) COLLATE utf8mb4_general_ci NOT NULL COMMENT '名称',
  `description` varchar(256) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '描述',
  `url` varchar(512) COLLATE utf8mb4_general_ci NOT NULL COMMENT '接口地址',
  `requestParams` text COLLATE utf8mb4_general_ci COMMENT '请求参数',
  `requestHeader` text COLLATE utf8mb4_general_ci COMMENT '璇锋眰澶',
  `responseHeader` text COLLATE utf8mb4_general_ci COMMENT '鍝嶅簲澶',
  `status` int NOT NULL DEFAULT '0' COMMENT '接口状态 (0-关闭, 1-开启)',
  `method` varchar(256) COLLATE utf8mb4_general_ci NOT NULL COMMENT '请求类型',
  `userId` bigint NOT NULL COMMENT '鍒涘缓浜',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除（0-未删除, 1-已删除）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=74 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='鎺ュ彛淇℃伅';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notice` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `receiver` varchar(64) NOT NULL COMMENT '接收方',
  `noticeType` varchar(32) NOT NULL COMMENT 'ORDER_CREATED/OUTBOUND/INBOUND/REJECTED/LOGISTICS',
  `title` varchar(128) NOT NULL,
  `content` varchar(1024) DEFAULT NULL,
  `orderNo` varchar(64) DEFAULT NULL COMMENT '关联调拨单号',
  `isRead` tinyint NOT NULL DEFAULT '0',
  `createdAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_notice_receiver` (`receiver`,`isRead`),
  KEY `idx_notice_order` (`orderNo`)
) ENGINE=InnoDB AUTO_INCREMENT=114 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `age` int DEFAULT NULL COMMENT '年龄',
  `gender` tinyint NOT NULL DEFAULT '0' COMMENT '性别（0-男, 1-女）',
  `education` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '学历',
  `place` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '地点',
  `job` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '职业',
  `contact` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '联系方式',
  `loveExp` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '感情经历',
  `content` text COLLATE utf8mb4_general_ci COMMENT '内容（个人介绍）',
  `photo` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '照片地址',
  `reviewStatus` int NOT NULL DEFAULT '0' COMMENT '状态（0-待审核, 1-通过, 2-拒绝）',
  `reviewMessage` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '审核信息',
  `viewNum` int NOT NULL DEFAULT '0' COMMENT '浏览数',
  `thumbNum` int NOT NULL DEFAULT '0' COMMENT '鐐硅禐鏁',
  `userId` bigint NOT NULL COMMENT '创建用户 id',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='甯栧瓙';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `terminal` (
  `serialNo` varchar(64) NOT NULL COMMENT '终端串码（PK）',
  `bigType` varchar(64) DEFAULT NULL COMMENT '终端大类',
  `smallType` varchar(64) DEFAULT NULL COMMENT '终端小类',
  `vendor` varchar(64) DEFAULT NULL COMMENT '终端厂家',
  `model` varchar(128) DEFAULT NULL COMMENT '终端型号',
  `materialCode` varchar(64) DEFAULT NULL COMMENT '物料编码',
  `materialName` varchar(256) DEFAULT NULL COMMENT '物料名称',
  `purchaseNo` varchar(128) DEFAULT NULL COMMENT '采购单号',
  `terminalStatus` varchar(32) NOT NULL COMMENT '终端状态',
  `inboundType` varchar(32) DEFAULT NULL COMMENT '入库类型',
  `businessType` varchar(32) DEFAULT NULL COMMENT '业务类型',
  `purchaseType` varchar(32) DEFAULT NULL COMMENT '采购类型',
  `statusChangedAt` datetime DEFAULT NULL COMMENT '状态变更时间',
  `lv1Warehouse` varchar(128) DEFAULT NULL COMMENT '一级仓位名（省公司）',
  `lv2Warehouse` varchar(128) DEFAULT NULL COMMENT '二级仓位名（地市）',
  `lv3Warehouse` varchar(128) DEFAULT NULL COMMENT '三级仓位名（区县）',
  `lv4Warehouse` varchar(128) DEFAULT NULL COMMENT '四级仓位名（网格）',
  `ownerWarehouse` varchar(128) DEFAULT NULL COMMENT '归属仓位名',
  `warehouseCode` varchar(32) DEFAULT NULL COMMENT '仓位编码（FK → warehouse）',
  `targetWarehouse` varchar(128) DEFAULT NULL COMMENT '目标仓位',
  `unitPrice` decimal(12,4) DEFAULT NULL COMMENT '单价（元）',
  `customerPhone` varchar(64) DEFAULT NULL COMMENT '客户号码',
  `receiver` varchar(64) DEFAULT NULL COMMENT '领用人',
  `receiverNo` varchar(64) DEFAULT NULL COMMENT '领用工号',
  `boxNo` varchar(64) DEFAULT NULL COMMENT '箱号',
  `firstInboundAt` datetime DEFAULT NULL COMMENT '首次入库时间',
  `agingDays` int DEFAULT NULL COMMENT '库龄（天）',
  `warrantyStart` datetime DEFAULT NULL COMMENT '保修起始时间',
  `warrantyEnd` datetime DEFAULT NULL COMMENT '保修结束时间',
  `operator` varchar(64) DEFAULT NULL COMMENT '操作人',
  `operatorNo` varchar(64) DEFAULT NULL COMMENT '操作人工号',
  `secondInboundAt` datetime DEFAULT NULL COMMENT '二次入库时间',
  `lostCompensation` decimal(12,4) DEFAULT NULL COMMENT '丢失赔偿价格',
  `createdAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updatedAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`serialNo`),
  KEY `idx_terminal_wh_status` (`warehouseCode`,`terminalStatus`),
  KEY `idx_terminal_material` (`materialCode`),
  KEY `idx_terminal_model` (`model`),
  KEY `idx_terminal_status` (`terminalStatus`),
  KEY `idx_terminal_aging` (`agingDays`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='终端台账（串码级全生命周期）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transfer_order` (
  `orderNo` varchar(64) NOT NULL COMMENT '调拨单号',
  `operationType` varchar(16) NOT NULL COMMENT 'TRANSFER 调拨 / ISSUE 下发 / RECALL 收回 / RETURN 回退',
  `fromWarehouse` varchar(32) NOT NULL COMMENT '调出仓位编码',
  `toWarehouse` varchar(32) NOT NULL COMMENT '调入仓位编码',
  `status` varchar(32) NOT NULL COMMENT 'PENDING/SENT/ACCOUNT_CONFIRMED/OUTBOUND/DONE/REJECTED',
  `itemCount` int NOT NULL DEFAULT '0' COMMENT '明细种类数',
  `totalQuantity` int NOT NULL DEFAULT '0' COMMENT '总数量',
  `needApproval` tinyint NOT NULL DEFAULT '0' COMMENT '是否需要省级审批',
  `creator` varchar(64) DEFAULT NULL COMMENT '创建人',
  `rejectReason` varchar(512) DEFAULT NULL COMMENT '拒绝原因',
  `source` varchar(16) NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL 手工 / AI 智能体生成',
  `suggestionId` varchar(64) DEFAULT NULL COMMENT '关联的补货建议 ID（AI 建单时记录）',
  `createdAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `outboundAt` datetime DEFAULT NULL COMMENT '出库时间',
  `inboundAt` datetime DEFAULT NULL COMMENT '入库时间',
  `sentAt` datetime DEFAULT NULL COMMENT '工单发送时间',
  `accountConfirmedAt` datetime DEFAULT NULL COMMENT '调出方账管确认时间',
  `keeperConfirmedAt` datetime DEFAULT NULL COMMENT '调出方仓管确认（出库执行）时间',
  `senderUser` varchar(64) DEFAULT NULL COMMENT '发送工单的人（账号）',
  `accountConfirmer` varchar(64) DEFAULT NULL COMMENT '确认的账管账号',
  `keeperConfirmer` varchar(64) DEFAULT NULL COMMENT '确认的仓管账号',
  PRIMARY KEY (`orderNo`),
  KEY `idx_order_from` (`fromWarehouse`),
  KEY `idx_order_to` (`toWarehouse`),
  KEY `idx_order_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调拨单';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transfer_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `orderNo` varchar(64) NOT NULL COMMENT '调拨单号',
  `materialCode` varchar(64) NOT NULL COMMENT '物料编码',
  `model` varchar(128) DEFAULT NULL COMMENT '规格型号',
  `materialName` varchar(256) DEFAULT NULL COMMENT '物料名称',
  `quantity` int NOT NULL COMMENT '数量',
  `serialNos` json DEFAULT NULL COMMENT '串码明细',
  `createdAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_item_order` (`orderNo`),
  KEY `idx_item_material` (`materialCode`)
) ENGINE=InnoDB AUTO_INCREMENT=137 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调拨单明细';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `userName` varchar(256) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '用户昵称',
  `userAccount` varchar(256) COLLATE utf8mb4_general_ci NOT NULL COMMENT '账号',
  `userAvatar` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '用户头像',
  `gender` tinyint DEFAULT NULL COMMENT '性别',
  `userRole` varchar(256) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'user' COMMENT '用户角色：user / admin',
  `userPassword` varchar(512) COLLATE utf8mb4_general_ci NOT NULL COMMENT '密码',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除',
  `accessKey` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '签名 accessKey',
  `secretKey` varchar(512) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '签名 secretKey',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uni_userAccount` (`userAccount`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='鐢ㄦ埛';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_interface_info` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `userId` bigint NOT NULL COMMENT '调用用户id',
  `interfaceInfoId` bigint NOT NULL COMMENT '接口id',
  `totalNum` int NOT NULL DEFAULT '0' COMMENT '总调用次数',
  `leftNum` int NOT NULL DEFAULT '0' COMMENT '剩余调用次数',
  `status` int NOT NULL DEFAULT '0' COMMENT '0-正常, 1-禁用',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除（0-未删除, 1-已删除）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=55 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='鐢ㄦ埛璋冪敤鎺ュ彛鍏崇郴';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_quota` (
  `userId` varchar(64) NOT NULL COMMENT '用户标识',
  `dailyLimit` bigint NOT NULL DEFAULT '200000' COMMENT '每日 token 限额',
  `enabled` tinyint NOT NULL DEFAULT '1',
  `updatedAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`userId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户每日 token 限额';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_token_usage` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `userId` varchar(64) NOT NULL COMMENT '用户标识',
  `usageDate` date NOT NULL COMMENT '统计日期',
  `feature` varchar(32) NOT NULL COMMENT '功能点：kb/replenish/order',
  `calls` int NOT NULL DEFAULT '0' COMMENT '调用次数',
  `tokensIn` bigint NOT NULL DEFAULT '0',
  `tokensOut` bigint NOT NULL DEFAULT '0',
  `blocked` int NOT NULL DEFAULT '0' COMMENT '被限额拦截次数',
  `createdAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updatedAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_date_feature` (`userId`,`usageDate`,`feature`),
  KEY `idx_usage_date` (`usageDate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='token 用量账本（按人/天/功能点）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `warehouse` (
  `warehouseCode` varchar(32) NOT NULL COMMENT '仓位编码（业务主键，源数据 7A00/7AZA 这类 4 位码）',
  `name` varchar(128) NOT NULL COMMENT '仓位名称（源数据"归属仓位"列）',
  `level` tinyint NOT NULL COMMENT '层级：1=省 2=地市 3=区县 4=网格 5=智家(虚拟)',
  `parentCode` varchar(32) DEFAULT NULL COMMENT '上级仓位编码',
  `province` varchar(64) DEFAULT NULL COMMENT 'ʡ',
  `city` varchar(64) DEFAULT NULL COMMENT '地市',
  `district` varchar(64) DEFAULT NULL COMMENT '区县',
  `createdAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`warehouseCode`),
  KEY `idx_warehouse_parent` (`parentCode`),
  KEY `idx_warehouse_level` (`level`),
  KEY `idx_warehouse_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仓位（四级仓储体系 + 智家虚拟五级）';
/*!40101 SET character_set_client = @saved_cs_client */;
