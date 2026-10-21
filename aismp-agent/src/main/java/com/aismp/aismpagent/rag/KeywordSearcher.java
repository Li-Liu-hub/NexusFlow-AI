package com.aismp.aismpagent.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词通道（混合检索的第二路）。
 *
 * <p><b>为什么不用数据库的全文索引</b>：项目里的语料是中文，而 PostgreSQL 的
 * {@code to_tsvector('simple', ...)} 对中文基本按整句切词，命中率接近于零；
 * 装 zhparser 又要改镜像和扩展，代价远大于收益。
 *
 * <p><b>这里的做法</b>：把候选片段按知识库捞一批（上限可配，默认 500），
 * 在 Java 里用**查询词的 2-gram 覆盖率**打分：
 * <pre>
 *   score = Σ(该 2-gram 在片段中出现次数) / (1 + log(片段长度))
 * </pre>
 * 2-gram 对中文是够用的近似切词（"库龄分布" → 库龄/龄分/分布），
 * 除以长度是对长片段的惩罚（否则长文档天然命中更多）。
 *
 * <p>这是"关键词命中"的**诚实版**，不是严格的 BM25（没有 idf、没有词干化）。
 * 它的价值在于补向量检索的短板：型号编码、专有名词这类**精确字面匹配**，
 * 向量模型经常召回不准，字面命中反而稳定。
 */
@Component
public class KeywordSearcher {

    private static final int MAX_ROWS = 500;

    private final JdbcTemplate jdbcTemplate;

    public KeywordSearcher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 一条关键词命中：id 用于去重，content 用于拼上下文 */
    public record Hit(String id, String content, double score) {
    }

    /**
     * @param query 用户问题（或扩展出的子查询）
     * @param topN  返回条数
     */
    public List<Hit> search(String query, int topN) {
        return search(query, topN, null);
    }

    /**
     * @param categories 非空时只看这些分类（对应 metadata.category），
     *                   保证关键词通道不会绕过分区过滤
     */
    public List<Hit> search(String query, int topN, java.util.List<String> categories) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows;
        try {
            if (categories != null && !categories.isEmpty()) {
                String in = categories.stream().map(c -> "'" + c.replace("'", "''") + "'")
                        .reduce((a, b) -> a + "," + b).orElse("''");
                rows = jdbcTemplate.queryForList(
                        "SELECT id::text AS id, content FROM vector_store WHERE content IS NOT NULL "
                                + "AND metadata->>'category' IN (" + in + ") LIMIT " + MAX_ROWS);
            } else {
                rows = jdbcTemplate.queryForList(
                        "SELECT id::text AS id, content FROM vector_store WHERE content IS NOT NULL LIMIT " + MAX_ROWS);
            }
        } catch (RuntimeException e) {
            // 关键词通道是增强项：查不到就返回空，由调用方降级为纯向量检索
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            String content = String.valueOf(row.get("content"));
            double score = score(query, content);
            if (score > 0) {
                hits.add(new Hit(id, content, score));
            }
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.size() > topN ? hits.subList(0, topN) : hits;
    }

    /**
     * 2-gram 覆盖率打分。查询里的 2-gram 逐个在片段里数出现次数，按片段长度衰减。
     * 纯函数，便于单测。
     */
    public static double score(String query, String content) {
        if (query == null || content == null || query.length() < 2) {
            return 0;
        }
        String q = normalize(query);
        String c = normalize(content);
        if (q.length() < 2) {
            return 0;
        }
        Map<String, Integer> grams = new LinkedHashMap<>();
        for (int i = 0; i + 2 <= q.length(); i++) {
            grams.merge(q.substring(i, i + 2), 1, Integer::sum);
        }
        double raw = 0;
        for (String gram : grams.keySet()) {
            int count = count(c, gram);
            if (count > 0) {
                // 每个命中的 2-gram 记 1 分，重复出现按对数递减（避免单个词反复出现刷分）
                raw += 1 + Math.log(count);
            }
        }
        if (raw <= 0) {
            return 0;
        }
        return raw / (1 + Math.log(1 + c.length() / 100.0));
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[\\s\\p{Punct}，。、；：？！（）【】《》]", "");
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
