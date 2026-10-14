package com.aismp.digitalhuman.controller;

import com.aismp.digitalhuman.model.ToolDefPO;
import com.aismp.digitalhuman.repository.AgentRepository;
import com.aismp.digitalhuman.tool.SqlGuard;
import com.aismp.digitalhuman.tool.ToolCallbackFactory;
import com.aismp.digitalhuman.tool.builder.SqlTemplateToolBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具管理接口 —— 「动态创建查库工具」的 HTTP 入口。
 *
 * <p>往 a_llm_tool 插一行即得到一个新工具；把它挂到数字员工上（POST /admin/agent/{code}/tool/{id}）
 * 数字员工立刻就能用，全程不写 Java 代码、不重启服务。
 *
 * <p><b>创建时做两层校验</b>：
 * <ol>
 *   <li>静态检查（{@link SqlGuard#validateForCreation}）：SQL 是否只读、参数是否在 schema 里声明过</li>
 *   <li>试运行（dryRun=true）：真的拿空参数跑一次，能立刻发现列名写错、表不存在这类问题 ——
 *       这是"写 SQL 工具"最常见的坑，等到线上被模型调用才发现就太晚了</li>
 * </ol>
 */
@RestController
@RequestMapping("/admin/tool")
public class ToolAdminController {

    private final AgentRepository repository;
    private final NamedParameterJdbcTemplate jdbc;
    private final ToolCallbackFactory toolCallbackFactory;

    public ToolAdminController(AgentRepository repository,
                               NamedParameterJdbcTemplate jdbc,
                               ToolCallbackFactory toolCallbackFactory) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.toolCallbackFactory = toolCallbackFactory;
    }

    @GetMapping("/list")
    public List<ToolDefPO> list() {
        return repository.listAllTools();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ToolDefPO> get(@PathVariable Long id) {
        ToolDefPO po = repository.findToolById(id);
        return po == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(po);
    }

    /**
     * 静态校验 + 可选试运行。运维界面在用户点「保存」前调它，能立刻给出可读的报错。
     *
     * @param dryRun 为 true 时真的执行一次 SQL（空参数），验证表名/列名/语法
     */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody ToolDefPO def,
                                        @RequestParam(defaultValue = "false") boolean dryRun) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 校验也走工厂：通用规则 + 该类型的专属规则。
        // 首版这里写死调用 SQL 校验，导致 KB_RETRIEVAL 等非 SQL 类型无法创建。
        try {
            toolCallbackFactory.validate(def);
        } catch (IllegalArgumentException e) {
            result.put("valid", false);
            result.put("stage", "静态检查");
            result.put("message", e.getMessage());
            return result;
        }
        result.put("valid", true);
        result.put("stage", "静态检查通过");

        // 试运行只对 SQL 类型有意义（它的执行结果取决于库里的数据；
        // 知识检索 / HTTP 等类型不查库，试运行无从谈起）
        if (!isSqlTemplate(def)) {
            result.put("note", "非 SQL 模板类型，跳过试运行（该类型的可用性由运行期调用验证）");
            return result;
        }
        if (!dryRun) {
            return result;
        }

        // 试运行：验证 SQL 语法与表/列是否存在
        try {
            String sql = SqlGuard.wrapWithLimit(
                    SqlGuard.assertSafe(def.getSqlTemplate()), def.getResultLimit());

            // 必须为每个命名参数绑定哑值。试运行要验的是「SQL 能不能跑」，
            // 不是「参数传得对不对」—— 后者由运行时决定。
            // 若用空参数，任何带参模板都会报 "No value supplied for the SQL parameter"，
            // 那是假阳性，会把正确的工具误判为不可用。（首版就踩了这个坑）
            MapSqlParameterSource dryParams = new MapSqlParameterSource();
            for (String p : SqlGuard.extractParamNames(def.getSqlTemplate())) {
                dryParams.addValue(p, "");
            }

            List<Map<String, Object>> rows = jdbc.queryForList(sql, dryParams);
            result.put("stage", "试运行通过");
            result.put("sampleRowCount", rows.size());
            result.put("sampleRows", rows.subList(0, Math.min(2, rows.size())));
            if (rows.isEmpty()) {
                result.put("note", "试运行返回 0 行 —— 参数为哑值所致，SQL 本身已可执行");
            }
        } catch (Exception e) {
            result.put("valid", false);
            result.put("stage", "试运行失败");
            result.put("message", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /** 判断是否为 SQL 模板类型（试运行只对它有意义）。 */
    private boolean isSqlTemplate(ToolDefPO def) {
        String type = def.getToolType();
        return type == null || type.isBlank()
                || SqlTemplateToolBuilder.TYPE.equalsIgnoreCase(type);
    }

    /**
     * 创建工具。默认带试运行 —— 宁可在这里失败，也不要等模型调用时才炸。
     */
    @PostMapping
    public Map<String, Object> create(@RequestBody ToolDefPO def,
                                      @RequestParam(defaultValue = "true") boolean dryRun) {
        Map<String, Object> check = validate(def, dryRun);
        if (!Boolean.TRUE.equals(check.get("valid"))) {
            return Map.of("created", false, "check", check);
        }
        try {
            Long id = repository.insertTool(def);
            return Map.of("created", true, "id", id, "toolName", def.getToolName(), "check", check);
        } catch (Exception e) {
            // 最典型的是工具名唯一键冲突
            return Map.of("created", false, "message", e.getMessage(), "check", check);
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody ToolDefPO def) {
        def.setId(id);
        Map<String, Object> check = validate(def, true);
        if (!Boolean.TRUE.equals(check.get("valid"))) {
            return Map.of("updated", 0, "check", check);
        }
        int rows = repository.updateTool(def);
        // 工具内容变了，挂载它的数字员工都要重建
        List<String> affected = repository.findAgentsUsingTool(id);
        affected.forEach(repository::bumpVersion);
        return Map.of("updated", rows, "affectedAgents", affected, "check", check);
    }

    /**
     * 删除工具。会先摘掉所有挂载关系，并 bump 受影响数字员工的 version，
     * 避免出现「配置指向一个不存在的工具」这种悬空状态。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        List<String> affected = repository.findAgentsUsingTool(id);
        int detached = repository.detachToolEverywhere(id);
        int deleted = repository.deleteTool(id);
        affected.forEach(repository::bumpVersion);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("detachedRelations", detached);
        result.put("affectedAgents", affected);
        return result;
    }

    /** 工具模板：给运维界面一个「新建」时的起点，也当使用示例。 */
    @GetMapping("/template")
    public Map<String, Object> template() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("toolName", "countStockByModel");
        t.put("toolDesc", "按终端型号统计库存数量，用于回答「某型号有多少台」");
        t.put("inputSchema", "{\"type\":\"object\",\"properties\":{\"model\":{\"type\":\"string\",\"description\":\"终端型号，如 F657\"}},\"required\":[\"model\"]}");
        t.put("sqlTemplate", "SELECT t.model AS 型号, COUNT(*) AS 数量 FROM t_terminal t WHERE t.model LIKE CONCAT('%', :model, '%') GROUP BY t.model");
        t.put("tableAlias", null);
        t.put("resultLimit", 50);
        t.put("status", "active");
        t.put("remark", "按型号统计");
        return t;
    }

    /** 供运维界面展示可选的表与列（写 SQL 模板时的参考）。 */
    @GetMapping("/db-schema")
    public List<Map<String, Object>> dbSchema() {
        List<Map<String, Object>> tables = jdbc.getJdbcTemplate().queryForList("""
                SELECT table_name AS tableName, table_comment AS tableComment
                  FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                   AND table_name LIKE 't\\_%'
                 ORDER BY table_name
                """);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            String name = String.valueOf(table.get("tableName"));
            List<Map<String, Object>> columns = jdbc.getJdbcTemplate().queryForList("""
                    SELECT column_name AS columnName, column_type AS columnType, column_comment AS columnComment
                      FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = ?
                     ORDER BY ordinal_position
                    """, name);
            Map<String, Object> entry = new LinkedHashMap<>(table);
            entry.put("columns", columns);
            result.add(entry);
        }
        return result;
    }
}
