package com.aismp.aismpagent.tools;

// Spring AI 工具注解，标记这是一个可被大模型调用的工具
import org.springframework.ai.tool.annotation.Tool;
// Spring AI 工具上下文，用于接收 userId 等身份信息
import org.springframework.ai.tool.annotation.ToolParam;
// Spring AI 工具上下文类
import org.springframework.ai.chat.model.ToolContext;
// Spring JDBC，用于执行参数化查询
import org.springframework.jdbc.core.JdbcTemplate;
// Spring 组件注解
import org.springframework.stereotype.Component;

// Java 集合类
import java.util.List;
import java.util.Map;

/**
 * 查询当前用户权限工具
 * 天然安全：只查当前登录用户自己的数据（WHERE user_id = ?）
 * 不需要额外脱敏，因为用户查自己的信息不存在隐私问题
 */
// 注册为 Spring Bean
@Component
public class QueryMyPermissionTool {

    // JdbcTemplate，Spring 自动注入，用于执行参数化 SQL
    private final JdbcTemplate jdbcTemplate;

    // 构造函数注入 JdbcTemplate
    public QueryMyPermissionTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询当前用户的角色、地市信息
     * 大模型判断用户问"我有什么权限"时，自动调用此工具
     */
    // @Tool 注解：标记为可被大模型调用的工具，description 告诉大模型什么时候该用它
    @Tool(description = "查询当前登录用户的角色和地市信息，用于回答'我有什么权限''我能做什么'等权限相关问题")
    public String queryMyPermission(
            // ToolContext：框架自动注入，包含 Controller 传来的 userId 等身份信息
            ToolContext toolContext
    ) {
        // 从 ToolContext 中获取当前用户工号
        String userId = (String) toolContext.getContext().get("userId");

        // 查询用户基本信息（SELECT 白名单：只查 name 和 area_name，不查 phone/id_card）
        // 参数化查询（? 占位符），防止 SQL 注入
        List<Map<String, Object>> userList = jdbcTemplate.queryForList(
                "SELECT u.name, a.area_name " +
                "FROM base_user u " +
                "LEFT JOIN tm_area a ON u.area_code = a.area_code " +
                "WHERE u.user_id = ? AND u.del_flag = 0",
                userId
        );

        // 用户不存在时的兜底处理
        if (userList.isEmpty()) {
            return "未找到您的用户信息，请联系管理员";
        }

        // 取出用户信息
        Map<String, Object> user = userList.get(0);
        // 获取姓名
        String name = (String) user.get("name");
        // 获取地市名称
        String areaName = user.get("area_name") != null ? (String) user.get("area_name") : "未知";

        // 查询用户角色列表
        List<Map<String, Object>> roleList = jdbcTemplate.queryForList(
                "SELECT r.role_name " +
                "FROM tm_user_role ur " +
                "JOIN tm_role r ON ur.role_id = r.role_id " +
                "WHERE ur.user_id = ?",
                userId
        );

        // 拼接角色名称
        StringBuilder roles = new StringBuilder();
        for (int i = 0; i < roleList.size(); i++) {
            if (i > 0) {
                // 多个角色用顿号分隔
                roles.append("、");
            }
            roles.append(roleList.get(i).get("role_name"));
        }

        // 如果没有角色
        if (roles.length() == 0) {
            roles.append("无");
        }

        // 组装返回结果（返回给大模型，大模型再组织自然语言回答用户）
        return String.format("当前用户：%s，地市：%s，角色：%s", name, areaName, roles);
    }
}
