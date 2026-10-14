package com.aismp.aismpagent.mcp;

// OA 查询工具（工单状态、审批人、权限）
import com.aismp.aismpagent.tools.QueryOrderStatusTool;
import com.aismp.aismpagent.tools.QueryApproverTool;
import com.aismp.aismpagent.tools.QueryMyPermissionTool;
// 知识库搜索工具
import com.aismp.aismpagent.tools.SearchKnowledgeBaseTool;
// 智能体即工具（整套 ReAct 智能体封装为单一 MCP 工具）
import com.aismp.aismpagent.tools.AskAgentTool;
// Spring AI 工具回调提供者
import org.springframework.ai.tool.ToolCallbackProvider;
// Spring AI 方法工具回调提供者构建器
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
// Spring 上下文注解
import org.springframework.context.annotation.Bean;
// Spring 配置注解
import org.springframework.context.annotation.Configuration;

/**
 * OA MCP Server 配置
 *
 * 将 OA 查询工具注册为 MCP 服务，让其他 AI 应用（如 Cursor、Claude Desktop、Cherry Studio）
 * 可以通过 MCP 协议直接调用这些工具。
 *
 * 注册的工具：
 * 1. queryOrderStatus — 查工单流转状态
 * 2. queryApprover    — 查审批人信息
 * 3. queryMyPermission — 查自己权限
 * 4. searchKnowledgeBase — 搜索 OA 知识库
 *
 * 使用方式（在其他 AI 应用中配置）：
 * - SSE 模式：http://localhost:8123/api/sse
 * - 或在 Cursor / Claude Desktop 的 mcp.json 中添加 SSE 连接
 */
// 注册为 Spring 配置类
@Configuration
public class OaMcpServerConfig {

    /**
     * 注册 OA 工具为 MCP 服务
     *
     * MethodToolCallbackProvider 会扫描 @Tool 注解的方法，
     * 自动转换为 MCP Tool 规范，供 MCP 客户端发现和调用。
     *
     * @param queryOrderStatusTool    工单查询工具
     * @param queryApproverTool       审批人查询工具
     * @param queryMyPermissionTool   权限查询工具
     * @param searchKnowledgeBaseTool 知识库搜索工具
     * @param askAgentTool            智能体即工具（整套 ReAct 智能体委托）
     * @return ToolCallbackProvider 工具回调提供者
     */
    @Bean
    public ToolCallbackProvider oaToolCallbackProvider(
            QueryOrderStatusTool queryOrderStatusTool,
            QueryApproverTool queryApproverTool,
            QueryMyPermissionTool queryMyPermissionTool,
            SearchKnowledgeBaseTool searchKnowledgeBaseTool,
            AskAgentTool askAgentTool
    ) {
        // 构建并返回工具回调提供者
        return MethodToolCallbackProvider.builder()
                // 注册 5 个 OA 工具（4 个单一能力 + 1 个整套智能体委托）
                .toolObjects(
                        queryOrderStatusTool,
                        queryApproverTool,
                        queryMyPermissionTool,
                        searchKnowledgeBaseTool,
                        askAgentTool
                )
                .build();
    }
}
