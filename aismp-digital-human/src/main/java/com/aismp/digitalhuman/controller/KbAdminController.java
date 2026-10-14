package com.aismp.digitalhuman.controller;

import com.aismp.digitalhuman.auth.AuthInterceptor;
import com.aismp.digitalhuman.auth.UserPrincipal;
import com.aismp.digitalhuman.model.KbDocPO;
import com.aismp.digitalhuman.repository.KbAdminRepository;
import com.aismp.digitalhuman.service.AgentKbClient;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库管理（管理面）：a_knowledge_base / a_knowledge_document 的 CRUD 与文档上传（2026-10-06 批 D）。
 *
 * <p><b>职责切分</b>：内核管「账本与文件」—— 建库、存上传文件、写文档行；
 * 向量化（切分 → embedding → PGVector）委托给 aismp-agent 的
 * {@code POST /inner/kb/ingest}（见 {@link AgentKbClient}），再把 chunk_count 回写。
 *
 * <p><b>分类（category）为什么重要</b>：agent 侧 RAG 检索带着分类过滤
 * （intro/process/role/operation/trouble，见 OaRagQueryTransformer），
 * 上传文档带上分类才会被对应的提问检索到；不带分类时只在「分类=none」的提问里可命中。
 * 分类值随文件一起落盘（文件名 {@code <documentId>_v<version>_<category>.<ext>}），
 * 重建索引时从文件名还原，不额外占数据库列。
 *
 * <p><b>支持的文件类型</b>：文本类（txt/md/markdown/csv/json），UTF-8 解码后入库；
 * PDF/Word 等二进制文档需先转成文本（当前不做解析器集成）。
 */
@RestController
@RequestMapping("/admin/kb")
public class KbAdminController {

    /** 默认租户：与 a_knowledge_base 现有数据一致（ENT001）。 */
    private static final String DEFAULT_ENT_CODE = "ENT001";

    /** 上传大小上限：与 application.yml 的 multipart 限制保持一致。 */
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** 允许的文本扩展名。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown", "csv", "json");

    /** 合法分类：与 OaRagQueryTransformer.CATEGORIES 严格对齐。 */
    private static final Set<String> CATEGORIES = Set.of("intro", "process", "role", "operation", "trouble");

    /** 文件名里表示「无分类」的占位（文件名不能有空段）。 */
    private static final String NO_CATEGORY = "none";

    /** 切片大小默认值 / 允许区间（与 agent 侧 ingest 的 <100 兜底逻辑对齐）。 */
    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int MIN_CHUNK_SIZE = 100;
    private static final int MAX_CHUNK_SIZE = 4000;

    @Resource
    private KbAdminRepository repository;

    @Resource
    private AgentKbClient agentKbClient;

    /** 上传文件落盘目录（重建索引的源文件；systemd 部署时用 KB_UPLOAD_DIR 指到持久目录）。 */
    @Value("${kb.upload-dir:./kb-uploads}")
    private String uploadDir;

    /**
     * 平台统一口径的嵌入模型（管理台只读展示用）。
     * 真实生效方在 aismp-agent 的 OaVectorStoreConfig（检索与入库必须同模型，
     * 否则向量不在同一空间、检索不中）—— 这里只读透出，避免管理台硬编码漂移。
     */
    @Value("${kb.embedding-model:BAAI/bge-m3}")
    private String embeddingModel;

