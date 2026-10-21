package com.aismp.aismpagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 混合检索配置（多查询扩展 + RRF + Rerank）。
 *
 * <p>全部可关：任一项出问题都能单独关掉回到「单路向量检索」，不影响问答可用性。
 */
@Component
@ConfigurationProperties(prefix = "kb.hybrid")
public class KbHybridProperties {

    /** 总开关：关掉则完全走原来的单路向量检索 */
    private boolean enabled = true;
    /** 多查询扩展：1 问变 N 问（含原问），1 表示不扩展 */
    private int multiQuery = 3;
    /** RRF 平滑常数，原论文经验值 60 */
    private int rrfK = 60;
    /** 每路召回条数（融合前的候选池大小） */
    private int candidates = 20;
    /** 最终返回给模型的片段数 */
    private int topK = 5;
    /** 向量路权重（RRF 里的权重，不是相似度权重） */
    private double vectorWeight = 1.0;
    /** 关键词路权重 */
    private double keywordWeight = 1.0;

    private final Rerank rerank = new Rerank();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMultiQuery() {
        return multiQuery;
    }

    public void setMultiQuery(int multiQuery) {
        this.multiQuery = multiQuery;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public int getCandidates() {
        return candidates;
    }

    public void setCandidates(int candidates) {
        this.candidates = candidates;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getVectorWeight() {
        return vectorWeight;
    }

    public void setVectorWeight(double vectorWeight) {
        this.vectorWeight = vectorWeight;
    }

    public double getKeywordWeight() {
        return keywordWeight;
    }

    public void setKeywordWeight(double keywordWeight) {
        this.keywordWeight = keywordWeight;
    }

    public Rerank getRerank() {
        return rerank;
    }

    /** 交叉编码重排（硅基流动 bge-reranker）。 */
    public static class Rerank {
        private boolean enabled = true;
        private String baseUrl = "https://api.siliconflow.cn";
        private String model = "BAAI/bge-reranker-v2-m3";
        private String apiKey = "";
        private int timeoutSeconds = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
