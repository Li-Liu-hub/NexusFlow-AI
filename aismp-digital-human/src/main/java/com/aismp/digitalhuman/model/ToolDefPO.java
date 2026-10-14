package com.aismp.digitalhuman.model;

/**
 * 声明式工具定义（→ a_llm_tool，复用已有表，不是本模块新建）。
 *
 * <p>这就是「创建查库的工具」的载体：一条记录即一个可被 LLM 调用的工具，
 * 不需要写任何 Java 代码。P3 的 {@code SqlTemplateToolCallback} 会把它变成
 * Spring AI 的 {@code ToolCallback}。
 *
 * <ul>
 *   <li>{@code sqlTemplate} 用命名参数（{@code :param}），与 inputSchema 的字段一一对应</li>
 *   <li>{@code tableAlias} 非空时，执行前自动注入租户条件（该表须有 ent_code 列）</li>
 *   <li>{@code resultLimit} 强制追加 LIMIT，防止全表扫描拖垮数据库</li>
 * </ul>
 */
public class ToolDefPO {

    /** 工具类型取值：SQL_TEMPLATE / KB_RETRIEVAL / HTTP / MCP（见 DigitalToolBuilder 实现） */
    private String toolType;
    private Long id;
    private String toolName;
    /** 中文显示名（管理台展示用；LLM 的 function name 仍是 toolName，不受影响） */
    private String displayName;
    private String toolDesc;
    private String inputSchema;
    private String sqlTemplate;
    /** 类型专属配置（JSON），结构由 tool_type 决定；SQL_TEMPLATE 不使用 */
    private String configJson;
    private String tableAlias;
    private Integer resultLimit;
    /** 1 = 模型调用该工具时先拦截，生成审批单，前端放行后才真正执行 */
    private Integer approvalRequired;
    /** 风险等级 LOW / MEDIUM / HIGH（审批卡片上展示用） */
    private String riskLevel;
    private String status;
    private String remark;

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public String getToolType() {
        return toolType;
    }

    public void setToolType(String toolType) {
        this.toolType = toolType;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getToolDesc() {
        return toolDesc;
    }

    public void setToolDesc(String toolDesc) {
        this.toolDesc = toolDesc;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(String inputSchema) {
        this.inputSchema = inputSchema;
    }

    public String getSqlTemplate() {
        return sqlTemplate;
    }

    public void setSqlTemplate(String sqlTemplate) {
        this.sqlTemplate = sqlTemplate;
    }

    public String getTableAlias() {
        return tableAlias;
    }

    public void setTableAlias(String tableAlias) {
        this.tableAlias = tableAlias;
    }

    public Integer getResultLimit() {
        return resultLimit;
    }

    public void setResultLimit(Integer resultLimit) {
        this.resultLimit = resultLimit;
    }

    public Integer getApprovalRequired() {
        return approvalRequired;
    }

    public void setApprovalRequired(Integer approvalRequired) {
        this.approvalRequired = approvalRequired;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