    // ------------------------------------------------------------------
    // 知识库 CRUD
    // ------------------------------------------------------------------

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        return repository.listKbs();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        Map<String, Object> kb = repository.findKbById(id);
        return kb == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(kb);
    }

    /** 入库参数：管理台表单默认值 + 嵌入模型只读展示。 */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embeddingModel", embeddingModel);
        result.put("defaultChunkSize", DEFAULT_CHUNK_SIZE);
        result.put("minChunkSize", MIN_CHUNK_SIZE);
        result.put("maxChunkSize", MAX_CHUNK_SIZE);
        return result;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String name = str(body.get("name"));
        if (name == null) {
            return Map.of("error", "知识库名称（name）必填");
        }
        String entCode = str(body.get("entCode"));
        if (entCode == null) {
            entCode = DEFAULT_ENT_CODE;
        }
        Integer chunkSize;
        if (body.get("chunkSize") != null) {
            chunkSize = intOrNull(body.get("chunkSize"));
            String err = chunkSizeError(chunkSize);
            if (err != null) {
                return Map.of("error", err);
            }
        } else {
            chunkSize = DEFAULT_CHUNK_SIZE;
        }
        String kbUuid = UUID.randomUUID().toString();
        Long id = repository.insertKb(kbUuid, entCode, name, str(body.get("description")),
                chunkSize, currentUser(request));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("knowledgeBaseId", kbUuid);
        result.put("name", name);
        result.put("chunkSize", chunkSize);
        return result;
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> existing = repository.findKbById(id);
        if (existing == null) {
            return Map.of("error", "知识库不存在: " + id);
        }
        String name = str(body.get("name"));
        if (name == null) {
            name = String.valueOf(existing.get("name"));
        }
        String description = str(body.get("description"));
        if (description == null) {
            description = String.valueOf(existing.get("description"));
        }
        String status = str(body.get("status"));
        if (status == null) {
            status = String.valueOf(existing.get("status"));
        }
        if (!status.equals("active") && !status.equals("inactive")) {
            return Map.of("error", "status 只能是 active / inactive");
        }
        Integer chunkSize = intOrNull(existing.get("chunk_size"));
        if (body.get("chunkSize") != null) {
            Integer v = intOrNull(body.get("chunkSize"));
            String err = chunkSizeError(v);
            if (err != null) {
                return Map.of("error", err);
            }
            chunkSize = v;
        }
        int rows = repository.updateKb(id, name, description, chunkSize, status);
        return Map.of("updated", rows, "status", status, "chunkSize", chunkSize);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Map<String, Object> kb = repository.findKbById(id);
        if (kb == null) {
            return Map.of("error", "知识库不存在: " + id);
        }
        // 保护：还有数字员工挂着它时不允许删（否则挂载变成悬空引用，装配时才报错）
        int mounted = repository.countAgentsMounted(id);
        if (mounted > 0) {
            return Map.of("error", "还有 " + mounted + " 个数字员工挂载着该知识库，请先解除挂载再删除");
        }
        String kbUuid = String.valueOf(kb.get("knowledge_base_id"));
        List<KbDocPO> readyDocs = repository.listReadyDocs(kbUuid);
        // 先删向量再软删账本：顺序反了的话 ready 清单就查不到了
        for (KbDocPO doc : readyDocs) {
            agentKbClient.delete(doc.getDocumentId());
        }
        int docs = repository.softDeleteDocsOfKb(kbUuid);
        int rows = repository.softDeleteKb(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", rows);
        result.put("docsDeleted", docs);
        return result;
    }

    // ------------------------------------------------------------------
    // 文档：上传 / 列表 / 删除 / 重建索引
    // ------------------------------------------------------------------

    @GetMapping("/{id}/documents")
    public ResponseEntity<?> documents(@PathVariable Long id) {
        Map<String, Object> kb = repository.findKbById(id);
        if (kb == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository.listDocs(String.valueOf(kb.get("knowledge_base_id"))));
    }

    /**
     * 上传文档：存文件 → 写 processing 行 → 调 agent 向量化 → 回写 ready/failed。
     *
     * @param category 可选分类（intro/process/role/operation/trouble），决定该文档能被哪类提问检索到
     */
    @PostMapping("/{id}/documents")
    public Map<String, Object> upload(@PathVariable Long id,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "category", required = false) String category,
                                      HttpServletRequest request) {
        Map<String, Object> kb = repository.findKbById(id);
        if (kb == null) {
            return Map.of("error", "知识库不存在: " + id);
        }
        if (!"active".equals(String.valueOf(kb.get("status")))) {
            return Map.of("error", "知识库已停用，不能上传文档");
        }
        if (file == null || file.isEmpty()) {
            return Map.of("error", "上传文件为空");
        }
        String sourceName = file.getOriginalFilename() == null ? "untitled.txt" : file.getOriginalFilename();
        String ext = extension(sourceName);
        if (ext == null || !TEXT_EXTENSIONS.contains(ext)) {
            return Map.of("error", "暂只支持文本类文档（txt / md / markdown / csv / json），二进制文档请先转成文本");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            return Map.of("error", "文件超过 10MB 上限");
        }
        String cat = normalizeCategory(category);
        if (category != null && !category.isBlank() && cat == null) {
            return Map.of("error", "category 只能是 " + CATEGORIES + " 之一");
        }

        String kbUuid = String.valueOf(kb.get("knowledge_base_id"));
        if (repository.existsSourceName(kbUuid, sourceName)) {
            return Map.of("error", "同名文档已存在：「" + sourceName + "」，请先删除旧文档再上传");
        }

        // 读内容 → 落盘（文件是重建索引的源，丢了就没法 reindex）
        byte[] bytes;
        String content;
        try {
            bytes = file.getBytes();
            content = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Map.of("error", "读取上传文件失败：" + e.getMessage());
        }
        if (content.isBlank()) {
            return Map.of("error", "文件内容为空（或不是 UTF-8 文本）");
        }

        String documentId = UUID.randomUUID().toString();
        try {
            storeFile(documentId, 1, cat, ext, bytes);
        } catch (IOException e) {
            return Map.of("error", "保存上传文件失败：" + e.getMessage());
        }

        KbDocPO po = new KbDocPO();
        po.setDocumentId(documentId);
        po.setKbUuid(kbUuid);
        po.setEntCode(String.valueOf(kb.get("ent_code")));
        po.setSourceName(sourceName);
        po.setContentType(file.getContentType());
        po.setSizeBytes(file.getSize());
        po.setChecksumSha256(sha256(bytes));
        po.setVersion(1);
        po.setStatus("processing");
        po.setChunkCount(0);
        repository.insertDoc(po, currentUser(request));

        return ingestAndUpdate(kbUuid, documentId, sourceName, cat, content,
                intOrNull(kb.get("chunk_size")));
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    public Map<String, Object> deleteDocument(@PathVariable Long id, @PathVariable String documentId) {
        Map<String, Object> kb = repository.findKbById(id);
        if (kb == null) {
            return Map.of("error", "知识库不存在: " + id);
        }
        String kbUuid = String.valueOf(kb.get("knowledge_base_id"));
        KbDocPO doc = repository.findDoc(kbUuid, documentId);
        if (doc == null) {
            return Map.of("error", "文档不存在: " + documentId);
        }
        agentKbClient.delete(documentId);
        removeFiles(documentId, doc.getVersion());
        int rows = repository.softDeleteDoc(kbUuid, documentId);
        return Map.of("deleted", rows, "documentId", documentId);
    }

    /**
     * 重建索引：把该知识库全部 ready 文档按落盘源文件重新向量化。
     * 每篇独立成败（一篇失败不影响其它），结果逐篇返回。
     */
    @PostMapping("/{id}/reindex")
    public Map<String, Object> reindex(@PathVariable Long id) {
        Map<String, Object> kb = repository.findKbById(id);
        if (kb == null) {
            return Map.of("error", "知识库不存在: " + id);
        }
        String kbUuid = String.valueOf(kb.get("knowledge_base_id"));
        Integer chunkSize = intOrNull(kb.get("chunk_size"));
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        for (KbDocPO doc : repository.listReadyDocs(kbUuid)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("documentId", doc.getDocumentId());
            item.put("sourceName", doc.getSourceName());
            String[] found = findStoredFile(doc.getDocumentId(), doc.getVersion());
            if (found == null) {
                item.put("error", "源文件缺失（历史数据或落盘目录变更），无法重建");
            } else {
                try {
                    String content = new String(Files.readAllBytes(Path.of(found[0])), StandardCharsets.UTF_8);
                    Map<String, Object> r = ingestAndUpdate(kbUuid, doc.getDocumentId(),
                            doc.getSourceName(), fromFileCategory(found[1]), content, chunkSize);
                    item.put("chunkCount", r.get("chunkCount"));
                    if (r.containsKey("error")) {
                        item.put("error", r.get("error"));
                    } else {
                        ok++;
                    }
                } catch (IOException e) {
                    item.put("error", "读取源文件失败：" + e.getMessage());
                }
            }
            results.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", results.size());
        result.put("ok", ok);
        result.put("results", results);
        return result;
    }

    // ------------------------------------------------------------------
    // 私有辅助
    // ------------------------------------------------------------------

    /** 调 agent 向量化并回写文档状态；ingest 失败时标记 failed 并把原因带回管理面。 */
    private Map<String, Object> ingestAndUpdate(String kbUuid, String documentId,
                                                String sourceName, String category, String content,
                                                Integer chunkSize) {
        try {
            AgentKbClient.IngestResult r = agentKbClient.ingest(documentId, kbUuid, sourceName,
                    category, content, chunkSize);
            repository.markReady(kbUuid, documentId, r.chunkCount(), r.embeddingModel());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("documentId", documentId);
            result.put("status", "ready");
            result.put("chunkCount", r.chunkCount());
            result.put("embeddingModel", r.embeddingModel());
            return result;
        } catch (Exception e) {
            repository.markFailed(kbUuid, documentId, e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("documentId", documentId);
            result.put("status", "failed");
            result.put("error", "向量化失败：" + e.getMessage());
            return result;
        }
    }

    /** 解析整数：兼容 Number 与数字字符串；空/非法返回 null（由调用方决定默认或报错）。 */
    private Integer intOrNull(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = str(v);
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 切片大小合法性：null → 提示需为整数；越界 → 提示区间；合法 → null。 */
    private String chunkSizeError(Integer v) {
        if (v == null) {
            return "切片大小（chunkSize）需为整数（字符数）";
        }
        if (v < MIN_CHUNK_SIZE || v > MAX_CHUNK_SIZE) {
            return "切片大小（chunkSize）需在 " + MIN_CHUNK_SIZE + "–" + MAX_CHUNK_SIZE + " 之间（字符）";
        }
        return null;
    }

    /** 落盘：{@code <documentId>_v<version>_<category|none>.<ext>}。 */
    private void storeFile(String documentId, int version, String category, String ext, byte[] bytes)
            throws IOException {
        Path dir = Path.of(uploadDir).toAbsolutePath();
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName(documentId, version, category, ext));
        Files.write(target, bytes);
    }

    /** 找源文件：[绝对路径, 文件名]，找不到返回 null。 */
    private String[] findStoredFile(String documentId, int version) {
        Path dir = Path.of(uploadDir).toAbsolutePath();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        String prefix = documentId + "_v" + version + "_";
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .map(p -> new String[]{p.toString(), p.getFileName().toString()})
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void removeFiles(String documentId, Integer version) {
        Path dir = Path.of(uploadDir).toAbsolutePath();
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = documentId + "_v" + version + "_";
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith(prefix)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 文件删不掉不影响账本删除（软删已完成）
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static String fileName(String documentId, int version, String category, String ext) {
        return documentId + "_v" + version + "_" + (category == null ? NO_CATEGORY : category) + "." + ext;
    }

    /** 从落盘文件名还原分类（{@code <documentId>_v<version>_<category>.<ext>}）。 */
    private static String fromFileCategory(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String noExt = dot < 0 ? fileName : fileName.substring(0, dot);
        int lastUnderscore = noExt.lastIndexOf('_');
        if (lastUnderscore < 0) {
            return null;
        }
        String cat = noExt.substring(lastUnderscore + 1);
        return NO_CATEGORY.equals(cat) ? null : cat;
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank() || NO_CATEGORY.equals(category.trim())) {
            return null;
        }
        String c = category.trim().toLowerCase();
        return CATEGORIES.contains(c) ? c : null;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? null : filename.substring(dot + 1).toLowerCase();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String currentUser(HttpServletRequest request) {
        UserPrincipal principal = AuthInterceptor.current(request);
        return principal == null ? "admin" : principal.userCode();
    }
}