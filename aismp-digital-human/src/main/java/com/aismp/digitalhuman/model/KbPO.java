package com.aismp.digitalhuman.model;

import java.time.LocalDateTime;

/**
 * 知识库目录项（a_knowledge_base 的管理面投影）。
 *
 * <p>注意表里的主键是 {@code id}（bigint），而业务上的「知识库ID」是
 * {@code knowledge_base_id}（UUID 字符串）—— 挂载关系存 bigint 主键，
 * 文档清单按 UUID 关联，两个都带出来免得前端二次查询。
 */
public class KbPO {

    private Long id;
    private String kbUuid;
    private String name;
    private String description;
    private Integer isDefault;
    private Integer docCount;
    private Integer chunkCount;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKbUuid() { return kbUuid; }
    public void setKbUuid(String kbUuid) { this.kbUuid = kbUuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getIsDefault() { return isDefault; }
    public void setIsDefault(Integer isDefault) { this.isDefault = isDefault; }
    public Integer getDocCount() { return docCount; }
    public void setDocCount(Integer docCount) { this.docCount = docCount; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
