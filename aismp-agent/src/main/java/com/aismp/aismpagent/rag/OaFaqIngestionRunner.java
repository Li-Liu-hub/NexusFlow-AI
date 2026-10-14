package com.aismp.aismpagent.rag;

// 应用启动后执行一次性任务
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
// 属性注入
import org.springframework.beans.factory.annotation.Value;
// 组件注册
import org.springframework.stereotype.Component;
// Spring AI 文档与向量库
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

// 日志
import lombok.extern.slf4j.Slf4j;

/**
 * OA FAQ 知识库一次性灌库 Runner
 *
 * 背景：vector_store 原在阿里云 RDS（有数据），PG 本地化迁移后只建了空表，
 * ETL 管线缺"触发"一段——本类补上 Load 前的启动开关。
 *
 * 用法（仅灌库时带参启动，平时启动不受影响）：
 *   java -jar aismp-agent.jar --oa.rag.ingest=true
 *
 * 为什么放 Java 侧：md 在 classpath、加载器和智谱 embedding 客户端都是现成 Bean，
 * 且检索方（PgVectorStore.similaritySearch）与写入方同构，schema/metadata 天然一致。
 * Python 编排层不碰 PG（无状态设计），灌库属于 agent 服务自己的数据初始化。
 */
// 注册为组件
@Component
// 启用日志
@Slf4j
public class OaFaqIngestionRunner implements ApplicationRunner {

    // 灌库开关：默认 false，只有显式 --oa.rag.ingest=true 才执行（防止每次启动重复写入造成向量重复）
    @Value("${oa.rag.ingest:false}")
    private boolean ingest;

    // FAQ 文档加载器（读 classpath:faq/**/*.md，frontmatter 清洗 + category 元数据）
    private final OaFaqDocumentLoader faqDocumentLoader;

    // Spring AI 向量库（starter 自动装配的 PgVectorStore，@Primary 智谱 embedding-3 1024 维）
    private final VectorStore vectorStore;

    // 构造函数注入
    public OaFaqIngestionRunner(OaFaqDocumentLoader faqDocumentLoader, VectorStore vectorStore) {
        this.faqDocumentLoader = faqDocumentLoader;
        this.vectorStore = vectorStore;
    }

    // 应用启动完成后回调：开关打开才灌库
    @Override
    public void run(ApplicationArguments args) {
        // 开关未打开，直接跳过（日常启动路径零开销）
        if (!ingest) {
            return;
        }
        // ETL：Extract+Transform（加载器）→ Load（向量库写入，内部自动调 embedding）
        java.util.List<Document> documents = faqDocumentLoader.loadFaqDocuments();
        log.info("知识库灌库开始：共 {} 篇 FAQ 待向量化写入", documents.size());
        vectorStore.add(documents);
        log.info("知识库灌库完成：vector_store 新增 {} 条向量", documents.size());
    }
}
