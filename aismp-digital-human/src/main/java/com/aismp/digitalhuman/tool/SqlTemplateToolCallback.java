package com.aismp.digitalhuman.tool;

import com.aismp.digitalhuman.model.ToolDefPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把一条 {@code a_llm_tool} 记录变成 Spring AI 可调用的工具。
 *
 * <p><b>这是「创建查库的工具」的核心</b>：往 a_llm_tool 插一行数据即得到一个工具，
 * 全程不需要写 Java 代码，也不需要重启（挂到数字员工上时 version 自增，运行时自动重建）。
 *
 * <p>执行链路：
 * <pre>
 *   LLM 决定调用 → 解析入参 → SQL 安全闸门 → 命名参数绑定 → 包一层强制 LIMIT → 执行 → 审计 → JSON 返回
 * </pre>
 *
 * <p>安全规则统一在 {@link SqlGuard} 里，创建工具时与执行时共用同一套 —— 见该类注释。
 *
 * <p><b>为什么用 SQL 模板里的 :param 当参数白名单</b>：
 * 比解析 input_schema 更准确 —— 模板里出现的命名参数就是唯一允许的入参，
 * LLM 多传的参数直接被忽略；且所有值走预编译绑定，不存在字符串拼接注入。
 */
public class SqlTemplateToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SqlTemplateToolCallback.class);

    private final ToolDefPO def;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String tenantCode;
    private final ToolCallAuditor auditor;

    public SqlTemplateToolCallback(ToolDefPO def,
                                   NamedParameterJdbcTemplate jdbc,
                                   ObjectMapper json,
                                   String tenantCode,
                                   ToolCallAuditor auditor) {
        this.def = def;
        this.jdbc = jdbc;
        this.json = json;
        this.tenantCode = tenantCode;
        this.auditor = auditor;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(def.getToolName())
                .description(def.getToolDesc())
                .inputSchema(def.getInputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> params = bindParameters(toolInput);
            String sql = SqlGuard.assertSafe(def.getSqlTemplate());
            sql = SqlGuard.wrapWithLimit(sql, def.getResultLimit());

            List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource(params));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", rows.size());
            if (rows.isEmpty()) {
                result.put("message", "没有查到匹配的数据");
            } else {
                result.put("rows", rows);
            }
            return json.writeValueAsString(result);

        } catch (Exception e) {
            // 工具失败不抛堆栈给 LLM，返回可读错误，让模型自行决定换工具或如实告知用户
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            try {
                return json.writeValueAsString(err);
            } catch (Exception ignored) {
                return "{\"error\":\"工具执行失败\"}";
            }
        }
    }
    // 说明：调用审计不在这里做 —— 它由 AuditedToolCallback 装饰器统一处理，
    // 这样新增工具类型自动获得审计，不必每个实现各写一遍。

    /**
     * 用 SQL 模板里出现的 :param 作为白名单，只放行这些入参。
     * LLM 多传参数时直接忽略，不报错（模型常会多传）。
     */
    private Map<String, Object> bindParameters(String toolInput) {
        Map<String, Object> params = new HashMap<>();

        // 租户隔离：模板里写了 :__tenant 才注入，避免危险的 SQL 改写
        if (def.getSqlTemplate() != null && def.getSqlTemplate().contains(":__tenant")) {
            params.put("__tenant", tenantCode == null ? "" : tenantCode);
        }

        if (toolInput == null || toolInput.isBlank()) {
            return params;
        }

        List<String> allowed = SqlGuard.extractParamNames(def.getSqlTemplate());
        if (allowed.isEmpty()) {
            return params;
        }

        Map<?, ?> raw = json.readValue(toolInput, Map.class);
        for (String name : allowed) {
            if ("__tenant".equals(name)) {
                continue;
            }
            Object value = raw.get(name);
            if (value != null) {
                params.put(name, value);
            }
        }
        return params;
    }
}
