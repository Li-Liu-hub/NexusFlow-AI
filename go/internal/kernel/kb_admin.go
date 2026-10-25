// 知识库管理面（KbAdminController 的 Go 等价物）：a_knowledge_base /
// a_knowledge_document 账本 CRUD + 文档上传（正文经 agent /inner/kb/ingest
// 切分→embedding→落 pgvector，内核只做协议调用与状态回写）。
//
// 重索引支撑：新增 a_knowledge_document_text 附加表存原文（既有 schema 不改，
// 纯增量），删除/重建索引依赖它而非重新上传。
package kernel

import (
	"context"
	"reflect"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/kb"
)

// kbCategories 合法分类（与 faq 语料 frontmatter / 查询转换器严格对齐）。
var kbCategories = map[string]bool{
	"intro": true, "process": true, "role": true, "operation": true, "trouble": true,
}

// kbTextExtensions 管理台允许的文本扩展名（Java 版同款白名单，无 PDF/DOCX）。
var kbTextExtensions = map[string]bool{
	"txt": true, "md": true, "markdown": true, "csv": true, "json": true,
}

const kbMaxFileBytes = 10 << 20

// RegisterKBAdmin 挂载 /admin/kb/**（挂在已有的 e.auth 组内）。
func (e *Engine) RegisterKBAdmin(r gin.IRouter) {
	g := r.Group("/kb")  // 已挂在 /admin 组内，勿重复前缀
	g.GET("/list", e.kbList)
	g.GET("/settings", e.kbSettings)
	g.POST("", e.kbCreate)
	g.GET("/:id", e.kbGet)
	g.PUT("/:id", e.kbUpdate)
	g.DELETE("/:id", e.kbDelete)
	g.GET("/:id/documents", e.kbDocsList)
	g.POST("/:id/documents", e.kbUpload)
	g.DELETE("/:id/documents/:documentId", e.kbDocDelete)
	g.POST("/:id/reindex", e.kbReindex)
}

func kbJSON(c *gin.Context, data any, err error) {
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}
	// nil slice 装进 any 后 != nil（Go 接口陷阱），反射判空保证输出 [] 而非 null
	if data == nil || reflect.ValueOf(data).Kind() == reflect.Slice && reflect.ValueOf(data).IsNil() {
		data = []any{}
	}
	c.JSON(http.StatusOK, data)
}

// kbExistsByUUID 账本 uuid → 内部 id。
func (e *Engine) kbExistsByUUID(ctx context.Context, kbUUID string) (int64, bool, error) {
	var id int64
	err := e.Terminal.db.QueryRowContext(ctx,
		`SELECT id FROM a_knowledge_base WHERE ent_code = ? AND knowledge_base_id = ? AND status <> 'deleted'`,
		"ENT001", kbUUID).Scan(&id)
	if err == sql.ErrNoRows {
		return 0, false, nil
	}
	return id, err == nil, err
}


// kbUUIDOf 数字 id → 账本 UUID（列存的是 knowledge_base_id 字符串，URL 传数字 id）。
func (e *Engine) kbUUIDOf(ctx context.Context, id string) (string, bool) {
	var uuid string
	err := e.Terminal.db.QueryRowContext(ctx,
		`SELECT knowledge_base_id FROM a_knowledge_base WHERE id = ? AND status <> 'deleted'`, id).Scan(&uuid)
	return uuid, err == nil
}

func (e *Engine) kbList(c *gin.Context) {
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db, `
		SELECT kb.id, kb.knowledge_base_id, kb.ent_code, kb.name, IFNULL(kb.description,'') AS description,
		       kb.chunk_size, kb.status, IFNULL(kb.is_default,0) AS is_default,
		       (SELECT COUNT(*) FROM a_knowledge_document d WHERE d.knowledge_base_id = kb.knowledge_base_id AND d.status <> 'deleted') AS document_count,
		       (SELECT IFNULL(SUM(d.chunk_count),0) FROM a_knowledge_document d WHERE d.knowledge_base_id = kb.knowledge_base_id AND d.status <> 'deleted') AS chunk_count
		  FROM a_knowledge_base kb WHERE kb.status <> 'deleted' ORDER BY kb.id`)
	kbJSON(c, rows, err)
}

func (e *Engine) kbGet(c *gin.Context) {
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db,
		`SELECT id, knowledge_base_id, ent_code, name, IFNULL(description,'') AS description,
		        chunk_size, status, IFNULL(is_default,0) AS is_default
		   FROM a_knowledge_base WHERE id = ? AND status <> 'deleted'`, c.Param("id"))
	if err != nil || len(rows) == 0 {
		c.JSON(http.StatusOK, gin.H{"error": "知识库不存在"})
		return
	}
	c.JSON(http.StatusOK, rows[0])
}

