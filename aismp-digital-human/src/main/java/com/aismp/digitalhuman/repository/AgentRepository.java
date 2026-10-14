package com.aismp.digitalhuman.repository;

import com.aismp.digitalhuman.model.AgentPO;
import com.aismp.digitalhuman.model.SkillPO;
import com.aismp.digitalhuman.model.ToolDefPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * 数字员工配置仓储（JdbcTemplate 实现）。
 *
 * <p>选 JdbcTemplate 而非 MyBatis-Plus 的原因：本模块跑在 Spring Boot 4 上，
 * MyBatis-Plus 的 Boot 4 starter 支持情况未知，而配置表 CRUD 只有十来个固定查询，
 * 用 JdbcTemplate 可以做到零额外依赖风险。
 *
 * <p><b>version 语义</b>：{@link #updateAgent} 每次自增 version，供运行时判断缓存失效。
 * 「改配置不重启」的机制就建立在这一列上。
 */
@Repository
public class AgentRepository {

    private final JdbcTemplate jdbc;

    public AgentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 数字员工
    // ------------------------------------------------------------------

    private static final RowMapper<AgentPO> AGENT_MAPPER = (rs, i) -> {
        AgentPO po = new AgentPO();
        po.setId(rs.getLong("id"));
        po.setAgentCode(rs.getString("agent_code"));
        po.setAgentName(rs.getString("agent_name"));
        po.setAvatar(rs.getString("avatar"));
        po.setDescription(rs.getString("description"));
        po.setSystemPrompt(rs.getString("system_prompt"));
        po.setModelName(rs.getString("model_name"));
        po.setTemperature(rs.getBigDecimal("temperature"));
        po.setMaxTurns(rs.getInt("max_turns"));
        long kbId = rs.getLong("knowledge_base_id");
        po.setKnowledgeBaseId(rs.wasNull() ? null : kbId);
        po.setStatus(rs.getString("status"));
        po.setVersion(rs.getInt("version"));
        po.setTenantCode(rs.getString("tenant_code"));
        po.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        po.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
        return po;
    };

    private static final String AGENT_COLUMNS =
            "id, agent_code, agent_name, avatar, description, system_prompt, model_name, "
                    + "temperature, max_turns, knowledge_base_id, status, version, tenant_code, "
                    + "create_time, update_time";

    public List<AgentPO> listAgents() {
        return jdbc.query("SELECT " + AGENT_COLUMNS + " FROM a_agent ORDER BY id", AGENT_MAPPER);
    }

    public AgentPO findByCode(String agentCode) {
        List<AgentPO> list = jdbc.query(
                "SELECT " + AGENT_COLUMNS + " FROM a_agent WHERE agent_code = ?", AGENT_MAPPER, agentCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 轻量查询：运行时热更新只需比对 version，不必拉全量配置。 */
    public Integer findVersionByCode(String agentCode) {
        List<Integer> list = jdbc.queryForList(
                "SELECT version FROM a_agent WHERE agent_code = ?", Integer.class, agentCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public Long insertAgent(AgentPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO a_agent
                      (agent_code, agent_name, avatar, description, system_prompt, model_name,
                       temperature, max_turns, knowledge_base_id, status, version, tenant_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.getAgentCode());
            ps.setString(2, po.getAgentName());
            ps.setString(3, po.getAvatar());
            ps.setString(4, po.getDescription());
            ps.setString(5, po.getSystemPrompt());
            ps.setString(6, po.getModelName());
            ps.setBigDecimal(7, po.getTemperature());
            ps.setInt(8, po.getMaxTurns() == null ? 10 : po.getMaxTurns());
            if (po.getKnowledgeBaseId() == null) {
                ps.setNull(9, java.sql.Types.BIGINT);
            } else {
                ps.setLong(9, po.getKnowledgeBaseId());
            }
            ps.setString(10, po.getStatus() == null ? "active" : po.getStatus());
            ps.setString(11, po.getTenantCode());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    /**
     * 更新数字员工配置，并自增 version。
     * 只更新人设相关字段；挂载关系变更由 {@link #bumpVersion} 单独触发。
     */
    public int updateAgent(AgentPO po) {
        return jdbc.update("""
                UPDATE a_agent
                   SET agent_name = ?, avatar = ?, description = ?, system_prompt = ?,
                       model_name = ?, temperature = ?, max_turns = ?, knowledge_base_id = ?,
                       status = ?, tenant_code = ?, version = version + 1
                 WHERE agent_code = ?
                """,
                po.getAgentName(), po.getAvatar(), po.getDescription(), po.getSystemPrompt(),
                po.getModelName(), po.getTemperature(),
                po.getMaxTurns() == null ? 10 : po.getMaxTurns(),
                po.getKnowledgeBaseId(), po.getStatus(), po.getTenantCode(), po.getAgentCode());
    }

    public int deleteAgent(String agentCode) {
        return jdbc.update("DELETE FROM a_agent WHERE agent_code = ?", agentCode);
    }

    /**
     * 配置版本 +1。
     * 挂载关系（技能/工具增删）变更后必须调用——这是运行时感知配置变化唯一的信号。
     */
    public int bumpVersion(String agentCode) {
        return jdbc.update("UPDATE a_agent SET version = version + 1 WHERE agent_code = ?", agentCode);
    }

    // ------------------------------------------------------------------
    // 技能包与挂载
    // ------------------------------------------------------------------

    private static final RowMapper<SkillPO> SKILL_MAPPER = (rs, i) -> {
        SkillPO po = new SkillPO();
        po.setId(rs.getLong("id"));
        po.setSkillCode(rs.getString("skill_code"));
        po.setSkillName(rs.getString("skill_name"));
        po.setDescription(rs.getString("description"));
        po.setPromptFragment(rs.getString("prompt_fragment"));
        po.setExamples(rs.getString("examples"));
        po.setStatus(rs.getString("status"));
        return po;
    };

    public List<SkillPO> listSkills() {
        return jdbc.query("SELECT * FROM a_agent_skill ORDER BY id", SKILL_MAPPER);
    }

    /** 按挂载顺序取出某数字员工的全部技能。 */
    public List<SkillPO> listSkillsOfAgent(Long agentId) {
        return jdbc.query("""
                SELECT s.* FROM a_agent_skill s
                  JOIN a_agent_skill_rel r ON r.skill_id = s.id
                 WHERE r.agent_id = ? AND s.status = 'active'
                 ORDER BY r.sort_order, s.id
                """, SKILL_MAPPER, agentId);
    }

    public int attachSkill(Long agentId, Long skillId, int sortOrder) {
        return jdbc.update(
                "INSERT IGNORE INTO a_agent_skill_rel (agent_id, skill_id, sort_order) VALUES (?, ?, ?)",
                agentId, skillId, sortOrder);
    }

    public int detachSkill(Long agentId, Long skillId) {
        return jdbc.update("DELETE FROM a_agent_skill_rel WHERE agent_id = ? AND skill_id = ?",
                agentId, skillId);
    }

    /** 技能包管理面 CRUD：技能 = 可复用能力描述（常驻 description + 展开 promptFragment + examples）。 */
    public SkillPO findSkillById(Long id) {
        List<SkillPO> list = jdbc.query("SELECT * FROM a_agent_skill WHERE id = ?", SKILL_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Long insertSkill(SkillPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO a_agent_skill
                      (skill_code, skill_name, description, prompt_fragment, examples, status)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.getSkillCode());
            ps.setString(2, po.getSkillName());
            ps.setString(3, po.getDescription());
            ps.setString(4, po.getPromptFragment());
            ps.setString(5, po.getExamples());
            ps.setString(6, po.getStatus() == null ? "active" : po.getStatus());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public int updateSkill(SkillPO po) {
        return jdbc.update("""
                UPDATE a_agent_skill
                   SET skill_name = ?, description = ?, prompt_fragment = ?, examples = ?, status = ?
                 WHERE id = ?
                """,
                po.getSkillName(), po.getDescription(), po.getPromptFragment(),
                po.getExamples(), po.getStatus(), po.getId());
    }

    public int deleteSkill(Long id) {
        return jdbc.update("DELETE FROM a_agent_skill WHERE id = ?", id);
    }

    /** 删除技能前先摘掉所有挂载，避免留下悬空的挂载关系。 */
    public int detachSkillEverywhere(Long skillId) {
        return jdbc.update("DELETE FROM a_agent_skill_rel WHERE skill_id = ?", skillId);
    }

    /** 用这个技能的数字员工编码（删改前置影响面提示 + 版本 bump 范围）。 */
    public List<String> findAgentsUsingSkill(Long skillId) {
        return jdbc.queryForList("""
                SELECT a.agent_code FROM a_agent a
                  JOIN a_agent_skill_rel r ON r.agent_id = a.id
                 WHERE r.skill_id = ?
                """, String.class, skillId);
    }

    // ------------------------------------------------------------------
    // 工具（复用已有 a_llm_tool）
    // ------------------------------------------------------------------

    private static final RowMapper<ToolDefPO> TOOL_MAPPER = (rs, i) -> {
        ToolDefPO po = new ToolDefPO();
        po.setId(rs.getLong("id"));
        po.setToolType(rs.getString("tool_type"));
        po.setToolName(rs.getString("tool_name"));
        po.setDisplayName(rs.getString("display_name"));
        po.setToolDesc(rs.getString("tool_desc"));
        po.setInputSchema(rs.getString("input_schema"));
        po.setSqlTemplate(rs.getString("sql_template"));
        po.setConfigJson(rs.getString("config_json"));
        po.setTableAlias(rs.getString("table_alias"));
        po.setResultLimit(rs.getInt("result_limit"));
        po.setApprovalRequired(rs.getInt("approval_required"));
        po.setRiskLevel(rs.getString("risk_level"));
        po.setStatus(rs.getString("status"));
        po.setRemark(rs.getString("remark"));
        return po;
    };

    public List<ToolDefPO> listAllTools() {
        return jdbc.query("SELECT * FROM a_llm_tool ORDER BY id", TOOL_MAPPER);
    }

    public ToolDefPO findToolById(Long id) {
        List<ToolDefPO> list = jdbc.query("SELECT * FROM a_llm_tool WHERE id = ?", TOOL_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 新建一个查库工具 —— 往 a_llm_tool 插一行，工具即存在。
     * 这是「动态创建工具」的落点：不写 Java 代码、不重启服务。
     */
    public Long insertTool(ToolDefPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO a_llm_tool
                      (tool_name, display_name, tool_desc, tool_type, input_schema, sql_template, config_json,
                       table_alias, result_limit, approval_required, risk_level, status, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.getToolName());
            ps.setString(2, po.getDisplayName());
            ps.setString(3, po.getToolDesc());
            ps.setString(4, po.getToolType() == null ? "SQL_TEMPLATE" : po.getToolType());
            ps.setString(5, po.getInputSchema());
            ps.setString(6, po.getSqlTemplate());
            ps.setString(7, po.getConfigJson());
            ps.setString(8, po.getTableAlias());
            ps.setInt(9, po.getResultLimit() == null ? 100 : po.getResultLimit());
            ps.setInt(10, Boolean.TRUE.equals(po.getApprovalRequired()) || Integer.valueOf(1).equals(po.getApprovalRequired()) ? 1 : 0);
            ps.setString(11, po.getRiskLevel() == null ? "LOW" : po.getRiskLevel());
            ps.setString(12, po.getStatus() == null ? "active" : po.getStatus());
            ps.setString(13, po.getRemark());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public int updateTool(ToolDefPO po) {
        return jdbc.update("""
                UPDATE a_llm_tool
                   SET tool_name = ?, display_name = ?, tool_desc = ?, tool_type = ?, input_schema = ?,
                       sql_template = ?, config_json = ?, table_alias = ?, result_limit = ?,
                       approval_required = ?, risk_level = ?, status = ?, remark = ?
                 WHERE id = ?
                """,
                po.getToolName(), po.getDisplayName(), po.getToolDesc(),
                po.getToolType() == null ? "SQL_TEMPLATE" : po.getToolType(),
                po.getInputSchema(), po.getSqlTemplate(), po.getConfigJson(),
                po.getTableAlias(),
                po.getResultLimit() == null ? 100 : po.getResultLimit(),
                Integer.valueOf(1).equals(po.getApprovalRequired()) ? 1 : 0,
                po.getRiskLevel() == null ? "LOW" : po.getRiskLevel(),
                po.getStatus(), po.getRemark(), po.getId());
    }

    public int deleteTool(Long id) {
        return jdbc.update("DELETE FROM a_llm_tool WHERE id = ?", id);
    }

    /** 删除工具前先摘掉所有挂载，避免留下悬空的挂载关系。 */
    public int detachToolEverywhere(Long toolId) {
        return jdbc.update("DELETE FROM a_agent_tool_rel WHERE tool_id = ?", toolId);
    }

    /** 用这个工具的数字员工有哪些（删改工具前的影响面提示）。 */
    public List<String> findAgentsUsingTool(Long toolId) {
        return jdbc.queryForList("""
                SELECT a.agent_code FROM a_agent a
                  JOIN a_agent_tool_rel r ON r.agent_id = a.id
                 WHERE r.tool_id = ?
                """, String.class, toolId);
    }

    // ------------------------------------------------------------------
    // 工具调用审计（a_tool_call_log）
    // ------------------------------------------------------------------

    /**
     * 记录一次工具调用。
     *
     * <p>这张表是「数字员工到底查了什么」的唯一凭证 —— 出问题时能回答
     * 「谁在什么时候、用哪句话、调了哪个工具、拿到几条、花了多久、成没成」。
     *
     * <p>调用方必须吞掉这里的异常：审计是旁路，不能因为它失败而让业务调用失败。
     */
    public void logToolCall(String traceId, String conversationId, String userId, String entCode,
                            String toolName, String argumentsJson, int resultCount,
                            Long durationMs, String status, String errorMessage) {
        jdbc.update("""
                INSERT INTO a_tool_call_log
                  (trace_id, conversation_id, ent_code, user_id, mode, model,
                   tool_name, tool_type, arguments_json, result_count,
                   duration_ms, status, error_message)
                VALUES (?, ?, ?, ?, 'data', NULL, ?, 'database', ?, ?, ?, ?, ?)
                """,
                traceId,
                conversationId,
                entCode == null ? "default" : entCode,
                userId,
                toolName,
                argumentsJson,
                resultCount,
                durationMs,
                status,
                errorMessage == null ? null
                        : errorMessage.substring(0, Math.min(errorMessage.length(), 500)));
    }

    /** 最近的工具调用记录，供运维界面/排障查看。 */
    public List<Map<String, Object>> recentToolCalls(int limit) {
        return jdbc.queryForList("""
                SELECT trace_id, conversation_id, tool_name, arguments_json,
                       result_count, duration_ms, status, error_message, created_at
                  FROM a_tool_call_log
                 ORDER BY id DESC
                 LIMIT ?
                """, limit);
    }

    /** 工具调用统计：每个工具被调了多少次、成功率、平均耗时。 */
    public List<Map<String, Object>> toolCallStats() {
        return jdbc.queryForList("""
                SELECT tool_name,
                       COUNT(*)                                              AS call_count,
                       SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END)      AS error_count,
                       ROUND(AVG(duration_ms))                               AS avg_duration_ms,
                       SUM(result_count)                                     AS total_rows
                  FROM a_tool_call_log
                 GROUP BY tool_name
                 ORDER BY call_count DESC
                """);
    }

    /** 按挂载顺序取出某数字员工挂载的全部可用工具。 */
    public List<ToolDefPO> listToolsOfAgent(Long agentId) {
        return jdbc.query("""
                SELECT t.* FROM a_llm_tool t
                  JOIN a_agent_tool_rel r ON r.tool_id = t.id
                 WHERE r.agent_id = ? AND t.status = 'active'
                 ORDER BY r.sort_order, t.id
                """, TOOL_MAPPER, agentId);
    }

    public int attachTool(Long agentId, Long toolId, int sortOrder) {
        return jdbc.update(
                "INSERT IGNORE INTO a_agent_tool_rel (agent_id, tool_id, sort_order) VALUES (?, ?, ?)",
                agentId, toolId, sortOrder);
    }

    public int detachTool(Long agentId, Long toolId) {
        return jdbc.update("DELETE FROM a_agent_tool_rel WHERE agent_id = ? AND tool_id = ?",
                agentId, toolId);
    }

    // ------------------------------------------------------------------
    // 角色分配（a_role_def / a_agent_role_rel）
    // ------------------------------------------------------------------

    private static final RowMapper<com.aismp.digitalhuman.model.RolePO> ROLE_MAPPER = (rs, i) -> {
        com.aismp.digitalhuman.model.RolePO po = new com.aismp.digitalhuman.model.RolePO();
        po.setId(rs.getLong("id"));
        po.setRoleCode(rs.getString("role_code"));
        po.setRoleName(rs.getString("role_name"));
        po.setDescription(rs.getString("description"));
        po.setSortOrder(rs.getInt("sort_order"));
        po.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        return po;
    };

    public List<com.aismp.digitalhuman.model.RolePO> listRoles() {
        return jdbc.query("SELECT * FROM a_role_def ORDER BY sort_order, id", ROLE_MAPPER);
    }

    public List<com.aismp.digitalhuman.model.RolePO> listRolesOfAgent(Long agentId) {
        return jdbc.query("""
                SELECT r.* FROM a_role_def r
                  JOIN a_agent_role_rel rel ON rel.role_code = r.role_code
                 WHERE rel.agent_id = ?
                 ORDER BY rel.sort_order, r.sort_order
                """, ROLE_MAPPER, agentId);
    }

    public int attachRole(Long agentId, String roleCode, int sortOrder) {
        return jdbc.update(
                "INSERT IGNORE INTO a_agent_role_rel (agent_id, role_code, sort_order) VALUES (?, ?, ?)",
                agentId, roleCode, sortOrder);
    }

    public int detachRole(Long agentId, String roleCode) {
        return jdbc.update("DELETE FROM a_agent_role_rel WHERE agent_id = ? AND role_code = ?",
                agentId, roleCode);
    }

    // ------------------------------------------------------------------
    // 角色过滤：智能路由的硬边界
    // ------------------------------------------------------------------

    /** 全部启用的数字员工（角色为空时的路由候选）。 */
    public List<AgentPO> listActiveAgents() {
        return jdbc.query(
                "SELECT " + AGENT_COLUMNS + " FROM a_agent WHERE status='active' ORDER BY id", AGENT_MAPPER);
    }

    /** 某角色可用的启用数字员工（按分配顺序，顺序即路由优先级）。 */
    public List<AgentPO> listAgentsForRole(String roleCode) {
        return jdbc.query("""
                SELECT %s FROM a_agent a
                  JOIN a_agent_role_rel rel ON rel.agent_id = a.id
                 WHERE rel.role_code = ? AND a.status = 'active'
                 ORDER BY rel.sort_order, a.id
                """.formatted(AGENT_COLUMNS_WITH_PREFIX), AGENT_MAPPER, roleCode);
    }

    /** listAgentsForRole 里列名带 a. 前缀的版本（JOIN 查询需要限定表名）。 */
    private static final String AGENT_COLUMNS_WITH_PREFIX =
            "a.id, a.agent_code, a.agent_name, a.avatar, a.description, a.system_prompt, a.model_name, "
                    + "a.temperature, a.max_turns, a.knowledge_base_id, a.status, a.version, a.tenant_code, "
                    + "a.create_time, a.update_time";

    // ------------------------------------------------------------------
    // 知识库目录与挂载（a_knowledge_base / a_agent.knowledge_base_id）
    // ------------------------------------------------------------------

    /** 启用的知识库目录，带 ready 文档数与分片总数（供配置面挑选）。 */
    public List<Map<String, Object>> listKbs() {
        return jdbc.queryForList("""
                SELECT kb.id, kb.knowledge_base_id, kb.name, kb.description, kb.is_default, kb.created_at,
                       SUM(CASE WHEN d.status = 'ready' THEN 1 ELSE 0 END)  AS doc_count,
                       COALESCE(SUM(CASE WHEN d.status = 'ready' THEN d.chunk_count ELSE 0 END), 0) AS chunk_count
                  FROM a_knowledge_base kb
                  LEFT JOIN a_knowledge_document d ON d.knowledge_base_id = kb.knowledge_base_id
                 WHERE kb.status = 'active'
                 GROUP BY kb.id, kb.knowledge_base_id, kb.name, kb.description, kb.is_default, kb.created_at
                 ORDER BY kb.is_default DESC, kb.id
                """);
    }

    /** 某知识库下的文档清单（配置面展示挂载了什么）。 */
    public List<Map<String, Object>> listKbDocs(String kbUuid) {
        return jdbc.queryForList("""
                SELECT document_id, source_name, content_type, status, chunk_count,
                       size_bytes, created_at
                  FROM a_knowledge_document
                 WHERE knowledge_base_id = ? AND status IN ('ready','processing')
                 ORDER BY id
                """, kbUuid);
    }

    /** 挂载知识库（kbId=null 表示解绑）。version 由控制器 bump。 */
    public int setAgentKnowledgeBase(String agentCode, Long kbId) {
        return jdbc.update("UPDATE a_agent SET knowledge_base_id = ? WHERE agent_code = ?",
                kbId, agentCode);
    }

    /** 按主键查知识库（装配时把挂载的 KB 注进系统提示词）。 */
    public Map<String, Object> findKbById(Long kbId) {
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT id, knowledge_base_id, name, description FROM a_knowledge_base WHERE id = ?", kbId);
        return list.isEmpty() ? null : list.get(0);
    }
}
