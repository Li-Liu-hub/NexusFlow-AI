// 知识检索与 RAG 问答端点（InnerKbController + OaChatController 的 Go 等价物）。
//
//	POST /oa/chat/sync       混合检索 + 上下文增强回答（内核知识检索工具的下游）
//	POST /inner/kb/ingest    切分 → embedding → 写 pgvector（内核管理面上传的落库端）
//	POST /inner/kb/delete    按文档清向量
//
// /inner/** 是内网接口（内核直连），与 backend 的 /api/inner/** 同一信任模型：
// 不经网关、不验 AK/SK。/oa/chat/sync 经网关 AK/SK 签名调用（interface_info 登记）。
package terminal

import (
	"encoding/json"
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/kb"
)

// KBHandler 承载知识检索端点。
type KBHandler struct {
	Svc *kb.Service
}

// NewKBHandler 构造。
func NewKBHandler(svc *kb.Service) *KBHandler { return &KBHandler{Svc: svc} }

// RegisterKB 挂载三个端点。
func (h *KBHandler) RegisterKB(r gin.IRouter) {
	r.POST("/oa/chat/sync", h.oaChatSync)
	r.POST("/inner/kb/ingest", h.ingest)
	r.POST("/inner/kb/delete", h.deleteDoc)
}

type oaChatRequest struct {
	Message        string `json:"message"`
	ConversationID string `json:"conversationId"`
	UserID         string `json:"userId"`
}

// oaChatSync 检索增强问答。失败返回 200 + {"error": ...}（对齐 Java 的错误面）。
func (h *KBHandler) oaChatSync(c *gin.Context) {
	var req oaChatRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.Message == "" {
		c.JSON(http.StatusOK, gin.H{"error": "message 不能为空"})
		return
	}
	answer, err := h.Svc.OaChatSync(c.Request.Context(), req.Message)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	c.Data(http.StatusOK, "text/plain; charset=utf-8", []byte(answer))
}

type ingestRequest struct {
	DocumentID      string `json:"documentId"`
	KnowledgeBaseID string `json:"knowledgeBaseId"`
	SourceName      string `json:"sourceName"`
	Category        string `json:"category"`
	Content         string `json:"content"`
	ChunkSize       int    `json:"chunkSize"`
}

// ingest 切分 → embedding → 落 pgvector，返回分片数与嵌入模型名（内核回写账本）。
func (h *KBHandler) ingest(c *gin.Context) {
	var req ingestRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "请求体格式错误"})
		return
	}
	if req.DocumentID == "" {
		c.JSON(http.StatusOK, gin.H{"error": "documentId 必填"})
		return
	}
	if req.KnowledgeBaseID == "" {
		c.JSON(http.StatusOK, gin.H{"error": "knowledgeBaseId 必填"})
		return
	}
	if req.Content == "" {
		c.JSON(http.StatusOK, gin.H{"error": "content 为空，无可入库内容"})
		return
	}
	count, err := h.Svc.Ingest(c.Request.Context(), req.DocumentID, req.KnowledgeBaseID,
		req.SourceName, req.Category, req.Content, req.ChunkSize)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"chunkCount": count, "embeddingModel": h.Svc.Cfg.EmbedModel})
}

// deleteDoc 按文档清全部向量。
func (h *KBHandler) deleteDoc(c *gin.Context) {
	var req struct {
		DocumentID string `json:"documentId"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.DocumentID == "" {
		c.JSON(http.StatusOK, gin.H{"error": "documentId 必填"})
		return
	}
	n, err := h.Svc.DeleteByDocument(c.Request.Context(), req.DocumentID)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	_ = n
	c.JSON(http.StatusOK, gin.H{"deleted": true})
}

// marshalJSON 供测试复用的小工具。
func marshalJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

var _ = marshalJSON