func (e *Engine) kbSettings(c *gin.Context) {
	cats := make([]string, 0, len(kbCategories))
	for k := range kbCategories {
		cats = append(cats, k)
	}
	c.JSON(http.StatusOK, gin.H{
		"categories":     cats,
		"maxFileBytes":   kbMaxFileBytes,
		"defaultChunkSize": 800,
		"minChunkSize":   100,
		"maxChunkSize":   4000,
		"textExtensions": []string{"txt", "md", "markdown", "csv", "json"},
		"embeddingModel": e.Cfg.EmbedModel,
	})
}

type kbUpsertRequest struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	ChunkSize   int    `json:"chunkSize"`
	IsDefault   bool   `json:"isDefault"`
}

func (e *Engine) kbCreate(c *gin.Context) {
	var req kbUpsertRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.Name == "" {
		c.JSON(http.StatusOK, gin.H{"error": "name 必填"})
		return
	}
	kbUUID := newUUID()
	chunkSize := clampInt(req.ChunkSize, 100, 4000)
	if chunkSize == 0 {
		chunkSize = 800
	}
	user := principalOf(c)
	by := ""
	if user != nil {
		by = user.UserCode
	}
	_, err := e.Terminal.db.ExecContext(c.Request.Context(), `
		INSERT INTO a_knowledge_base (knowledge_base_id, ent_code, name, description, chunk_size, status, is_default, created_by, created_at)
		VALUES (?, 'ENT001', ?, ?, ?, 'active', ?, ?, NOW())`,
		kbUUID, req.Name, req.Description, chunkSize, boolToInt(req.IsDefault), by)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "创建失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"knowledge_base_id": kbUUID, "created": true})
}

func (e *Engine) kbUpdate(c *gin.Context) {
	var req kbUpsertRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "请求体格式错误"})
		return
	}
	chunkSize := clampInt(req.ChunkSize, 100, 4000)
	_, err := e.Terminal.db.ExecContext(c.Request.Context(), `
		UPDATE a_knowledge_base SET name = IFNULL(NULLIF(?, ''), name), description = ?, chunk_size = ?
		 WHERE id = ? AND status <> 'deleted'`,
		req.Name, req.Description, chunkSize, c.Param("id"))
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "更新失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"updated": true})
}

