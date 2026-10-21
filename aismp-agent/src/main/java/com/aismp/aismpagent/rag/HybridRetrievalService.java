package com.aismp.aismpagent.rag;

import com.aismp.aismpagent.config.KbHybridProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：多查询扩展 → 向量 + 关键词双路 → RRF 融合 → Rerank 精排。
 *
 * <p><b>为什么不是简单地把三件事叠起来</b>：每一层都有明确的"补什么短板"
 * <ul>
 *   <li>多查询扩展：一个问题只查一次，容易漏掉换了说法的表述（"补货"vs"补仓"）</li>
 *   <li>关键词路：型号编码、专有名词这类精确字面匹配，向量模型经常召回不准</li>
 *   <li>RRF：两路分数量纲不同，只比名次才公平（见 {@link RrfFuser}）</li>
 *   <li>Rerank：向量检索是双塔粗排，交叉编码才能分辨"哪条规定适用于这个场景"</li>
 * </ul>
 *
 * <p><b>每一层都可降级</b>：扩展失败 → 只用原问；关键词通道异常 → 只用向量；
 * rerank 超时 → 沿用 RRF 顺序。任何一层挂了都还能返回答案，只是质量不同。
 * 这条纪律来自原版图表路线的一次事故：LLM 选型抖动把链路拖到 301 秒。
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private static final double SIMILARITY_THRESHOLD = 0.5;

    private final VectorStore vectorStore;
    private final ObjectProvider<OaMultiQueryExpander> expanderProvider;
    private final KeywordSearcher keywordSearcher;
    private final RerankClient rerankClient;
    private final KbHybridProperties properties;

    public HybridRetrievalService(VectorStore vectorStore,
                                  ObjectProvider<OaMultiQueryExpander> expanderProvider,
                                  KeywordSearcher keywordSearcher,
                                  RerankClient rerankClient,
                                  KbHybridProperties properties) {
        this.vectorStore = vectorStore;
        this.expanderProvider = expanderProvider;
        this.keywordSearcher = keywordSearcher;
        this.rerankClient = rerankClient;
        this.properties = properties;
    }

    /**
     * 检索入口。
     *
     * @param query 用户问题
     * @return 命中的片段文本（已按相关度排序）；无命中返回空列表
     */
    public List<String> search(String query) {
        return search(query, null);
    }

    /**
     * @param filterExpression Spring AI 的 metadata 过滤表达式（如 {@code category in ['operation']}）。
     *                         非空时向量路与关键词路都按它收口 —— 否则关键词通道会绕过分区，
     *                         把不该出现的分类也捞进来。
     */
    public List<String> search(String query, String filterExpression) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!properties.isEnabled()) {
            return singleVectorSearch(query, filterExpression);
        }

        long start = System.currentTimeMillis();
        List<String> variants = expand(query);
        Map<String, String> textByKey = new LinkedHashMap<>();   // key → 原文
        List<List<String>> rankedLists = new ArrayList<>();
        List<Double> weights = new ArrayList<>();

        // ① 向量路：每个子查询各召回一批
        double perVariantWeight = properties.getVectorWeight() / Math.max(1, variants.size());
        for (String variant : variants) {
            List<Document> docs = vectorSearch(variant, properties.getCandidates(), filterExpression);
            List<String> keys = new ArrayList<>();
            for (Document doc : docs) {
                String key = keyOf(doc);
                textByKey.putIfAbsent(key, doc.getText());
                keys.add(key);
            }
            if (!keys.isEmpty()) {
                rankedLists.add(keys);
                weights.add(perVariantWeight);
            }
        }

        // ② 关键词路：字面命中（型号编码、专有名词靠它兜）
        List<KeywordSearcher.Hit> keywordHits = keywordSearcher.search(
                query, properties.getCandidates(), categoriesOf(filterExpression));
        if (!keywordHits.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (KeywordSearcher.Hit hit : keywordHits) {
                textByKey.putIfAbsent(hit.id(), hit.content());
                keys.add(hit.id());
            }
            rankedLists.add(keys);
            weights.add(properties.getKeywordWeight());
        }

        if (rankedLists.isEmpty()) {
            log.info("混合检索：两路均无命中（{}ms）", System.currentTimeMillis() - start);
            return List.of();
        }

        // ③ RRF 融合
        List<RrfFuser.Ranked> fused = RrfFuser.fuse(rankedLists, weights, properties.getRrfK());
        List<String> candidates = new ArrayList<>();
        for (RrfFuser.Ranked r : fused) {
            String text = textByKey.get(r.key());
            if (text != null && !text.isBlank()) {
                candidates.add(text);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // ④ Rerank（失败沿用 RRF 顺序）
        List<String> reranked = rerankClient.rerank(query, candidates, properties.getTopK());
        List<String> finalList = reranked != null ? reranked : candidates;
        if (finalList.size() > properties.getTopK()) {
            finalList = finalList.subList(0, properties.getTopK());
        }
        log.info("混合检索：{} 路 / 子查询 {} 个 / 候选 {} 条 / 返回 {} 条 / rerank={} / 耗时 {}ms",
                rankedLists.size(), variants.size(), candidates.size(), finalList.size(),
                reranked != null ? "on" : "off(或失败)", System.currentTimeMillis() - start);
        return finalList;
    }

    /** 多查询扩展：失败或不开启都退回原问 */
    private List<String> expand(String query) {
        if (properties.getMultiQuery() <= 1) {
            return List.of(query);
        }
        OaMultiQueryExpander expander = expanderProvider.getIfAvailable();
        if (expander == null) {
            return List.of(query);
        }
        try {
            List<String> expanded = expander.expand(query);
            if (expanded == null || expanded.isEmpty()) {
                return List.of(query);
            }
            List<String> limited = expanded.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .limit(properties.getMultiQuery())
                    .toList();
            return limited.isEmpty() ? List.of(query) : limited;
        } catch (RuntimeException e) {
            log.warn("多查询扩展失败，退回原问：{}", e.getMessage());
            return List.of(query);
        }
    }

    private List<Document> vectorSearch(String query, int topK, String filterExpression) {
        try {
            SearchRequest.Builder sb = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(SIMILARITY_THRESHOLD);
            if (filterExpression != null && !filterExpression.isBlank()) {
                sb.filterExpression(filterExpression);
            }
            List<Document> docs = vectorStore.similaritySearch(sb.build());
            return docs == null ? List.of() : docs;
        } catch (RuntimeException e) {
            log.warn("向量检索失败（子查询「{}」）：{}", query, e.getMessage());
            return List.of();
        }
    }

    /** 关掉混合检索时的原路径：单路向量、topK 5、阈值 0.5 */
    private List<String> singleVectorSearch(String query, String filterExpression) {
        return vectorSearch(query, properties.getTopK(), filterExpression).stream()
                .map(Document::getText).toList();
    }

    /**
     * 从 {@code category in ['a','b']} 里抠出分类值，供关键词通道做同口径过滤。
     * 解析不出来就返回空 —— 关键词路降级为不参与（宁可不加，也不能漏过滤）。
     */
    static List<String> categoriesOf(String filterExpression) {
        if (filterExpression == null || filterExpression.isBlank()) {
            return List.of();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("category\\s+in\\s+\\[(.*?)]", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(filterExpression);
        if (!m.find()) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (String part : m.group(1).split(",")) {
            String v = part.trim().replaceAll("^['\"]|['\"]$", "");
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * 跨路去重的 key：优先用 metadata 里的「文档 + 切片序号」，
     * 拿不到就退回文本哈希（同一片段在不同子查询里应得到同一个 key）。
     */
    private static String keyOf(Document doc) {
        Object docId = doc.getMetadata().get("document_id");
        Object chunk = doc.getMetadata().get("chunk_index");
        if (docId != null && chunk != null) {
            return docId + ":" + chunk;
        }
        return Integer.toHexString(doc.getText() == null ? 0 : doc.getText().hashCode());
    }
}
