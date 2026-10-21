package com.aismp.aismpagent.tools;

// Spring AI 工具注解，声明可被 AI 调用的工具
import org.springframework.ai.tool.annotation.Tool;
// Spring AI 工具注解，描述工具参数
import org.springframework.ai.tool.annotation.ToolParam;
// Spring AI 文档检索接口
import org.springframework.ai.document.Document;
// Spring AI 向量存储抽象接口
import org.springframework.ai.vectorstore.SearchRequest;
import com.aismp.aismpagent.rag.HybridRetrievalService;
import org.springframework.ai.vectorstore.VectorStore;
// Spring 组件注解
import org.springframework.stereotype.Component;
// Jakarta 资源注入注解
import jakarta.annotation.Resource;
// Lombok 日志注解
import lombok.extern.slf4j.Slf4j;
// Java 列表
import java.util.List;
// Java 流式处理

/**
 * 知识库搜索工具（供 OA Agent 调用）
 *
 * 将 RAG 检索封装为 @Tool，让 Agent 在 ReAct 循环中自主决定何时查知识库。
 * 与 ChatClient + Advisor 方式的区别：
 * - Advisor 方式：每次请求自动检索，Agent 无法控制
 * - 工具方式：Agent 思考后决定是否需要查知识库，更灵活
 *
 * 工作流：
 * 1. 用户问"忘记密码怎么办"
 * 2. Agent think() → 决定调用 searchKnowledgeBase
 * 3. Agent act() → 执行检索，返回 FAQ 内容
 * 4. Agent think() → 基于检索结果组织回答
 * 5. Agent act() → 调用 doTerminate 结束
 */
// 注册为 Spring Bean
@Component
// 启用日志
@Slf4j
public class SearchKnowledgeBaseTool {

    // 向量存储（PGVector）：混合检索关闭或异常时的兜底路径
    @Resource
    private VectorStore vectorStore;

    // 混合检索：多查询扩展 + 关键词路 + RRF + Rerank（见 HybridRetrievalService）
    @Resource
    private HybridRetrievalService hybridRetrieval;

    /**
     * 搜索 OA 系统知识库
     *
     * @param query 用户的问题或关键词
     * @return 匹配的 FAQ 文档内容（最多 5 篇）
     */
    @Tool(description = "搜索 OA 系统知识库，查找操作指南、流程说明、常见问题等文档。" +
            "当用户询问系统操作、密码重置、工单流程、报表导出、电费管理等问题时调用此工具。")
    public String searchKnowledgeBase(
            // 工具参数：搜索关键词
            @ToolParam(description = "搜索关键词或完整问题，例如：忘记密码怎么办、工单审批流程") String query
    ) {
        log.info("Agent 知识库检索：query='{}'", query);

        List<String> passages;
        try {
            // 优先走混合检索（多查询 → 双路 → RRF → rerank），任一层失败会自行降级
            passages = hybridRetrieval.search(query);
        } catch (RuntimeException e) {
            // 兜底：混合检索整体异常时回到单路向量检索，保证问答不中断
            log.warn("混合检索异常，退回单路向量检索：{}", e.getMessage());
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query).topK(5).similarityThreshold(0.5).build());
            passages = docs == null ? List.of() : docs.stream().map(Document::getText).toList();
        }

        if (passages.isEmpty()) {
            log.info("Agent 知识库检索：无匹配文档");
            return "未找到相关知识库文档。";
        }
        log.info("Agent 知识库检索：返回 {} 段", passages.size());
        return String.join("\n\n---\n\n", passages);
    }
}