func (e *Engine) kbDelete(c *gin.Context) {
	ctx := c.Request.Context()
	id := c.Param("id")
	// 有数字员工挂载时禁止删除
	var bound int
	if err := e.Terminal.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM a_agent WHERE knowledge_base_id = ?`, id).Scan(&bound); err == nil && bound > 0 {
		c.JSON(http.StatusOK, gin.H{"error": fmt.Sprintf("有 %d 个数字员工挂载该知识库，请先解挂", bound)})
		return
	}
	var kbUUID string
	if err := e.Terminal.db.QueryRowContext(ctx,
		`SELECT knowledge_base_id FROM a_knowledge_base WHERE id = ? AND status <> 'deleted'`, id).Scan(&kbUUID); err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "知识库不存在"})
		return
	}
	// 账本软删 + 向量清空（逐文档）
	docs, _ := dbMaps(ctx, e.Terminal.db,
		`SELECT document_id FROM a_knowledge_document WHERE knowledge_base_id = ? AND status <> 'deleted'`, kbUUID)
	for _, d := range docs {
		if docID, ok := d["document_id"].(string); ok {
			_, _ = e.callAgentKBDelete(ctx, docID)
		}
	}
	e.Terminal.db.ExecContext(ctx, `UPDATE a_knowledge_document SET status='deleted' WHERE knowledge_base_id = ?`, kbUUID)
	res, err := e.Terminal.db.ExecContext(ctx,
		`UPDATE a_knowledge_base SET status = 'deleted' WHERE id = ?`, id)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "删除失败"})
		return
	}
	n, _ := res.RowsAffected()
	c.JSON(http.StatusOK, gin.H{"deleted": n > 0})
}

func (e *Engine) kbDocsList(c *gin.Context) {
	kbUUID, ok := e.kbUUIDOf(c.Request.Context(), c.Param("id"))
	if !ok {
		kbJSON(c, []any{}, nil)
		return
	}
	rows, err := dbMaps(c.Request.Context(), e.Terminal.db, `
		SELECT id, document_id, knowledge_base_id, ent_code, source_name, content_type,
		       size_bytes, checksum_sha256, embedding_model, version, status, chunk_count,
		       IFNULL(error_message,'') AS error_message, IFNULL(category,'') AS category, created_at
		  FROM a_knowledge_document
		 WHERE knowledge_base_id = ? AND status <> 'deleted' ORDER BY id DESC`, kbUUID)
	kbJSON(c, rows, err)
}

// kbUpload 上传文档：扩展名/大小/重名校验 → 正文经 agent 入库 → 账本回写。
func (e *Engine) kbUpload(c *gin.Context) {
	ctx := c.Request.Context()
	kbID := c.Param("id")
	var kbUUID, entCode string
	var chunkSize int
	err := e.Terminal.db.QueryRowContext(ctx,
		`SELECT knowledge_base_id, ent_code, IFNULL(chunk_size,800) FROM a_knowledge_base WHERE id = ? AND status <> 'deleted'`,
		kbID).Scan(&kbUUID, &entCode, &chunkSize)
	if err == sql.ErrNoRows {
		c.JSON(http.StatusOK, gin.H{"error": "知识库不存在"})
		return
	} else if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "查询知识库失败"})
		return
	}
	file, err := c.FormFile("file")
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "缺少 file 字段"})
		return
	}
	if file.Size > kbMaxFileBytes {
		c.JSON(http.StatusOK, gin.H{"error": "文件超过 10MB 上限"})
		return
	}
	ext := strings.ToLower(file.Filename)
	if i := strings.LastIndex(ext, "."); i >= 0 {
		ext = ext[i+1:]
	} else {
		ext = "txt"
	}
	if !kbTextExtensions[ext] {
		c.JSON(http.StatusOK, gin.H{"error": "仅支持文本文件（txt/md/markdown/csv/json）"})
		return
	}
	category := normalizeKbCategory(c.PostForm("category"))

	f, err := file.Open()
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "读取文件失败"})
		return
	}
	defer f.Close()
	contentBytes, err := io.ReadAll(io.LimitReader(f, kbMaxFileBytes+1))
	if err != nil || len(contentBytes) > kbMaxFileBytes {
		c.JSON(http.StatusOK, gin.H{"error": "文件超过 10MB 上限"})
		return
	}
	content := string(contentBytes)
	if strings.TrimSpace(content) == "" {
		c.JSON(http.StatusOK, gin.H{"error": "文件内容为空"})
		return
	}
	sourceName := file.Filename
	// 同库重名拒绝（Java 同款约束）
	var dup int
	e.Terminal.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM a_knowledge_document WHERE knowledge_base_id = ? AND source_name = ? AND status <> 'deleted'`,
		kbUUID, sourceName).Scan(&dup)
	if dup > 0 {
		c.JSON(http.StatusOK, gin.H{"error": "同名文档已存在于该知识库"})
		return
	}
	sum := sha256.Sum256(contentBytes)
	documentID := newUUID()
	user := principalOf(c)
	by := ""
	if user != nil {
		by = user.UserCode
	}
	if _, err := e.Terminal.db.ExecContext(ctx, `
		INSERT INTO a_knowledge_document (document_id, knowledge_base_id, ent_code, source_name,
		       content_type, size_bytes, checksum_sha256, embedding_model, version, status, chunk_count, category, created_by, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'processing', 0, ?, ?, NOW())`,
		documentID, kbUUID, entCode, sourceName, ext, file.Size, hex.EncodeToString(sum[:]),
		e.Cfg.EmbedModel, category, by); err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "账本写入失败"})
		return
	}
	// 原文存附加表（重索引依赖）
	e.Terminal.db.ExecContext(ctx,
		`INSERT IGNORE INTO a_knowledge_document_text (document_id, content) VALUES (?, ?)`,
		documentID, content)

	count, err := e.callAgentKBIngest(ctx, documentID, kbUUID, sourceName, category, content, chunkSize)
	if err != nil {
		e.Terminal.db.ExecContext(ctx,
			`UPDATE a_knowledge_document SET status='failed', error_message=? WHERE document_id=?`,
			clip480(err.Error()), documentID)
		c.JSON(http.StatusOK, gin.H{"error": "入库失败: " + err.Error(), "documentId": documentID})
		return
	}
	e.Terminal.db.ExecContext(ctx,
		`UPDATE a_knowledge_document SET status='ready', chunk_count=?, embedding_model=?, error_message=NULL WHERE document_id=?`,
		count, e.Cfg.EmbedModel, documentID)
	c.JSON(http.StatusOK, gin.H{"documentId": documentID, "chunkCount": count, "status": "ready"})
}

