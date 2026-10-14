package com.aismp.aismpagent.tools;

// Spring AI 工具注解，标记这是一个可被大模型调用的工具
import org.springframework.ai.tool.annotation.Tool;
// Spring AI 工具参数注解，标注参数含义让大模型理解传什么
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
 * 查询工单流转状态工具
 * 安全措施：行级过滤（WHERE submit_user = ?），用户只能查自己的工单
 * oper_user（审批人工号）不返回，保护审批人隐私
 */
// 注册为 Spring Bean
@Component
public class QueryOrderStatusTool {

    // JdbcTemplate，Spring 自动注入
    private final JdbcTemplate jdbcTemplate;

    // 构造函数注入 JdbcTemplate
    public QueryOrderStatusTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 根据工单号查询工单当前流转状态
     * 大模型判断用户问"我的工单到哪了"时，自动调用此工具
     */
    // @Tool 注解：description 告诉大模型这个工具的用途和适用场景
    @Tool(description = "根据工单号查询工单的流转状态，包括当前环节、操作类型、操作时间。用户只能查询自己提交的工单。")
    public String queryOrderStatus(
            // @ToolParam 注解：告诉大模型这个参数是什么格式、传什么内容
            @ToolParam(description = "工单号，格式如 AQLY-ZC-ZCSHXQ-XXXXXX-XXX") String orderNum,
            // ToolContext：框架自动注入，包含 Controller 传来的 userId
            ToolContext toolContext
    ) {
        // 从 ToolContext 中获取当前用户工号（用于行级过滤）
        String userId = (String) toolContext.getContext().get("userId");

        try {
            // 安全规范：
            // 1. SELECT 白名单——只查 job_id/oper_type/oper_time/oper_remark，不查 oper_user（审批人工号）
            // 2. 行级过滤——WHERE submit_user = ?，用户只能查自己的工单
            // 3. 参数化查询——用 ? 占位符，防止 SQL 注入
            // 4. LIMIT 5——限制返回记录数，避免 context 爆炸
            List<Map<String, Object>> records = jdbcTemplate.queryForList(
                    "SELECT t.job_id, t.oper_type, t.oper_time, t.oper_remark " +
                    "FROM tm_order_track t " +
                    "JOIN tm_work_inst w ON t.order_num = w.order_num " +
                    "WHERE t.order_num = ? AND w.submit_user = ? " +
                    "ORDER BY t.oper_time DESC LIMIT 5",
                    orderNum, userId
            );

            // 查不到记录的兜底处理（可能是工单号错误，也可能是越权查询别人的工单）
            if (records.isEmpty()) {
                return "未找到工单号 " + orderNum + " 的流转记录（可能是工单号错误，或该工单不属于您）";
            }

            // 组装返回结果，每条记录格式化为：时间 | 环节 | 操作 | 备注
            StringBuilder sb = new StringBuilder();
            // 拼接工单号标题
            sb.append("工单 ").append(orderNum).append(" 的流转记录：\n");
            // 遍历每条记录
            for (Map<String, Object> r : records) {
                sb.append(String.format("- %s | %s | %s | %s\n",
                        // 操作时间
                        r.get("oper_time"),
                        // 环节ID
                        r.get("job_id"),
                        // 操作类型
                        r.get("oper_type"),
                        // 备注（可能为空）
                        r.get("oper_remark") != null ? r.get("oper_remark") : "无备注"
                ));
            }

            // 返回给大模型，大模型再组织自然语言回答用户
            return sb.toString();

        } catch (Exception e) {
            // 异常兜底：返回友好提示，不把异常堆栈暴露给 AI
            return "查询工单状态失败，请稍后重试";
        }
    }
}
