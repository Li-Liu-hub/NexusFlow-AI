package com.aismp.digitalhuman.tool;

import com.aismp.digitalhuman.model.ToolDefPO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 安全闸门的单元测试。
 *
 * <p><b>这组测试的意义</b>：SqlGuard 是整个数字员工内核唯一允许 LLM 间接触达数据库的地方。
 * 它一旦失效，风险不是"答错"而是"删库"。所以这里覆盖的重点不是happy path，
 * 而是**各种绕过尝试**：写操作、堆叠注入、危险函数、以及"看起来像危险关键字"的合法列名。
 *
 * <p>纯函数测试，不需要 Spring 上下文。
 */
class SqlGuardTest {

    // ------------------------------------------------------------------
    // 闸门一：语句类型白名单
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("闸门一 · 只允许查询语句")
    class StatementTypeGate {

        @ParameterizedTest
        @DisplayName("合法查询语句应放行")
        @ValueSource(strings = {
                "SELECT * FROM t_terminal",
                "select * from t_terminal",
                "  SELECT 1  ",
                "WITH x AS (SELECT 1) SELECT * FROM x",
                "SHOW TABLES",
                "DESC t_terminal",
                "EXPLAIN SELECT 1",
        })
        void allowsReadOnlyStatements(String sql) {
            assertEquals(sql.trim(), SqlGuard.assertSafe(sql).trim());
        }

        @ParameterizedTest
        @DisplayName("写操作与 DDL 必须被拒绝")
        @ValueSource(strings = {
                "DELETE FROM t_terminal",
                "UPDATE t_terminal SET status = 'x'",
                "INSERT INTO t_terminal VALUES (1)",
                "DROP TABLE t_terminal",
                "TRUNCATE TABLE t_terminal",
                "ALTER TABLE t_terminal ADD COLUMN x INT",
                "CREATE TABLE evil (id INT)",
                "GRANT ALL ON *.* TO 'x'@'%'",
        })
        void rejectsWriteAndDdl(String sql) {
            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> SqlGuard.assertSafe(sql));
            assertTrue(ex.getMessage().contains("只允许查询语句"),
                    "异常信息应说明原因，实际: " + ex.getMessage());
        }

