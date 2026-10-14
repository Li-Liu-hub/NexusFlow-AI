package com.aismp.aismpagent.tools;

// Spring AI 工具注解
import org.springframework.ai.tool.annotation.Tool;
// Spring AI 工具参数注解
import org.springframework.ai.tool.annotation.ToolParam;
// Spring AI 工具上下文类
import org.springframework.ai.chat.model.ToolContext;
// Spring JDBC
import org.springframework.jdbc.core.JdbcTemplate;
// Spring 组件注解
import org.springframework.stereotype.Component;

// Java 集合类
import java.util.List;
import java.util.Map;

/**
 * 查询审批人工具
 * 安全措施：按 areaCode 过滤（用户只能查同地市的审批人）+ 姓名脱敏（董博 → 董某某）
 * 返回结果不含工号、手机号等敏感字段
 */
// 注册为 Spring Bean
@Component
public class QueryApproverTool {

    // JdbcTemplate，Spring 自动注入
    private final JdbcTemplate jdbcTemplate;

    // 构造函数注入 JdbcTemplate
    public QueryApproverTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询某个流程某个环节的审批人
     * 大模型判断用户问"这个环节审批人是谁"时，自动调用此工具
     */
    // @Tool 注解：description 告诉大模型这个工具的用途
    @Tool(description = "查询某个流程的某个环节有哪些审批人，用于回答'审批人是谁''谁来审核'等审批人相关问题")
    public String queryApprover(
            // @ToolParam：告诉大模型 flowId 参数的含义
            @ToolParam(description = "流程ID，如 FLOW_ZC（资源审批流程）、FLOW_DF（电费核查流程）") String flowId,
            // @ToolParam：告诉大模型 jobId 参数的含义
            @ToolParam(description = "环节ID，如 JOB_REVIEW（审批环节）、JOB_CHECK（核查环节）") String jobId,
            // ToolContext：框架自动注入，包含 Controller 传来的 areaCode
            ToolContext toolContext
    ) {
        // 从 ToolContext 中获取当前用户的地市编码（用于数据权限过滤）
        String areaCode = (String) toolContext.getContext().get("areaCode");

        try {
            // 安全规范：
            // 1. SELECT 白名单——只查 name 和 area_name，不查 user_id/phone/id_card
            // 2. 行级过滤——WHERE area_code = ?，只查同地市的审批人
            // 3. 参数化查询——用 ? 占位符，防止 SQL 注入
            // 4. DISTINCT 去重——避免同一审批人重复出现
            List<Map<String, Object>> approvers = jdbcTemplate.queryForList(
                    "SELECT DISTINCT u.name, a.area_name " +
                    "FROM base_user u " +
                    "LEFT JOIN tm_user_role ur ON u.user_id = ur.user_id " +
                    "LEFT JOIN tm_role_job rj ON ur.role_id = rj.role_id " +
                    "LEFT JOIN tm_area a ON u.area_code = a.area_code " +
                    "WHERE rj.flow_id = ? AND rj.job_id = ? AND u.area_code = ? AND u.del_flag = 0",
                    flowId, jobId, areaCode
            );

            // 查不到审批人的兜底处理
            if (approvers.isEmpty()) {
                return "该环节暂无配置审批人，请联系管理员";
            }

            // 遍历审批人列表，拼接待脱敏的字符串
            StringBuilder sb = new StringBuilder();
            // 拼接标题
            sb.append("当前环节审批人：");
            // 遍历每个审批人
            for (int i = 0; i < approvers.size(); i++) {
                if (i > 0) {
                    // 多个审批人用顿号分隔
                    sb.append("、");
                }
                // 获取审批人姓名
                String name = (String) approvers.get(i).get("name");
                // 获取地市名称
                String areaName = approvers.get(i).get("area_name") != null
                        ? (String) approvers.get(i).get("area_name") : "";
                // 脱敏后拼接：董博 → 董某某（武汉市）
                sb.append(desensitizeName(name));
                // 拼接地市信息
                if (!areaName.isEmpty()) {
                    sb.append("（").append(areaName).append("）");
                }
            }

            // 返回脱敏后的结果给大模型
            return sb.toString();

        } catch (Exception e) {
            // 异常兜底：返回友好提示，不把异常堆栈暴露给 AI
            return "查询审批人信息失败，请稍后重试";
        }
    }

    /**
     * 姓名脱敏：保留姓 + 某某
     * 董博 → 董某某
     * 王大陆 → 王某某
     */
    private String desensitizeName(String name) {
        // 空值或空字符串的兜底处理
        if (name == null || name.isEmpty()) {
            return "***";
        }
        // 保留第一个字 + 某某
        return name.charAt(0) + "某某";
    }
}
