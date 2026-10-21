package com.aismp.aismpagent.rag;

// Jakarta 资源注入注解
import jakarta.annotation.Resource;
// Lombok 日志注解
import lombok.extern.slf4j.Slf4j;
// Spring AI 模块化 RAG 管道 Advisor
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
// Spring AI 空上下文兜底增强器
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
// Spring AI 基于向量存储的文档检索器
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
// Spring AI 向量存储抽象接口
import org.springframework.ai.vectorstore.VectorStore;
// Spring 组件注解
import org.springframework.stereotype.Component;

/**
 * OA 智能客服 RAG Advisor 工厂
 * 把模块五验证有效的策略组装成 RetrievalAugmentationAdvisor 管道
 */
// 注册为 Spring Bean
@Component
// 启用日志
@Slf4j
public class OaRagAdvisorFactory {

    // 向量存储（PGVector），注入 Spring AI 自动配置的 VectorStore Bean
    @Resource
    private VectorStore vectorStore;

    // 自定义查询转换器（重写 + 分类，一次 LLM 调用）
    @Resource
    private OaRagQueryTransformer oaRagQueryTransformer;

    // 混合检索版文档检索器（替代单路向量检索）
    @Resource
    private HybridDocumentRetriever hybridDocumentRetriever;

    /**
     * 创建 OA 智能客服的 RAG Advisor
     * 管道流程：查询重写+分类 → 向量检索（带元数据过滤）→ 空上下文兜底
     */
    public RetrievalAugmentationAdvisor create() {
        // 【诊断日志】证明 create() 被调用（initChatClient 阶段）
        log.info(">>> [诊断] OaRagAdvisorFactory.create() 开始组装 RAG 管道");
        // 文档检索器：混合检索（多查询扩展 → 向量 + 关键词 → RRF → Rerank）
        // 为什么不再直接用 VectorStoreDocumentRetriever：单路向量对「型号编码/专有名词」
        // 这类字面匹配召回不稳，且只有一个查询视角。混合检索在 HybridRetrievalService 里，
        // 任一层失败都会自行降级，最终仍能返回结果。
        // 分类过滤（category in [...]）由查询转换器注入 Query.context，由 HybridDocumentRetriever 透传。
        DocumentRetriever documentRetriever = hybridDocumentRetriever;

        // 空上下文增强器：检索为空时的兜底处理
        // allowEmptyContext=false → 检索为空时指示模型不要回答（防止瞎编）
        ContextualQueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder()
                // 不允许空上下文（超纲题礼貌拒绝）
                .allowEmptyContext(false)
                .build();

        // 组装 RAG 管道
        return RetrievalAugmentationAdvisor.builder()
                // 查询重写 + 分类（一次 LLM 调用，支持多轮对话历史）
                .queryTransformers(oaRagQueryTransformer)
                // 向量检索（带元数据过滤）
                .documentRetriever(documentRetriever)
                // 空上下文兜底
                .queryAugmenter(queryAugmenter)
                // 执行顺序设为最大值，确保 RAG 在 SelectiveMemoryAdvisor（order=0）之后执行
                // 否则 RAG 会先增强用户消息（注入检索到的上下文），导致增强后的文本被保存到 ChatMemory
                .order(Integer.MAX_VALUE)
                .build();
    }
}
