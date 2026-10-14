-- ============================================================================
-- 数字员工内核 · 架构优化：工具类型抽象
-- ============================================================================
-- 目标库：terminal_db
--
-- 【为什么需要】
-- 原设计里「工具」只有一种实现（SQL 模板），且 AgentAssembler 直接 new
-- SqlTemplateToolCallback(...) —— 这意味着：每加一种工具类型（HTTP 接口、
-- Spring Bean 方法、MCP Server、知识库检索）都要回头改装配器。
-- 装配器本应只认配置，不该认识任何具体工具实现。
--
-- 【怎么做】
-- 给 a_llm_tool 加 tool_type 列，装配时由 ToolCallbackFactory 按类型分发。
-- 默认值 'SQL_TEMPLATE' 保证现有 19 条工具的行为一字不变（向后兼容）。
-- ============================================================================
USE terminal_db;

-- 幂等添加（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用存储过程判断）
DROP PROCEDURE IF EXISTS add_tool_type_if_absent;
DELIMITER //
CREATE PROCEDURE add_tool_type_if_absent()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = 'a_llm_tool'
           AND column_name = 'tool_type'
    ) THEN
        ALTER TABLE a_llm_tool
            ADD COLUMN tool_type varchar(32) NOT NULL DEFAULT 'SQL_TEMPLATE'
            COMMENT '工具类型：SQL_TEMPLATE / KB_RETRIEVAL / HTTP / MCP'
            AFTER tool_desc;
    END IF;
END //
DELIMITER ;
CALL add_tool_type_if_absent();
DROP PROCEDURE IF EXISTS add_tool_type_if_absent;

-- 回填：现有工具全部是 SQL 模板型（新列默认值已保证，这里显式声明意图）
UPDATE a_llm_tool SET tool_type = 'SQL_TEMPLATE' WHERE tool_type IS NULL OR tool_type = '';

-- 【必须】放开 sql_template 的非空约束。
-- 原表结构隐含假设「所有工具都是 SQL 类型」，导致 KB_RETRIEVAL 等非 SQL 类型
-- 插入时报 "Column 'sql_template' cannot be null"。
-- 抽象若不贯穿到表结构，就等于没做 —— 现有 20 条都有值，放开不影响任何存量数据。
ALTER TABLE a_llm_tool
    MODIFY COLUMN sql_template text NULL COMMENT 'SQL 模板（仅 SQL_TEMPLATE 类型使用）';

-- 校验
SELECT tool_type, COUNT(*) AS 工具数 FROM a_llm_tool GROUP BY tool_type;
