package com.aismp.digitalhuman.repository;

import com.aismp.digitalhuman.model.KbDocPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理面仓储（a_knowledge_base / a_knowledge_document 的读写，2026-10-06 批 D）。
 *
 * <p>读接口（catalog）在 {@link AgentRepository}，这里只补管理面的写路径与更全的读
 * （含非 active 知识库、processing/failed 文档）—— 两边读的是同一批表，字段口径一致。
 *
 * <p><b>软删约定</b>：「删除」= status 置 {@code deleted}（表里有这档状态，保留行便于追溯）；
 * 列表查询一律排除 deleted。
 */
@Repository
public class KbAdminRepository {

    private final JdbcTemplate jdbc;

    public KbAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 知识库（a_knowledge_base）
    // ------------------------------------------------------------------

    private static final RowMapper<KbDocPO> DOC_MAPPER = (rs, i) -> {
        KbDocPO po = new KbDocPO();
        po.setId(rs.getLong("id"));
        po.setDocumentId(rs.getString("document_id"));
        po.setKbUuid(rs.getString("knowledge_base_id"));
        po.setEntCode(rs.getString("ent_code"));
        po.setSourceName(rs.getString("source_name"));
        po.setContentType(rs.getString("content_type"));
        po.setSizeBytes(rs.getObject("size_bytes") == null ? null : rs.getLong("size_bytes"));
        po.setChecksumSha256(rs.getString("checksum_sha256"));
        po.setEmbeddingModel(rs.getString("embedding_model"));
        po.setVersion(rs.getInt("version"));
        po.setStatus(rs.getString("status"));
        po.setChunkCount(rs.getInt("chunk_count"));
        po.setErrorMessage(rs.getString("error_message"));
        po.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return po;
    };

    /** 管理面列表：全部未删除知识库（含 inactive），带 ready 文档数与分片总数。 */
    public List<Map<String, Object>> listKbs() {
        return jdbc.queryForList("""
                SELECT kb.id, kb.knowledge_base_id, kb.ent_code, kb.name, kb.description,
                       kb.chunk_size, kb.status, kb.is_default, kb.created_at, kb.updated_at,
                       SUM(CASE WHEN d.status = 'ready' THEN 1 ELSE 0 END)  AS doc_count,
                       COALESCE(SUM(CASE WHEN d.status = 'ready' THEN d.chunk_count ELSE 0 END), 0) AS chunk_count
                  FROM a_knowledge_base kb
                  LEFT JOIN a_knowledge_document d ON d.knowledge_base_id = kb.knowledge_base_id
                 WHERE kb.status <> 'deleted'
                 GROUP BY kb.id, kb.knowledge_base_id, kb.ent_code, kb.name, kb.description,
                          kb.chunk_size, kb.status, kb.is_default, kb.created_at, kb.updated_at
                 ORDER BY kb.is_default DESC, kb.id
                """);
    }

