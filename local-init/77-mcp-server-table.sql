-- ============================================================================
-- 77: MCP 服务注册表（批 E，2026-10-06）
-- ============================================================================
-- 内核「接入外部 MCP server」的管理面：一条记录 = 一个可探测、可调用的工具来源。
-- 工具定义（a_llm_tool，tool_type='MCP'）通过 config_json 引用它：
--   {"serverCode":"aismp-agent-mcp","toolName":"queryOrderStatus"}
--
-- 落 terminal_db（与内核其余配置表同库，内核数据源即指向这里）。
-- 可重复执行（IF NOT EXISTS）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `a_mcp_server` (
  `id`             bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `server_code`    varchar(64)  NOT NULL COMMENT '服务编码（a_llm_tool.config_json 引用它）',
  `server_name`    varchar(100) NOT NULL COMMENT '服务名称',
  `transport`      varchar(10)  NOT NULL DEFAULT 'SSE' COMMENT '传输类型: SSE / HTTP(streamable http)',
  `base_url`       varchar(500) NOT NULL COMMENT 'SSE: http://host:port（端点默认 /sse）；HTTP: 完整端点地址',
  `headers_json`   varchar(1000)         DEFAULT NULL COMMENT '自定义请求头（JSON 对象，如鉴权头）',
  `auth_type`      varchar(20)  NOT NULL DEFAULT 'NONE' COMMENT '鉴权类型: NONE / HEADER（HEADER 时用 headers_json 带凭证）',
  `status`         varchar(10)  NOT NULL DEFAULT 'active' COMMENT '状态: active / disabled',
  `last_sync_time` datetime              DEFAULT NULL COMMENT '最近一次探测成功时间',
  `last_error`     varchar(500)          DEFAULT NULL COMMENT '最近一次探测失败原因（500 字内）',
  `created_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mcp_server_code` (`server_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='MCP 服务注册表（外部 MCP server 接入）';