package com.aismp.digitalhuman.model;

import java.time.LocalDateTime;

/**
 * 知识文档（a_knowledge_document 的管理面投影，2026-10-06 批 D）。
 *
 * <p>状态语义与表一致：{@code processing}（已接收、向量化中）→
 * {@code ready}（已入库，chunk_count 有效）/ {@code failed}（error_message 有值）；
 * 删除是软删（{@code deleted}），保留行便于追溯。
 */
public class KbDocPO {

    private Long id;
    /** 稳定文档ID（UUID）：跨版本不变，向量元数据 document_id 与之对应。 */
    private String documentId;
    /** 所属知识库ID（a_knowledge_base.knowledge_base_id，UUID）。 */
    private String kbUuid;
    private String entCode;
    private String sourceName;
    private String contentType;
    private Long sizeBytes;
    private String checksumSha256;
    private String embeddingModel;
    private Integer version;
    private String status;
    private Integer chunkCount;
    private String errorMessage;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getKbUuid() { return kbUuid; }
    public void setKbUuid(String kbUuid) { this.kbUuid = kbUuid; }
    public String getEntCode() { return entCode; }
    public void setEntCode(String entCode) { this.entCode = entCode; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}