    public Map<String, Object> findKbById(Long id) {
        List<Map<String, Object>> list = jdbc.queryForList("""
                SELECT id, knowledge_base_id, ent_code, name, description, chunk_size, status, is_default
                  FROM a_knowledge_base WHERE id = ? AND status <> 'deleted'
                """, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Long insertKb(String kbUuid, String entCode, String name, String description,
                         Integer chunkSize, String createdBy) {
        jdbc.update("""
                INSERT INTO a_knowledge_base (knowledge_base_id, ent_code, name, description, chunk_size, status, is_default, created_by)
                VALUES (?, ?, ?, ?, ?, 'active', 0, ?)
                """, kbUuid, entCode, name, description, chunkSize, createdBy);
        return jdbc.queryForObject(
                "SELECT id FROM a_knowledge_base WHERE ent_code = ? AND knowledge_base_id = ?",
                Long.class, entCode, kbUuid);
    }

    public int updateKb(Long id, String name, String description, Integer chunkSize, String status) {
        return jdbc.update(
                "UPDATE a_knowledge_base SET name = ?, description = ?, chunk_size = ?, status = ? WHERE id = ? AND status <> 'deleted'",
                name, description, chunkSize, status, id);
    }

    public int softDeleteKb(Long id) {
        return jdbc.update("UPDATE a_knowledge_base SET status = 'deleted' WHERE id = ?", id);
    }

    public int softDeleteDocsOfKb(String kbUuid) {
        return jdbc.update(
                "UPDATE a_knowledge_document SET status = 'deleted' WHERE knowledge_base_id = ? AND status <> 'deleted'",
                kbUuid);
    }

    /** 挂载了该知识库的数字员工数（a_agent.knowledge_base_id 单挂载）——删除前的占用检查。 */
    public int countAgentsMounted(Long kbId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM a_agent WHERE knowledge_base_id = ?", Integer.class, kbId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------
    // 文档（a_knowledge_document）
    // ------------------------------------------------------------------

    /** 某知识库的文档清单（含 processing/failed，排除 deleted），新的在前。 */
    public List<KbDocPO> listDocs(String kbUuid) {
        return jdbc.query("""
                SELECT id, document_id, knowledge_base_id, ent_code, source_name, content_type,
                       size_bytes, checksum_sha256, embedding_model, version, status, chunk_count,
                       error_message, created_at
                  FROM a_knowledge_document
                 WHERE knowledge_base_id = ? AND status <> 'deleted'
                 ORDER BY id DESC
                """, DOC_MAPPER, kbUuid);
    }

    public KbDocPO findDoc(String kbUuid, String documentId) {
        List<KbDocPO> list = jdbc.query("""
                SELECT id, document_id, knowledge_base_id, ent_code, source_name, content_type,
                       size_bytes, checksum_sha256, embedding_model, version, status, chunk_count,
                       error_message, created_at
                  FROM a_knowledge_document
                 WHERE knowledge_base_id = ? AND document_id = ? AND status <> 'deleted'
                 ORDER BY version DESC
                """, DOC_MAPPER, kbUuid, documentId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 同名文档查重（同一知识库内不允许重名，上传即拒绝）。 */
    public boolean existsSourceName(String kbUuid, String sourceName) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM a_knowledge_document
                 WHERE knowledge_base_id = ? AND source_name = ? AND status <> 'deleted'
                """, Integer.class, kbUuid, sourceName);
        return n != null && n > 0;
    }

    public void insertDoc(KbDocPO po, String createdBy) {
        jdbc.update("""
                INSERT INTO a_knowledge_document (document_id, knowledge_base_id, ent_code, source_name,
                       content_type, size_bytes, checksum_sha256, embedding_model, version, status,
                       chunk_count, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, po.getDocumentId(), po.getKbUuid(), po.getEntCode(), po.getSourceName(),
                po.getContentType(), po.getSizeBytes(), po.getChecksumSha256(), po.getEmbeddingModel(),
                po.getVersion(), po.getStatus(), po.getChunkCount(), createdBy);
    }

    public int markReady(String kbUuid, String documentId, int chunkCount, String embeddingModel) {
        return jdbc.update("""
                UPDATE a_knowledge_document
                   SET status = 'ready', chunk_count = ?, embedding_model = ?, error_message = NULL
                 WHERE knowledge_base_id = ? AND document_id = ?
                """, chunkCount, embeddingModel, kbUuid, documentId);
    }

    public int markFailed(String kbUuid, String documentId, String errorMessage) {
        String safe = errorMessage == null ? "未知错误"
                : (errorMessage.length() > 480 ? errorMessage.substring(0, 480) : errorMessage);
        return jdbc.update("""
                UPDATE a_knowledge_document
                   SET status = 'failed', error_message = ?
                 WHERE knowledge_base_id = ? AND document_id = ?
                """, safe, kbUuid, documentId);
    }

    public int softDeleteDoc(String kbUuid, String documentId) {
        return jdbc.update("""
                UPDATE a_knowledge_document SET status = 'deleted'
                 WHERE knowledge_base_id = ? AND document_id = ? AND status <> 'deleted'
                """, kbUuid, documentId);
    }

    /** 重建索引的输入：某知识库全部 ready 文档。 */
    public List<KbDocPO> listReadyDocs(String kbUuid) {
        return jdbc.query("""
                SELECT id, document_id, knowledge_base_id, ent_code, source_name, content_type,
                       size_bytes, checksum_sha256, embedding_model, version, status, chunk_count,
                       error_message, created_at
                  FROM a_knowledge_document
                 WHERE knowledge_base_id = ? AND status = 'ready'
                 ORDER BY id
                """, DOC_MAPPER, kbUuid);
    }
}