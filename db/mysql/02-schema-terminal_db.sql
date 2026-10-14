-- ============================================================
--  终端 AI 智慧管理平台 · 旧业务库 terminal_db（仅表结构，不含数据）
-- ------------------------------------------------------------
--  ⚠️ 该库已被 api_db 取代（当前生产为 api_db，见 01-schema-api_db.sql）。
--     保留结构仅作对照与历史参考，新部署无需执行。
-- ============================================================
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE DATABASE IF NOT EXISTS `terminal_db`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `terminal_db`;

CREATE TABLE `a_agent` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `agent_code` varchar(64) NOT NULL COMMENT '数字人编码，运行时按它装配',
  `agent_name` varchar(128) NOT NULL COMMENT '显示名',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像 URL',
  `description` varchar(500) DEFAULT NULL COMMENT '一句话介绍',
  `system_prompt` text NOT NULL COMMENT '人设提示词（基底）',
  `model_name` varchar(64) NOT NULL DEFAULT 'mimo-v2.6-flash' COMMENT '模型名',
  `temperature` decimal(3,2) NOT NULL DEFAULT '0.70',
  `max_turns` int NOT NULL DEFAULT '10' COMMENT 'ReAct 最大轮次',
  `knowledge_base_id` bigint DEFAULT NULL COMMENT '绑定知识库 → a_knowledge_base.id',
  `status` varchar(16) NOT NULL DEFAULT 'active' COMMENT 'active/disabled',
  `version` int NOT NULL DEFAULT '1' COMMENT '配置版本号，任何变更 +1（热更新依据）',
  `tenant_code` varchar(32) DEFAULT NULL COMMENT '租户编码，空=不限',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_code` (`agent_code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数字人定义';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_agent_role_rel` (
  `agent_id` bigint NOT NULL COMMENT '→ a_agent.id',
  `role_code` varchar(32) NOT NULL COMMENT '→ a_role_def.role_code',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '路由候选顺序（同分时靠前优先）',
  PRIMARY KEY (`agent_id`,`role_code`),
  KEY `idx_role` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-数字人分配';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_agent_skill` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `skill_code` varchar(64) NOT NULL COMMENT '技能编码',
  `skill_name` varchar(128) NOT NULL COMMENT '技能名',
  `description` varchar(500) DEFAULT NULL COMMENT '技能描述（渐进披露时给 LLM 看）',
  `prompt_fragment` text COMMENT '追加到 system_prompt 之后',
  `examples` text COMMENT 'Few-shot 示例（JSON 数组）',
  `status` varchar(16) NOT NULL DEFAULT 'active',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_code` (`skill_code`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技能包';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_agent_skill_rel` (
  `agent_id` bigint NOT NULL,
  `skill_id` bigint NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '拼接顺序',
  PRIMARY KEY (`agent_id`,`skill_id`),
  KEY `idx_skill` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数字人-技能 挂载';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_agent_tool_rel` (
  `agent_id` bigint NOT NULL,
  `tool_id` bigint NOT NULL COMMENT '→ a_llm_tool.id',
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`agent_id`,`tool_id`),
  KEY `idx_tool` (`tool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数字人-工具 挂载';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
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
CREATE TABLE `a_billing_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码',
  `plan_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '当前套餐编码',
  `balance` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '账户余额（元）',
  `total_recharged` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '累计充值金额',
  `total_consumed` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '累计消费金额',
  `used_tokens_this_month` bigint NOT NULL DEFAULT '0' COMMENT '本月已用 token 数',
  `billing_cycle_start` date NOT NULL COMMENT '当前计费周期起始日',
  `status` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '״̬: active/suspended/arrears',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ent_code` (`ent_code`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户计费账户表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_billing_invoice` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `invoice_no` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '账单编号',
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码',
  `billing_month` varchar(7) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '账单月份（yyyy-MM）',
  `plan_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '套餐编码',
  `plan_fee` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '套餐月费',
  `token_usage_fee` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT 'token 用量费用（超额部分）',
  `total_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '账单总金额',
  `total_tokens` bigint NOT NULL DEFAULT '0' COMMENT '月度总 token 用量',
  `total_requests` int NOT NULL DEFAULT '0' COMMENT '月度总请求数',
  `status` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'pending' COMMENT '״̬: pending/paid/overdue',
  `paid_at` datetime DEFAULT NULL COMMENT '支付时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invoice_no` (`invoice_no`),
  UNIQUE KEY `uk_ent_month` (`ent_code`,`billing_month`),
  KEY `idx_billing_month` (`billing_month`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月度账单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_billing_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `plan_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '套餐编码',
  `plan_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '套餐名称',
  `plan_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '套餐类型: free/basic/pro/enterprise',
  `monthly_token_quota` bigint NOT NULL DEFAULT '0' COMMENT '每月 token 配额（0=不限）',
  `monthly_price` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '月费（元）',
  `overage_price_per_1k` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '超额部分每千 token 单价（元）',
  `max_conversations_per_day` int DEFAULT NULL COMMENT '每日最大会话数（NULL=不限）',
  `max_tokens_per_request` int DEFAULT NULL COMMENT '单次请求最大 token（NULL=不限）',
  `max_users` int DEFAULT NULL COMMENT '最大用户数（NULL=不限）',
  `features` text COLLATE utf8mb4_unicode_ci COMMENT '套餐功能描述（JSON）',
  `status` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '״̬: active/discontinued',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_code` (`plan_code`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计费套餐表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_billing_price_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `model` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型名称（如 deepseek-chat）',
  `input_price_per_1k` decimal(10,6) NOT NULL COMMENT '输入 token 单价（元/千token）',
  `output_price_per_1k` decimal(10,6) NOT NULL COMMENT '输出 token 单价（元/千token）',
  `effective_date` date NOT NULL COMMENT '生效日期',
  `expired_date` date DEFAULT NULL COMMENT '失效日期（NULL=永久有效）',
  `remark` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '备注',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_model` (`model`),
  KEY `idx_effective_date` (`effective_date`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型计价规则表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_billing_transaction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `transaction_no` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '交易流水号（UUID）',
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码',
  `type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '类型: recharge/deduction/refund/monthly_fee/gift',
  `amount` decimal(12,4) NOT NULL COMMENT '交易金额（正=入账，负=扣除）',
  `balance_after` decimal(12,2) NOT NULL COMMENT '交易后余额',
  `token_count` int DEFAULT NULL COMMENT '关联 token 数（扣费时记录）',
  `model` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关联模型（扣费时记录）',
  `conversation_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '关联会话ID（扣费时记录）',
  `description` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '交易描述',
  `operator` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作人',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transaction_no` (`transaction_no`),
  KEY `idx_ent_code` (`ent_code`),
  KEY `idx_type` (`type`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计费流水表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_chat_conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话ID（UUID）',
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码',
  `user_id` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户ID',
  `agent_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'terminal_manager' COMMENT '所属数字员工编码',
  `title` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话标题（取首条消息摘要）',
  `mode` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'auto' COMMENT '问答模式: auto/data/knowledge',
  `message_count` int NOT NULL DEFAULT '0' COMMENT '消息总数',
  `total_tokens` int NOT NULL DEFAULT '0' COMMENT '会话累计 token 数',
  `status` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '״̬: active/archived/deleted',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_id` (`conversation_id`),
  KEY `idx_ent_user` (`ent_code`,`user_id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_agent` (`agent_code`)
) ENGINE=InnoDB AUTO_INCREMENT=269 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话会话表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息ID（UUID）',
  `conversation_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属会话ID',
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码',
  `user_id` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户ID',
  `agent_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'terminal_manager' COMMENT '所属数字员工编码',
  `role` varchar(15) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色: user/assistant/system',
  `content` text COLLATE utf8mb4_unicode_ci COMMENT '消息内容',
  `mode` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '问答模式: auto/data/knowledge',
  `knowledge_base_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '知识问答实际使用的知识库ID',
  `model` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '使用的模型名称（如 deepseek-chat）',
  `prompt_tokens` int NOT NULL DEFAULT '0' COMMENT '提示词 token 数',
  `completion_tokens` int NOT NULL DEFAULT '0' COMMENT '生成回答 token 数',
  `total_tokens` int NOT NULL DEFAULT '0' COMMENT '总 token 数',
  `tool_calls` text COLLATE utf8mb4_unicode_ci COMMENT '工具调用记录（JSON 数组）',
  `tool_calls_count` int NOT NULL DEFAULT '0' COMMENT '工具调用次数',
  `chart_spec` text COLLATE utf8mb4_unicode_ci COMMENT '助手图表数据（ChartSpec JSON，最大60KiB）',
  `rag_doc_count` int NOT NULL DEFAULT '0' COMMENT 'RAG 检索文档数',
  `rag_citations` json DEFAULT NULL COMMENT 'RAG 引用快照（最大32KiB）',
  `duration_ms` int DEFAULT NULL COMMENT '响应耗时（毫秒）',
  `status` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'success' COMMENT '״̬: success/error/timeout',
  `error_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '错误信息（失败时记录）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_id` (`message_id`),
  KEY `idx_conversation_id` (`conversation_id`),
  KEY `idx_ent_user` (`ent_code`,`user_id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_agent_conv` (`agent_code`,`conversation_id`)
) ENGINE=InnoDB AUTO_INCREMENT=634 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_knowledge_base` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `knowledge_base_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '知识库ID（UUID）',
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码',
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '知识库名称',
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '知识库说明',
  `chunk_size` int NOT NULL DEFAULT '800' COMMENT '切片目标长度（字符），上传/重建索引时生效',
  `status` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '״̬: active/inactive/deleted',
  `is_default` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否默认知识库',
  `created_by` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人用户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ent_knowledge_base` (`ent_code`,`knowledge_base_id`),
  KEY `idx_ent_status` (`ent_code`,`status`),
  KEY `idx_ent_default` (`ent_code`,`is_default`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户知识库表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `document_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '稳定文档ID（UUID）',
  `knowledge_base_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属知识库ID',
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码',
  `source_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文档来源名称',
  `content_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件内容类型',
  `size_bytes` bigint DEFAULT NULL COMMENT '文件大小（字节）',
  `checksum_sha256` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文件SHA-256摘要',
  `embedding_model` varchar(150) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '成功入库使用的嵌入模型身份',
  `version` int NOT NULL COMMENT '文档版本号',
  `status` varchar(12) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '״̬: processing/ready/failed/superseded/deleted',
  `chunk_count` int NOT NULL DEFAULT '0' COMMENT '实际入库分片数',
  `error_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '安全错误摘要',
  `created_by` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人用户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ent_document_version` (`ent_code`,`document_id`,`version`),
  KEY `idx_ent_base_status` (`ent_code`,`knowledge_base_id`,`status`),
  KEY `idx_ent_base_source` (`ent_code`,`knowledge_base_id`,`source_name`),
  KEY `idx_ent_document` (`ent_code`,`document_id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文档版本表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_llm_tool` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tool_name` varchar(64) NOT NULL COMMENT '工具名（LLM function name）',
  `tool_desc` varchar(500) NOT NULL COMMENT '工具描述（LLM 据此选择）',
  `tool_type` varchar(32) NOT NULL DEFAULT 'SQL_TEMPLATE' COMMENT '工具类型：SQL_TEMPLATE 内核查库 / API_CALL 调业务接口 / KB_RETRIEVAL 知识检索 / MCP（预留）',
  `input_schema` text NOT NULL COMMENT '入参 JSON Schema',
  `sql_template` text COMMENT 'SQL 模板（仅 SQL_TEMPLATE 类型使用）',
  `config_json` text COMMENT '类型专属配置（JSON），结构由 tool_type 决定',
  `table_alias` varchar(32) DEFAULT NULL COMMENT '租户条件注入的表别名（该表须有 ent_code 列）',
  `result_limit` int NOT NULL DEFAULT '50' COMMENT '返回行数上限',
  `status` varchar(10) NOT NULL DEFAULT 'active',
  `remark` varchar(500) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `approval_required` tinyint NOT NULL DEFAULT '0' COMMENT '1=调用需人工审批放行',
  `risk_level` varchar(20) NOT NULL DEFAULT 'LOW' COMMENT '风险等级 LOW/MEDIUM/HIGH',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_name` (`tool_name`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=37 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='RAG demo 动态 AI 工具定义（指向终端库）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_mcp_server` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `server_code` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务编码（a_llm_tool.config_json 引用它）',
  `server_name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '服务名称',
  `transport` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'SSE' COMMENT '传输类型: SSE / HTTP(streamable http)',
  `base_url` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SSE: http://host:port（端点默认 /sse）；HTTP: 完整端点地址',
  `headers_json` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '自定义请求头（JSON 对象，如鉴权头）',
  `auth_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NONE' COMMENT '鉴权类型: NONE / HEADER（HEADER 时用 headers_json 带凭证）',
  `status` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '״̬: active / disabled',
  `last_sync_time` datetime DEFAULT NULL COMMENT '最近一次探测成功时间',
  `last_error` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近一次探测失败原因（500 字内）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_server_code` (`server_code`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP 服务注册表（外部 MCP server 接入）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_role_def` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_code` varchar(32) NOT NULL COMMENT '角色编码（portal 角色切换器用）',
  `role_name` varchar(64) NOT NULL COMMENT '显示名',
  `description` varchar(200) DEFAULT NULL COMMENT '角色职责说明',
  `sort_order` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务角色字典';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_tenant` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ent_code` varchar(32) NOT NULL,
  `ent_name` varchar(128) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'active',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ent_code` (`ent_code`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_tenant_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户ID（租户内唯一）',
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属租户编码',
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '登录账号',
  `display_name` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '显示名称',
  `role` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'user' COMMENT '角色: admin/user/viewer',
  `status` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '״̬: active/disabled',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ent_user` (`ent_code`,`user_id`),
  KEY `idx_ent_code` (`ent_code`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户用户表';
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
) ENGINE=InnoDB AUTO_INCREMENT=20 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='token 月度配额';
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
) ENGINE=InnoDB AUTO_INCREMENT=156 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每日 token 用量汇总表';
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
CREATE TABLE `a_tool_approval` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `approval_id` varchar(64) NOT NULL COMMENT '审批单号（对外暴露）',
  `agent_code` varchar(64) NOT NULL COMMENT '发起调用的数字人',
  `tool_name` varchar(128) NOT NULL COMMENT '被拦下的工具',
  `conversation_id` varchar(64) DEFAULT NULL COMMENT '鍙戠敓鐨勪細璇',
  `user_id` varchar(64) DEFAULT NULL COMMENT '鍙戣捣浜',
  `arguments_json` text COMMENT '待执行入参（放行后原样执行）',
  `risk_level` varchar(20) NOT NULL DEFAULT 'LOW' COMMENT '快照自 a_llm_tool',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/EXECUTED/REJECTED/FAILED',
  `result_preview` text COMMENT '执行结果摘要（放行后回填）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `decided_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_approval_id` (`approval_id`),
  KEY `idx_status` (`status`),
  KEY `idx_conv` (`conversation_id`)
) ENGINE=InnoDB AUTO_INCREMENT=27 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='宸ュ叿璋冪敤瀹℃壒鍗';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `a_tool_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `trace_id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '单次问答链路 ID',
  `conversation_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话 ID',
  `message_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '助手消息 ID',
  `ent_code` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户编码',
  `user_id` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户 ID',
  `mode` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '问答模式',
  `model` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '使用模型',
  `tool_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Tool 名称',
  `tool_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Tool 来源: code/database',
  `arguments_json` text COLLATE utf8mb4_unicode_ci COMMENT 'Tool 入参 JSON',
  `result_count` int NOT NULL DEFAULT '0' COMMENT '返回结果条数',
  `duration_ms` bigint DEFAULT NULL COMMENT '调用耗时（毫秒）',
  `status` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'success' COMMENT '״̬: success/error',
  `error_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '错误信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_trace_id` (`trace_id`),
  KEY `idx_ent_conversation` (`ent_code`,`conversation_id`),
  KEY `idx_tool_name` (`tool_name`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_ent_created_at` (`ent_code`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=386 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM Tool 命中流水表';
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
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录账号（内核鉴权用）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_ai_tool` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tool_name` varchar(64) NOT NULL COMMENT '工具名（LLM function name）',
  `tool_desc` varchar(500) NOT NULL COMMENT '工具描述（LLM 据此选择）',
  `input_schema` text NOT NULL COMMENT '入参 JSON Schema',
  `sql_template` text NOT NULL COMMENT 'SQL 模板，命名参数 :param',
  `result_limit` int NOT NULL DEFAULT '200' COMMENT '返回行数上限',
  `status` varchar(16) NOT NULL DEFAULT 'active',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_name` (`tool_name`)
) ENGINE=InnoDB AUTO_INCREMENT=19 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态 AI 工具定义';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_inbound_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `inbound_no` varchar(64) NOT NULL,
  `inbound_type` varchar(32) NOT NULL COMMENT 'purchase（采购入库）/ zero（0元入库）',
  `source_type` varchar(32) DEFAULT NULL COMMENT 'zero_new/zero_old/zero_downgrade（0元入库的三类来源）',
  `purchase_order_no` varchar(64) DEFAULT NULL COMMENT '关联采购单号',
  `supplier_code` varchar(64) DEFAULT NULL,
  `supplier_name` varchar(128) DEFAULT NULL,
  `to_location_id` bigint DEFAULT NULL COMMENT '入库目标库位',
  `total_qty` int NOT NULL DEFAULT '0',
  `status` varchar(32) NOT NULL DEFAULT 'draft' COMMENT 'draft/pending_review/approved/received',
  `operator_code` varchar(64) DEFAULT NULL,
  `operator_name` varchar(64) DEFAULT NULL,
  `ent_code` varchar(32) NOT NULL DEFAULT 'ENT001',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `receive_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_no` (`inbound_no`),
  KEY `idx_to_location` (`to_location_id`),
  KEY `idx_type` (`inbound_type`)
) ENGINE=InnoDB AUTO_INCREMENT=19 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库单';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_inspection` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `inspection_no` varchar(64) NOT NULL,
  `terminal_code` varchar(64) NOT NULL,
  `inspection_round` int NOT NULL DEFAULT '1' COMMENT '质检轮次，翻新/维修后复检递增',
  `result` varchar(16) NOT NULL COMMENT 'pass/fail',
  `disposal` varchar(32) DEFAULT NULL COMMENT 'restock/refurbish/repair/vendor/scrap（不通过时必须选）',
  `fault_desc` varchar(255) DEFAULT NULL,
  `inspector_code` varchar(64) DEFAULT NULL,
  `inspector_name` varchar(64) DEFAULT NULL,
  `inspect_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `ent_code` varchar(32) NOT NULL DEFAULT 'ENT001',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inspection_no` (`inspection_no`),
  KEY `idx_terminal` (`terminal_code`),
  KEY `idx_result` (`result`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='质检记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_location` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `location_code` varchar(64) NOT NULL COMMENT '库位编码',
  `location_name` varchar(128) NOT NULL COMMENT '库位名称',
  `parent_id` bigint DEFAULT NULL COMMENT '上级库位',
  `level` tinyint NOT NULL DEFAULT '1' COMMENT '层级 1省 2市 3县区 4营业厅',
  `location_type` varchar(32) NOT NULL COMMENT 'province/city/district/hall/staff/user/vendor',
  `local_network` varchar(32) DEFAULT NULL COMMENT '所属本地网，跨本地网调拨判定用',
  `manager_code` varchar(64) DEFAULT NULL COMMENT '库管员工号',
  `manager_name` varchar(64) DEFAULT NULL COMMENT '库管员姓名',
  `is_virtual` tinyint NOT NULL DEFAULT '0' COMMENT '是否虚拟库：装维/用户/厂商为1，不计入实物库存',
  `ent_code` varchar(32) NOT NULL DEFAULT 'ENT001' COMMENT '租户/地市编码',
  `status` varchar(16) NOT NULL DEFAULT 'active' COMMENT 'active/disabled',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_location_code` (`location_code`),
  KEY `idx_parent` (`parent_id`),
  KEY `idx_level` (`level`),
  KEY `idx_ent` (`ent_code`)
) ENGINE=InnoDB AUTO_INCREMENT=72 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库位（四级库）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_purchase_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `purchase_no` varchar(64) NOT NULL,
  `supplier_code` varchar(64) DEFAULT NULL,
  `supplier_name` varchar(128) DEFAULT NULL,
  `terminal_type` varchar(32) DEFAULT NULL,
  `model` varchar(64) DEFAULT NULL,
  `total_qty` int NOT NULL DEFAULT '0',
  `unit_price` decimal(12,2) NOT NULL DEFAULT '0.00',
  `total_amount` decimal(14,2) NOT NULL DEFAULT '0.00',
  `review_status` varchar(32) NOT NULL DEFAULT 'draft' COMMENT 'draft/reviewing/approved/rejected（需求评审）',
  `review_staff` varchar(64) DEFAULT NULL,
  `review_remark` varchar(255) DEFAULT NULL COMMENT '需求评审意见/驳回原因',
  `review_time` datetime DEFAULT NULL,
  `apply_staff` varchar(64) DEFAULT NULL COMMENT '申请人工号（下单人）',
  `order_status` varchar(32) NOT NULL DEFAULT 'created' COMMENT 'created/approved/shipped/received',
  `ship_no` varchar(64) DEFAULT NULL COMMENT '供应商发货物流单号',
  `ship_time` datetime DEFAULT NULL COMMENT '供应商发货时间',
  `received_qty` int NOT NULL DEFAULT '0' COMMENT '已到货数量',
  `ent_code` varchar(32) NOT NULL DEFAULT 'ENT001',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_purchase_no` (`purchase_no`),
  KEY `idx_review` (`review_status`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购单';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_scrap_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `scrap_no` varchar(64) NOT NULL,
  `terminal_code` varchar(64) NOT NULL,
  `scrap_reason` varchar(255) DEFAULT NULL,
  `apply_staff` varchar(64) DEFAULT NULL,
  `approve_staff` varchar(64) DEFAULT NULL COMMENT '审批人（与申请人双人确认）',
  `cbss_confirmed` tinyint NOT NULL DEFAULT '0' COMMENT 'CBSS 侧状态是否已确认',
  `status` varchar(32) NOT NULL DEFAULT 'draft' COMMENT 'draft/pending_approve/approved/finalized',
  `finalize_time` datetime DEFAULT NULL COMMENT '报竣时间（非空才算终态）',
  `ent_code` varchar(32) NOT NULL DEFAULT 'ENT001',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scrap_no` (`scrap_no`),
  UNIQUE KEY `uk_terminal` (`terminal_code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报废单';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_stock_movement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `terminal_code` varchar(64) NOT NULL COMMENT '串码（不设外键：设备删除后流水必须保留）',
  `movement_type` varchar(32) NOT NULL COMMENT 'inbound/transfer/allocate/return/recover/inspect/refurbish/repair/vendor/scrap/stocktake',
  `from_location_id` bigint DEFAULT NULL COMMENT '源库位',
  `to_location_id` bigint DEFAULT NULL COMMENT '目标库位',
  `from_status` varchar(32) DEFAULT NULL COMMENT '变更前状态',
  `to_status` varchar(32) DEFAULT NULL COMMENT '变更后状态',
  `from_owner_code` varchar(64) DEFAULT NULL COMMENT '变更前归属',
  `to_owner_code` varchar(64) DEFAULT NULL COMMENT '变更后归属',
  `biz_no` varchar(64) DEFAULT NULL COMMENT '业务单号',
  `operator_code` varchar(64) DEFAULT NULL COMMENT '操作人工号',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作人姓名',
  `ent_code` varchar(32) NOT NULL DEFAULT 'ENT001' COMMENT '租户/地市编码',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_terminal` (`terminal_code`),
  KEY `idx_time` (`create_time`),
  KEY `idx_type` (`movement_type`),
  KEY `idx_biz` (`biz_no`)
) ENGINE=InnoDB AUTO_INCREMENT=1191 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存流水（只增不改）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_terminal` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `terminal_code` varchar(64) NOT NULL COMMENT '终端串码（业务主键，全局唯一）',
  `mac_address` varchar(32) DEFAULT NULL COMMENT 'MAC 地址',
  `terminal_type` varchar(32) NOT NULL COMMENT '光猫/机顶盒/家庭网关/智能摄像头',
  `vendor` varchar(64) DEFAULT NULL COMMENT '厂商',
  `model` varchar(64) DEFAULT NULL COMMENT '型号',
  `software_version` varchar(64) DEFAULT NULL COMMENT '软件版本',
  `location_id` bigint DEFAULT NULL COMMENT '当前库位',
  `status` varchar(32) NOT NULL COMMENT '生命周期状态（见状态机）',
  `owner_type` varchar(16) NOT NULL DEFAULT 'warehouse' COMMENT 'warehouse/staff/user/vendor',
  `owner_code` varchar(64) DEFAULT NULL COMMENT '归属编码（工号/用户账号/供应商编码）',
  `owner_name` varchar(64) DEFAULT NULL COMMENT '归属名称（冗余便于查询）',
  `source_type` varchar(32) NOT NULL DEFAULT 'purchase' COMMENT 'purchase/zero_new/zero_old/zero_downgrade/recover',
  `inbound_no` varchar(64) DEFAULT NULL COMMENT '当前入库单号',
  `work_order_no` varchar(64) DEFAULT NULL COMMENT '关联工单号',
  `purchase_order_no` varchar(64) DEFAULT NULL COMMENT '采购单号（可追溯采购源头）',
  `purchase_price` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '采购单价（0元入库为0）',
  `original_value` decimal(12,2) DEFAULT NULL COMMENT '降级前资产原值（待降级场景保留）',
  `is_capitalized` tinyint NOT NULL DEFAULT '0' COMMENT '是否资本化',
  `inbound_time` datetime DEFAULT NULL COMMENT '首次入库时间',
  `allocate_time` datetime DEFAULT NULL COMMENT '下发时间',
  `install_time` datetime DEFAULT NULL COMMENT '装机时间',
  `recover_time` datetime DEFAULT NULL COMMENT '回收时间',
  `scrap_time` datetime DEFAULT NULL COMMENT '报废时间',
  `ent_code` varchar(32) NOT NULL DEFAULT 'ENT001' COMMENT '租户/地市编码',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_terminal_code` (`terminal_code`),
  KEY `idx_location_status` (`location_id`,`status`),
  KEY `idx_work_order` (`work_order_no`),
  KEY `idx_owner` (`owner_code`),
  KEY `idx_status` (`status`),
  KEY `idx_ent` (`ent_code`)
) ENGINE=InnoDB AUTO_INCREMENT=504 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='终端台账（串码级）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_transfer_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `transfer_no` varchar(64) NOT NULL,
  `from_location_id` bigint NOT NULL,
  `to_location_id` bigint NOT NULL,
  `cross_network` tinyint NOT NULL DEFAULT '0' COMMENT '是否跨本地网（需审批）',
  `total_qty` int NOT NULL DEFAULT '0',
  `apply_staff` varchar(64) DEFAULT NULL,
  `approve_staff` varchar(64) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'draft' COMMENT 'draft/pending_approve/approved/done/rejected',
  `ent_code` varchar(32) NOT NULL DEFAULT 'ENT001',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finish_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transfer_no` (`transfer_no`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调拨单';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_work_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `work_order_no` varchar(64) NOT NULL COMMENT '工单号（本系统）',
  `order_type` varchar(32) NOT NULL COMMENT 'install/move/repair/dismantle',
  `source_system` varchar(32) NOT NULL DEFAULT 'manual' COMMENT 'cbss/manual/self',
  `cbss_order_no` varchar(64) DEFAULT NULL COMMENT 'CBSS 订单号（唯一索引保证幂等）',
  `sync_status` varchar(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/synced/failed/conflict',
  `sync_time` datetime DEFAULT NULL,
  `sync_version` int NOT NULL DEFAULT '1' COMMENT '同步版本号，处理 CBSS 改单',
  `user_account` varchar(64) DEFAULT NULL COMMENT '用户账号',
  `user_name` varchar(64) DEFAULT NULL,
  `user_address` varchar(255) DEFAULT NULL,
  `staff_code` varchar(64) DEFAULT NULL COMMENT '装维工号',
  `staff_name` varchar(64) DEFAULT NULL,
  `terminal_code` varchar(64) DEFAULT NULL COMMENT '绑定串码（下发时写入）',
  `bind_time` datetime DEFAULT NULL COMMENT '串码绑定时间',
  `status` varchar(32) NOT NULL DEFAULT 'created' COMMENT 'created/dispatched/installed/cancelled',
  `ent_code` varchar(32) NOT NULL DEFAULT 'ENT001',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finish_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_work_order_no` (`work_order_no`),
  UNIQUE KEY `uk_cbss_order_no` (`cbss_order_no`),
  KEY `idx_staff` (`staff_code`),
  KEY `idx_terminal` (`terminal_code`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=218 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单';
/*!40101 SET character_set_client = @saved_cs_client */;