        @Test
        @DisplayName("空模板与 null 必须被拒绝")
        void rejectsBlank() {
            assertThrows(IllegalArgumentException.class, () -> SqlGuard.assertSafe(null));
            assertThrows(IllegalArgumentException.class, () -> SqlGuard.assertSafe("   "));
        }
    }

    // ------------------------------------------------------------------
    // 闸门二：禁止多语句
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("闸门二 · 禁止多语句（堆叠注入）")
    class MultiStatementGate {

        @Test
        @DisplayName("分号后还有内容必须被拒绝")
        void rejectsStackedStatements() {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlGuard.assertSafe("SELECT 1; DROP TABLE t_terminal"));
            assertThrows(IllegalArgumentException.class,
                    () -> SqlGuard.assertSafe("SELECT * FROM t_terminal; DELETE FROM t_terminal"));
        }

        @Test
        @DisplayName("末尾单个分号是作者习惯，应被容忍并去掉")
        void toleratesTrailingSemicolon() {
            assertEquals("SELECT 1", SqlGuard.assertSafe("SELECT 1;"));
            assertEquals("SELECT 1", SqlGuard.assertSafe("SELECT 1;  "));
        }
    }

    // ------------------------------------------------------------------
    // 闸门三：危险关键字（重点是不误伤）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("闸门三 · 危险关键字词边界匹配")
    class ForbiddenKeywordGate {

        @ParameterizedTest
        @DisplayName("危险函数必须被拒绝")
        @ValueSource(strings = {
                "SELECT SLEEP(10)",
                "SELECT * FROM t_terminal WHERE 1=1 AND sleep(5)",
                "SELECT LOAD_FILE('/etc/passwd')",
                "SELECT * FROM t_terminal INTO OUTFILE '/tmp/x'",
                "SELECT BENCHMARK(1000000, MD5('x'))",
        })
        void rejectsDangerousFunctions(String sql) {
            assertThrows(IllegalArgumentException.class, () -> SqlGuard.assertSafe(sql));
        }

        /**
         * 这是最容易被写错的地方：如果实现只做 sql.contains("create")，
         * 那么任何查询 create_time 的正常 SQL 都会被误杀。
         * 词边界匹配（(?<![a-zA-Z0-9_])create(?![a-zA-Z0-9_])）就是为了避免这个。
         */
        @ParameterizedTest
        @DisplayName("含危险关键字前缀的合法列名/表名不得误伤")
        @ValueSource(strings = {
                "SELECT create_time FROM t_terminal",
                "SELECT t.create_time, t.update_time FROM t_terminal t",
                "SELECT * FROM t_terminal ORDER BY update_time DESC",
                "SELECT COUNT(*) AS created_count FROM t_terminal",
                "SELECT deleted_flag FROM t_terminal",          // deleted 不等于 delete
                "SELECT * FROM t_terminal WHERE status = 'DROP_PENDING'",
        })
        void doesNotMisjudgeColumnNames(String sql) {
            assertEquals(sql, SqlGuard.assertSafe(sql),
                    "这不含真正的危险语句，不该被拒: " + sql);
        }
    }

    // ------------------------------------------------------------------
    // 参数提取（决定工具入参白名单）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("参数提取")
    class ParamExtraction {

        @Test
        @DisplayName("应提取出去重且保序的命名参数")
        void extractsParams() {
            List<String> params = SqlGuard.extractParamNames(
                    "SELECT * FROM t WHERE a = :alpha AND b = :beta AND c = :alpha");
            assertEquals(List.of("alpha", "beta"), params);
        }

        @Test
        @DisplayName("无参数模板返回空列表")
        void noParams() {
            assertTrue(SqlGuard.extractParamNames("SELECT 1").isEmpty());
            assertTrue(SqlGuard.extractParamNames(null).isEmpty());
        }

        @Test
        @DisplayName("能从函数参数位置正确抽取（如 CAST 内）")
        void extractsFromFunctionArgs() {
            List<String> params = SqlGuard.extractParamNames("SELECT CAST(:x AS CHAR)");
            assertEquals(List.of("x"), params);
        }
    }

    // ------------------------------------------------------------------
    // 行数上限
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("行数闸门")
    class LimitGate {

        @Test
        @DisplayName("包一层子查询，无论原模板写没写 LIMIT 都被兜住")
        void wrapsWithLimit() {
            String wrapped = SqlGuard.wrapWithLimit("SELECT * FROM t_terminal", 50);
            assertTrue(wrapped.startsWith("SELECT * FROM ("));
            assertTrue(wrapped.endsWith("LIMIT 50"));

            // 原模板带大 LIMIT 也压不过外层
            String wrapped2 = SqlGuard.wrapWithLimit("SELECT * FROM t LIMIT 99999", 50);
            assertTrue(wrapped2.endsWith("LIMIT 50"), "外层 LIMIT 应生效: " + wrapped2);
        }

        @Test
        @DisplayName("未配置或非法上限时退化为默认值，而不是不限行")
        void fallsBackToDefault() {
            assertTrue(SqlGuard.wrapWithLimit("SELECT 1", null).endsWith("LIMIT 100"));
            assertTrue(SqlGuard.wrapWithLimit("SELECT 1", 0).endsWith("LIMIT 100"));
            assertTrue(SqlGuard.wrapWithLimit("SELECT 1", -5).endsWith("LIMIT 100"));
        }
    }

    // ------------------------------------------------------------------
    // 创建工具时的完整预检
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("创建预检 validateForCreation")
    class CreationValidation {

        private ToolDefPO valid() {
            ToolDefPO def = new ToolDefPO();
            def.setToolName("countByModel");
            def.setToolDesc("按型号统计数量");
            def.setInputSchema("{\"type\":\"object\",\"properties\":{\"model\":{}}}");
            def.setSqlTemplate("SELECT COUNT(*) FROM t_terminal WHERE model = :model");
            def.setResultLimit(50);
            return def;
        }

        @Test
        @DisplayName("合法定义应通过")
        void acceptsValid() {
            SqlGuard.validateForCreation(valid());
        }

        @Test
        @DisplayName("工具名必须是合法标识符（它会成为 LLM 的 function name）")
        void rejectsIllegalToolName() {
            ToolDefPO def = valid();
            def.setToolName("123bad");
            assertThrows(IllegalArgumentException.class, () -> SqlGuard.validateForCreation(def));

            ToolDefPO def2 = valid();
            def2.setToolName("has-dash");
            assertThrows(IllegalArgumentException.class, () -> SqlGuard.validateForCreation(def2));

            ToolDefPO def3 = valid();
            def3.setToolName(null);
            assertThrows(IllegalArgumentException.class, () -> SqlGuard.validateForCreation(def3));
        }

        @Test
        @DisplayName("描述不能为空（LLM 靠它决定何时调用）")
        void rejectsBlankDescription() {
            ToolDefPO def = valid();
            def.setToolDesc("  ");
            assertThrows(IllegalArgumentException.class, () -> SqlGuard.validateForCreation(def));
        }

        /**
         * 声明式工具最容易犯的错：SQL 里用了 :model，但 schema 没声明 model 参数，
         * 模型根本不知道要传什么，工具永久查不出数据。
         */
        @Test
        @DisplayName("模板用到的参数必须在 Schema 里声明")
        void rejectsUndeclaredParam() {
            ToolDefPO def = valid();
            def.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SqlGuard.validateForCreation(def));
            assertTrue(ex.getMessage().contains("model"), "应指出是哪个参数: " + ex.getMessage());
        }

        @Test
        @DisplayName("__tenant 是运行时注入的占位符，不要求在 Schema 里声明")
        void allowsTenantPlaceholder() {
            ToolDefPO def = valid();
            def.setSqlTemplate("SELECT * FROM t_terminal WHERE ent_code = :__tenant");
            def.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
            SqlGuard.validateForCreation(def);
        }

        @Test
        @DisplayName("返回行数上限过大应被拒绝（防全表拖库）")
        void rejectsHugeLimit() {
            ToolDefPO def = valid();
            def.setResultLimit(999999);
            assertThrows(IllegalArgumentException.class, () -> SqlGuard.validateForCreation(def));
        }

        @Test
        @DisplayName("创建预检同样拦截危险语句（不能绕过执行期闸门）")
        void creationAlsoChecksSql() {
            ToolDefPO def = valid();
            def.setSqlTemplate("DELETE FROM t_terminal");
            assertThrows(IllegalArgumentException.class, () -> SqlGuard.validateForCreation(def));
        }

        @Test
        @DisplayName("schema 非空但不是 JSON 也应能通过静态检查（格式由前端保证）")
        void toleratesSchemaFormat() {
            ToolDefPO def = valid();
            def.setInputSchema("{\"model\":\"x\"}");  // 只要包含参数名即可
            SqlGuard.validateForCreation(def);
            assertFalse(def.getInputSchema().isBlank());
        }
    }
}
