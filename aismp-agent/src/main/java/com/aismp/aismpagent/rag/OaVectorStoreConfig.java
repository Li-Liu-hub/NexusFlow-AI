package com.aismp.aismpagent.rag;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.Assert;

/**
 * 向量存储 / 嵌入模型配置
 * 嵌入模型：硅基流动的 BAAI/bge-m3（1024 维），OpenAI 兼容接口
 *
 * 演进过程：
 *   1. 最初走智谱 embedding-3（需要 ZHIPU_EMBEDDING_API_KEY）；
 *   2. 本机部署期改为本机 llama.cpp 托管的 bge-m3（127.0.0.1:8099，离线可跑）；
 *   3. 现在改为硅基流动托管（应用部署到服务器后，本机嵌入实例不再可达）。
 *
 * 为什么必须显式声明 1024 维：pgvector 的向量列是 vector(1024)，维度不一致会直接插入失败。
 * bge-m3 原生输出 1024 维，与 application.yml 的 dimensions 配置一致。
 */
@Configuration
public class OaVectorStoreConfig {

    /**
     * 硅基流动的 bge-m3 嵌入模型（OpenAI 兼容端点）
     *
     * Key 从环境变量 SILICONFLOW_API_KEY 读取：
     *   - 绝不硬编码（一旦提交进仓库等于泄露）；
     *   - 缺失时用 Assert 直接启动失败 —— 明确失败优于"灌库时才报 401"的静默失败。
     */
    @Bean
    @Primary
    public EmbeddingModel oaEmbeddingModel() {
        // 硅基流动 OpenAI 兼容地址；留base-url 可覆盖，便于切到其他兼容端点
        String baseUrl = System.getenv().getOrDefault("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn");
        String apiKey = System.getenv().getOrDefault("SILICONFLOW_API_KEY", "");
        Assert.hasText(apiKey, "缺少环境变量 SILICONFLOW_API_KEY（硅基流动嵌入模型 Key），应用无法启动");

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .embeddingsPath("/v1/embeddings")
                .build();

        return new OpenAiEmbeddingModel(openAiApi,
                MetadataMode.EMBED,  // 元数据模式：把 frontmatter 的 category 等一并纳入向量化
                // ⚠️ 不要设置 dimensions 参数：硅基流动的 bge-m3 不支持（返回 400 code=20015，
                // 实测复现），那是 OpenAI text-embedding-3 的特性。bge-m3 原生输出 1024 维，
                // 与 pgvector 的 vector(1024) 天然对齐，无需显式声明。
                OpenAiEmbeddingOptions.builder()
                        .model("BAAI/bge-m3")
                        .build());
    }
}