// kbDocDelete 删除文档：清向量 + 账本软删。
func (e *Engine) kbDocDelete(c *gin.Context) {
	ctx := c.Request.Context()
	kbUUID, _ := e.kbUUIDOf(ctx, c.Param("id"))
	documentID := c.Param("documentId")
	if _, err := e.callAgentKBDelete(ctx, documentID); err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "向量清理失败: " + err.Error()})
		return
	}
	e.Terminal.db.ExecContext(ctx, `DELETE FROM a_knowledge_document_text WHERE document_id = ?`, documentID)
	res, err := e.Terminal.db.ExecContext(ctx,
		`UPDATE a_knowledge_document SET status='deleted' WHERE knowledge_base_id = ? AND document_id = ?`,
		kbUUID, documentID)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "账本更新失败"})
		return
	}
	n, _ := res.RowsAffected()
	c.JSON(http.StatusOK, gin.H{"deleted": n > 0})
}

// kbReindex 重建索引：按账本逐文档取原文重嵌入（幂等：ingest 先清旧分片）。
func (e *Engine) kbReindex(c *gin.Context) {
	ctx := c.Request.Context()
	kbUUID, _ := e.kbUUIDOf(ctx, c.Param("id"))
	var chunkSize int
	if err := e.Terminal.db.QueryRowContext(ctx,
		`SELECT IFNULL(chunk_size,800) FROM a_knowledge_base WHERE knowledge_base_id = ? AND status <> 'deleted'`,
		kbUUID).Scan(&chunkSize); err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "知识库不存在"})
		return
	}
	docs, err := dbMaps(ctx, e.Terminal.db, `
		SELECT document_id, IFNULL(source_name,'') AS source_name, IFNULL(category,'') AS category
		  FROM a_knowledge_document
		 WHERE knowledge_base_id = ? AND status = 'ready'`, kbUUID)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "读取账本失败"})
		return
	}
	ok, failed := 0, 0
	for _, d := range docs {
		docID, _ := d["document_id"].(string)
		sourceName, _ := d["source_name"].(string)
		category, _ := d["category"].(string)
		var content string
		if err := e.Terminal.db.QueryRowContext(ctx,
			`SELECT content FROM a_knowledge_document_text WHERE document_id = ?`, docID).Scan(&content); err != nil {
			failed++
			continue
		}
		if _, err := e.callAgentKBIngest(ctx, docID, kbUUID, sourceName, category, content, chunkSize); err != nil {
			e.Terminal.db.ExecContext(ctx,
				`UPDATE a_knowledge_document SET status='failed', error_message=? WHERE document_id=?`,
				clip480(err.Error()), docID)
			failed++
			continue
		}
		e.Terminal.db.ExecContext(ctx,
			`UPDATE a_knowledge_document SET status='ready', embedding_model=? WHERE document_id=?`,
			e.Cfg.EmbedModel, docID)
		ok++
	}
	c.JSON(http.StatusOK, gin.H{"reindexed": ok, "failed": failed})
}

// ---- agent 内部调用（/inner/kb/ingest|delete，直连不走网关）----

func (e *Engine) callAgentKBIngest(ctx context.Context, documentID, kbUUID, sourceName, category, content string, chunkSize int) (int, error) {
	body, _ := json.Marshal(map[string]any{
		"documentId": documentID, "knowledgeBaseId": kbUUID,
		"sourceName": sourceName, "category": category,
		"content": content, "chunkSize": chunkSize,
	})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(e.Cfg.AgentBaseURL, "/")+"/inner/kb/ingest", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.HTTP.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	var out struct {
		ChunkCount int    `json:"chunkCount"`
		Error      string `json:"error"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return 0, err
	}
	if out.Error != "" {
		return 0, fmt.Errorf("%s", out.Error)
	}
	return out.ChunkCount, nil
}

func (e *Engine) callAgentKBDelete(ctx context.Context, documentID string) (bool, error) {
	body, _ := json.Marshal(map[string]string{"documentId": documentID})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(e.Cfg.AgentBaseURL, "/")+"/inner/kb/delete", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.HTTP.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	return resp.StatusCode == http.StatusOK, nil
}

func normalizeKbCategory(c string) string {
	c = strings.ToLower(strings.TrimSpace(c))
	if c == "" || c == "none" {
		return ""
	}
	if kbCategories[c] {
		return c
	}
	return ""
}

func clip480(s string) string {
	if len(s) > 480 {
		return s[:480]
	}
	return s
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

var _ = kb.Config{}
var _ = time.Now
