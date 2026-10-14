package com.aismp.aismpagent.controller;

// Spring AI 文档模型
import org.springframework.ai.document.Document;
// Spring AI 向量存储抽象接口
import org.springframework.ai.vectorstore.VectorStore;
// Spring AI 元数据过滤表达式构造器（按 document_id 删除向量用）
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
// 属性注入
import org.springframework.beans.factory.annotation.Value;
// Lombok 日志
import lombok.extern.slf4j.Slf4j;
// Spring Web 注解
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Java 集合
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库内部接口（管理面专用，2026-10-06 批 D）。
 *
 * <p><b>为什么加在这里</b>：向量库（PGVector）与嵌入模型（硅基流动 bge-m3）都归本服务，
 * 内核负责管理面（a_knowledge_base / a_knowledge_document 的账本），
 * 上传文档的「切分 → 向量化 → 落库」必须由持有向量库的一端执行 —— 内核只做协议调用与状态回写。
 *
 * <p><b>数据约定（与内核管理面共享）</b>：每篇文档的分片带元数据
 * {@code document_id / knowledge_base_id / source_name / chunk_index / category(可选)}。
 * 分片 id 由 Spring AI 自动生成 UUID —— vector_store.id 列是 uuid 类型，
 * 自定义拼接串（如 documentId-c0）会在写入时抛 "UUID string too large"（实测踩过）；
 * 重复写入靠「写前按 document_id 过滤删除」保证幂等，不依赖分片 id。
 *
 * <p><b>鉴权</b>：本组接口是内网接口（内核 127.0.0.1 直连），
 * 与 backend 的 /api/inner/** 同一信任模型，不经网关、不验 AK/SK。
 */
@RestController
@RequestMapping("/inner/kb")
@Slf4j
public class InnerKbController {

    /** 单分片目标长度（字符）。中文场景 1 字≈1 token，800 字符远低于 bge-m3 的 8192 上限，
     *  同时避免长文把相似度稀释掉。 */
    private static final int DEFAULT_CHUNK_SIZE = 800;

    /** 超长段落硬切时向前回看句末标点的窗口（尽量不断句）。 */
    private static final int SENTENCE_LOOKBACK = 120;

    // 向量库（PGVector）
    private final VectorStore vectorStore;

    // 实际使用的嵌入模型名（回写 a_knowledge_document.embedding_model 用，避免内核硬编码）
    private final String embeddingModel;

    public InnerKbController(VectorStore vectorStore,
                             @Value("${spring.ai.openai.embedding.options.model:BAAI/bge-m3}") String embeddingModel) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 文本入库：切分 → embedding → 写 PGVector。
     * 返回实际分片数（内核据此回写 chunk_count）与嵌入模型名。
     */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody IngestRequest req) {
        if (req.documentId() == null || req.documentId().isBlank()) {
            return Map.of("error", "documentId 必填");
        }
        if (req.knowledgeBaseId() == null || req.knowledgeBaseId().isBlank()) {
            return Map.of("error", "knowledgeBaseId 必填");
        }
        if (req.content() == null || req.content().isBlank()) {
            return Map.of("error", "content 为空，无可入库内容");
        }
        int chunkSize = req.chunkSize() == null || req.chunkSize() < 100 ? DEFAULT_CHUNK_SIZE : req.chunkSize();

        // 幂等：先清掉该文档的旧分片（重建索引 / 失败重试都会走到这里）
        vectorStore.delete(new FilterExpressionBuilder().eq("document_id", req.documentId()).build());

        List<String> chunks = split(req.content(), chunkSize);
        if (chunks.isEmpty()) {
            return Map.of("error", "切分后没有有效分片");
        }

        String sourceName = req.sourceName() == null || req.sourceName().isBlank()
                ? "untitled" : req.sourceName();
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("document_id", req.documentId());
            meta.put("knowledge_base_id", req.knowledgeBaseId());
            meta.put("source_name", sourceName);
            meta.put("chunk_index", i);
            // category 是可选的：给了就进 RAG 的分类过滤（与 FAQ 语料的元数据口径一致），
            // 没给则只在「分类=none」的提问中可被无过滤检索命中
            if (req.category() != null && !req.category().isBlank()) {
                meta.put("category", req.category());
            }
            // 不指定 id：由 Document 自动生成 UUID（vector_store.id 是 uuid 列，只收合法 UUID）
            docs.add(new Document(chunks.get(i), meta));
        }
        vectorStore.add(docs);
        log.info("知识库入库完成：document={} source={} 分片={} kb={}",
                req.documentId(), sourceName, docs.size(), req.knowledgeBaseId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunkCount", docs.size());
        result.put("embeddingModel", embeddingModel);
        return result;
    }

    /** 删除文档的全部向量（按 document_id 元数据过滤，与幂等写入同一口径）。 */
    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody DeleteRequest req) {
        if (req.documentId() == null || req.documentId().isBlank()) {
            return Map.of("error", "documentId 必填");
        }
        vectorStore.delete(new FilterExpressionBuilder().eq("document_id", req.documentId()).build());
        log.info("知识库删除向量：document={}", req.documentId());
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------------
    // 切分：段落优先，超长段落按句末标点回看硬切
    // ------------------------------------------------------------------

    private List<String> split(String content, int maxLen) {
        String text = content.replace("\r\n", "\n").replace("\r", "\n").trim();
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String para : text.split("\n{2,}")) {
            String p = para.trim();
            if (p.isEmpty()) {
                continue;
            }
            // 缓冲已满：先冲掉当前块再处理本段
            if (buf.length() > 0 && buf.length() + p.length() + 2 > maxLen) {
                chunks.add(buf.toString());
                buf.setLength(0);
            }
            // 单段就超长：先冲缓冲，再硬切本段
            if (p.length() > maxLen) {
                if (buf.length() > 0) {
                    chunks.add(buf.toString());
                    buf.setLength(0);
                }
                chunks.addAll(hardSplit(p, maxLen));
                continue;
            }
            if (buf.length() > 0) {
                buf.append("\n\n");
            }
            buf.append(p);
        }
        if (buf.length() > 0) {
            chunks.add(buf.toString());
        }
        return chunks;
    }

    private List<String> hardSplit(String p, int maxLen) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < p.length()) {
            int end = Math.min(start + maxLen, p.length());
            if (end < p.length()) {
                // 向前回看句末标点，尽量不把句子切成两半
                for (int i = end; i > Math.max(start, end - SENTENCE_LOOKBACK); i--) {
                    char c = p.charAt(i - 1);
                    if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                            || c == '.' || c == '!' || c == '?' || c == ';') {
                        end = i;
                        break;
                    }
                }
            }
            String piece = p.substring(start, end).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            start = end;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 请求体（record：只收字段，不做别的）
    // ------------------------------------------------------------------

    /** 入库请求：content 为纯文本正文（内核已把文件解码为 UTF-8 文本）。 */
    public record IngestRequest(String documentId, String knowledgeBaseId, String sourceName,
                                String category, String content, Integer chunkSize) {
    }

    /** 删除请求：按 documentId 清掉全部历史分片。 */
    public record DeleteRequest(String documentId) {
    }
}