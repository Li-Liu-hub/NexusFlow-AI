package com.aismp.digitalhuman.tool;

import com.aismp.digitalhuman.model.ToolDefPO;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全守卫：声明式工具的三重闸门。
 *
 * <p><b>为什么要抽成独立类</b>：同一套规则要在两个时机使用 ——
 * <ul>
 *   <li><b>创建/修改工具时</b>：预先校验，模板有问题立刻拒绝（运维界面能马上给出反馈）</li>
 *   <li><b>执行工具时</b>：兜底校验，防止有人绕过接口直接改库</li>
 * </ul>
 * 两处若各写一份，迟早不一致 —— 那是安全代码最不该出现的状态。
 *
 * <p><b>三道闸门</b>：
 * <ol>
 *   <li>语句类型白名单：只允许 SELECT / WITH / SHOW / DESC / EXPLAIN</li>
 *   <li>禁止多语句：分号后不得还有内容（防堆叠注入）</li>
 *   <li>危险关键字：词边界匹配，避免误伤 create_time 这类列名</li>
 * </ol>
 * 外加 {@link #wrapWithLimit} 强制行数上限。
 *
 * <p>注意：这些都是应用层的兜底，<b>不替代数据库层的只读账户</b>。
 * 生产环境必须给本模块配一个只有 SELECT 权限的账号，两道防线互为补充。
 */
public final class SqlGuard {

    /** 匹配 SQL 里的命名参数，如 :locationName */
    public static final Pattern NAMED_PARAM = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    /** 语句必须以这些开头 */
    private static final List<String> ALLOWED_PREFIX =
            List.of("select", "with", "show", "desc", "describe", "explain");

    /** 写操作/危险关键字（按词边界匹配） */
    private static final List<String> FORBIDDEN = List.of(
            "insert", "update", "delete", "drop", "alter", "truncate", "create",
            "grant", "revoke", "replace", "rename", "call", "exec", "execute",
            "into\\s+outfile", "into\\s+dumpfile", "load_file", "benchmark", "sleep");

    private SqlGuard() {
    }

    /**
     * 校验并规范化 SQL 模板。
     *
     * @return 去掉末尾分号后的模板
     * @throws IllegalArgumentException 校验不通过（消息面向工具创建者，可直接展示）
     */
    public static String assertSafe(String sqlTemplate) {
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            throw new IllegalArgumentException("SQL 模板不能为空");
        }

        String trimmed = sqlTemplate.trim();
        // 允许作者写末尾分号（习惯写法），但不允许分号出现在中间
        String noTrailing = trimmed.endsWith(";")
                ? trimmed.substring(0, trimmed.length() - 1)
                : trimmed;
        if (noTrailing.contains(";")) {
            throw new IllegalArgumentException("SQL 模板含多条语句，已拒绝");
        }

        String lower = noTrailing.trim().toLowerCase();
        if (ALLOWED_PREFIX.stream().noneMatch(lower::startsWith)) {
            throw new IllegalArgumentException(
                    "只允许查询语句（SELECT/WITH/SHOW/DESC/EXPLAIN），当前模板不符合");
        }

        for (String kw : FORBIDDEN) {
            if (Pattern.compile("(?<![a-zA-Z0-9_])" + kw + "(?![a-zA-Z0-9_])").matcher(lower).find()) {
                throw new IllegalArgumentException("SQL 模板含危险关键字: " + kw + "，已拒绝");
            }
        }
        return noTrailing;
    }

    /**
     * 包一层子查询强制 LIMIT。
     *
     * <p>这样无论模板作者写没写 LIMIT、写了大还是小，最终结果都被 result_limit 兜住，
     * 不会因为一条模板把数据库拖垮。
     */
    public static String wrapWithLimit(String sql, Integer limit) {
        int n = (limit == null || limit <= 0) ? 100 : limit;
        return "SELECT * FROM (" + sql + ") AS _dh_guard LIMIT " + n;
    }

    /** 提取模板里出现的命名参数 —— 它们就是该工具允许的入参白名单。 */
    public static List<String> extractParamNames(String sqlTemplate) {
        List<String> names = new ArrayList<>();
        if (sqlTemplate == null) {
            return names;
        }
        Matcher m = NAMED_PARAM.matcher(sqlTemplate);
        while (m.find()) {
            String name = m.group(1);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 通用校验：与工具类型无关的部分（工具名、描述、Schema、行数上限）。
     *
     * <p>拆出来的原因：这些规则对所有工具类型都成立，而 SQL 专属规则
     * （模板安全性、参数一致性）只对 SQL_TEMPLATE 成立。
     * 混在一起会让非 SQL 类型无法通过创建校验。
     */
    public static void validateCommon(ToolDefPO def) {
        if (def.getToolName() == null || def.getToolName().isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        // 工具名会成为 LLM 的 function name，必须是合法标识符
        if (!def.getToolName().matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("工具名只能含字母、数字、下划线，且不能以数字开头");
        }
        if (def.getToolDesc() == null || def.getToolDesc().isBlank()) {
            throw new IllegalArgumentException("工具描述不能为空（LLM 靠它决定何时调用）");
        }
        if (def.getInputSchema() == null || def.getInputSchema().isBlank()) {
            throw new IllegalArgumentException("入参 JSON Schema 不能为空");
        }
        if (def.getResultLimit() != null && def.getResultLimit() > 5000) {
            throw new IllegalArgumentException("返回行数上限过大（>5000），请收敛到合理范围");
        }
    }

    /**
     * SQL_TEMPLATE 类型的专属校验：模板安全 + 参数一致性。
     */
    public static void validateSqlTemplate(ToolDefPO def) {
        assertSafe(def.getSqlTemplate());

        // 参数一致性：模板里的命名参数应在 schema 的 properties 中声明过，
        // 否则 LLM 不知道该传什么（这是声明式工具最容易犯的错）
        List<String> params = extractParamNames(def.getSqlTemplate());
        for (String p : params) {
            if ("__tenant".equals(p)) {
                continue;  // 租户占位符由运行时注入，不对外暴露
            }
            if (!def.getInputSchema().contains("\"" + p + "\"")) {
                throw new IllegalArgumentException(
                        "SQL 模板用到参数 :" + p + "，但入参 Schema 里未声明，LLM 将无法传值");
            }
        }
    }

    /**
     * 保留的便捷入口：完整校验（通用 + SQL 专属）。
     *
     * <p>等价于 {@code validateCommon(def)} + {@code validateSqlTemplate(def)}，
     * 供 SQL 类型与单测使用。
     *
     * @throws IllegalArgumentException 任一检查不通过
     */
    public static void validateForCreation(ToolDefPO def) {
        validateCommon(def);
        validateSqlTemplate(def);
    }
}
