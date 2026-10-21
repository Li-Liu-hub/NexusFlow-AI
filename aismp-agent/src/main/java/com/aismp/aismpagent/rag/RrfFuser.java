package com.aismp.aismpagent.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）。
 *
 * <p>为什么用它而不是「分数加权平均」：向量相似度（0~1）与关键词命中（次数）**量纲完全不同**，
 * 调权重就是在两个不可比的尺度上凑参数。RRF 只用**名次**：score = Σ weight / (k + rank)，
 * 名次天然可比，也不需要归一化 —— 这是它在混合检索里被广泛采用的原因。
 *
 * <p>k 取 60 是原论文的经验值：k 越大，头部名次的优势越平缓，多路结果越"民主"。
 *
 * <p>纯函数、无 IO，可直接单测。
 */
public final class RrfFuser {

    public static final int DEFAULT_K = 60;

    private RrfFuser() {
    }

    /** 一路结果的排序条目：key 用来跨路去重（同一片段只保留一份） */
    public record Ranked(String key, double score) {
    }

    /**
     * 融合多路结果。
     *
     * @param lists   每路一个 key 列表，**按名次升序**（第 0 个最相关）
     * @param weights 每路权重，长度需与 lists 一致；传 null 视为 1.0
     * @param k       平滑常数，常用 60
     * @return 按融合分降序的 key 列表（已去重）
     */
    public static List<Ranked> fuse(List<List<String>> lists, List<Double> weights, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (lists == null || lists.isEmpty()) {
            return List.of();
        }
        int kk = k <= 0 ? DEFAULT_K : k;
        for (int i = 0; i < lists.size(); i++) {
            List<String> list = lists.get(i);
            if (list == null || list.isEmpty()) {
                continue;
            }
            double w = weights != null && i < weights.size() && weights.get(i) != null ? weights.get(i) : 1.0;
            for (int rank = 0; rank < list.size(); rank++) {
                String key = list.get(rank);
                if (key == null || key.isBlank()) {
                    continue;
                }
                // 同一路里重复出现的 key 只按最好名次计分（避免某个 key 把自己刷上去）
                if (rank > 0 && list.subList(0, rank).contains(key)) {
                    continue;
                }
                scores.merge(key, w / (kk + rank + 1), Double::sum);
            }
        }
        List<Ranked> out = new ArrayList<>();
        scores.forEach((key, score) -> out.add(new Ranked(key, score)));
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }
}
