-- =====================================================================
-- 69-user-auth.sql —— 用户登录鉴权（M5 登录/角色视图的第一块地基）
--
-- 设计：
--   a_user        账号表。role 三级：ADMIN（管理台）/ USER（门户）/ SERVICE（服务间调用，
--                 允许代传 userId —— 编排层代表系统调内核时要把终端用户身份透传下去）。
--   a_auth_token  不透明令牌（UUID），落库 24h 过期。不用 JWT：演示项目签发/吊销
--                 用一行 DELETE 就能讲清楚，也方便验收脚本断言。
--
-- 密码：SHA2(password, 256)。演示项目可接受；上生产再换 BCrypt。
-- 种子：admin/123456（ADMIN）、portal_user/123456（USER）、svc_orchestrator（SERVICE，仅编排层用）。
-- 目标库：terminal_db（与 68 号一致 —— 内核数据源连的是 terminal_db）
-- 执行：mysql -uroot -p123456 --default-character-set=utf8mb4 terminal_db < 69-user-auth.sql
-- =====================================================================

USE terminal_db;

CREATE TABLE IF NOT EXISTS a_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_code     VARCHAR(64)  NOT NULL COMMENT '登录名，同时是对话链路的 userId',
  user_name     VARCHAR(64)  NOT NULL COMMENT '显示名',
  password_hash CHAR(64)     NOT NULL COMMENT 'SHA2(password,256) hex',
  role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN / USER / SERVICE',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_code (user_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录账号（内核鉴权用）';

CREATE TABLE IF NOT EXISTS a_auth_token (
  token      CHAR(36)    NOT NULL PRIMARY KEY COMMENT 'UUID',
  user_code  VARCHAR(64) NOT NULL,
  role       VARCHAR(16) NOT NULL COMMENT '签发时角色快照，校验不再回查账号',
  expires_at DATETIME    NOT NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_token_user (user_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录令牌（24h 过期）';

-- 种子账号（幂等）
INSERT INTO a_user (user_code, user_name, password_hash, role)
VALUES
  ('admin',           '平台管理员', SHA2('123456', 256), 'ADMIN'),
  ('portal_user',     '门户演示用户', SHA2('123456', 256), 'USER'),
  ('svc_orchestrator','编排层服务账号', SHA2('svc-orch-2026', 256), 'SERVICE')
ON DUPLICATE KEY UPDATE role = VALUES(role), password_hash = VALUES(password_hash);
