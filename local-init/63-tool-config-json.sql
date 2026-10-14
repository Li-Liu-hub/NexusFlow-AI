-- ============================================================================
-- 数字员工内核 · 架构优化：工具通用配置字段
-- ============================================================================
-- 目标库：terminal_db
--
-- 【为什么需要】
-- sql_template 只对 SQL 模板型工具有意义。一旦引入非 SQL 类型
-- （KB_RETRIEVAL 知识检索 / HTTP 接口 / MCP Server），就需要一个存放
-- 「调用配置」的地方 —— 否则每加一种类型就要加一列。
--
-- 【设计】
-- config_json 存放类型专属配置，各 ToolBuilder 自解释其结构：
--   SQL_TEMPLATE  —— 不使用（沿用 sql_template 列，保持既有工具不变）
--   KB_RETRIEVAL  —— {"endpoint":"/oa/chat/sync","timeoutSeconds":120}
--   HTTP          —— {"method":"GET","url":"...","headers":{...}}
--   MCP           —— {"server":"...","tool":"..."}
--
-- 用 JSON 而非固定列：类型专属字段千差万别，加列会让表结构随类型数量膨胀。
-- ============================================================================
USE terminal_db;

DROP PROCEDURE IF EXISTS add_config_json_if_absent;
DELIMITER //
CREATE PROCEDURE add_config_json_if_absent()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'a_llm_tool'
           AND column_name = 'config_json'
    ) THEN
        ALTER TABLE a_llm_tool
            ADD COLUMN config_json text NULL
            COMMENT '类型专属配置（JSON），结构由 tool_type 决定'
            AFTER sql_template;
    END IF;
END //
DELIMITER ;
CALL add_config_json_if_absent();
DROP PROCEDURE IF EXISTS add_config_json_if_absent;

SELECT column_name, column_type, column_comment
  FROM information_schema.columns
 WHERE table_schema = DATABASE() AND table_name = 'a_llm_tool'
 ORDER BY ordinal_position;
