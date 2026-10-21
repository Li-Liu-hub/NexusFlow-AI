package com.aismp.aismpagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把混合检索包装成 Spring AI 的 {@link DocumentRetriever}。
 *
 * <p>为什么需要这一层：内核的问答链路走的是 {@code RetrievalAugmentationAdvisor}
 * （查询重写 → 文档检索 → 上下文增强），检索发生在一个 {@code DocumentRetriever} 里。
 * 只把混合检索接到工具类上没用 —— 那条路径只有 MCP 才会走，真实问答根本不经过它。
 * 这一步是把同一套混合检索挂到**真正在跑的那条链**上。
 *
 * <p>分类过滤（{@code category in [...]}）由查询转换器写进 {@link Query#context()}，
 * 这里原样透传，向量路与关键词路都按它收口。
 */
@Component
public class HybridDocumentRetriever implements DocumentRetriever {

    /** 查询转换器把过滤表达式存在这个 key 下（Spring AI 的约定） */
    private static final String FILTER_EXPRESSION = "filter_expression";

    private final HybridRetrievalService hybridRetrieval;

    public HybridDocumentRetriever(HybridRetrievalService hybridRetrieval) {
        this.hybridRetrieval = hybridRetrieval;
    }

    @Override
    public List<Document> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        String filter = query.context() == null ? null
                : String.valueOf(query.context().getOrDefault(FILTER_EXPRESSION, ""));
        List<String> passages = hybridRetrieval.search(query.text(),
                filter == null || "null".equals(filter) ? null : filter);
        List<Document> documents = new ArrayList<>(passages.size());
        for (String passage : passages) {
            if (passage != null && !passage.isBlank()) {
                documents.add(new Document(passage));
            }
        }
        return documents;
    }
}
