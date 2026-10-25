// bizsuite HTTP 端点：合同信抽取管线 + AI 工单建单。
// 路由无 context-path（网关已剥 /api）：/contract/**、/ticket/**。
// 鉴权：走网关门户通道（Bearer）或 AK/SK；本服务读 X-User-Id 注入身份。
package biz

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
)

// Handler 承载 bizsuite 全部端点。
type Handler struct {
	Store    *Store
	Tickets  *TicketStore
	Proc     *Processor
	Extract  Extractor
	Classify *Classifier
	Platform TicketPlatform
	Archive  Archiver
	Collect  Collector
}

// NewHandler 构造。
func NewHandler(store *Store, tickets *TicketStore, proc *Processor,
	extract Extractor, classify *Classifier, platform TicketPlatform,
	archive Archiver, collect Collector) *Handler {
	return &Handler{Store: store, Tickets: tickets, Proc: proc,
		Extract: extract, Classify: classify, Platform: platform,
		Archive: archive, Collect: collect}
}

// Register 挂载全部端点。
func (h *Handler) Register(r gin.IRouter) {
	ct := r.Group("/contract")
	ct.POST("/notify", h.contractNotify)
	ct.POST("/process", h.contractProcess)
	ct.GET("/list", h.contractList)
	ct.GET("/detail", h.contractDetail)
	ct.POST("/retry", h.contractRetry)

	tk := r.Group("/ticket")
	tk.GET("/categories", h.ticketCategories)
	tk.POST("/assist", h.ticketAssist)
	tk.POST("/create", h.ticketCreate)
	tk.GET("/history", h.ticketHistory)
}

func userIDOf(c *gin.Context) string {
	return c.GetHeader("X-User-Id")
}

// ---- 合同信 ----

type notifyRequest struct {
	FileNo    string `json:"fileNo"`
	FileName  string `json:"fileName"`
	BatchNo   string `json:"batchNo"`
	Content   string `json:"content"`
	AuditHTML string `json:"auditHtml"`
}

// contractNotify 上传通知（9 态的起点：state=UPLOADED）。
func (h *Handler) contractNotify(c *gin.Context) {
	var req notifyRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.FileNo == "" || req.FileName == "" {
		c.JSON(http.StatusOK, gin.H{"error": "fileNo 与 fileName 必填"})
		return
	}
	batchNo := req.BatchNo
	if batchNo == "" {
		batchNo = time.Now().Format("20060102")
	}
	if err := h.Store.Notify(c.Request.Context(), req.FileNo, req.FileName, batchNo, req.Content); err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	if req.AuditHTML != "" {
		h.Store.db.Exec("UPDATE contract_file SET audit_html = ? WHERE file_no = ?", req.AuditHTML, req.FileNo)
	}
	c.JSON(http.StatusOK, gin.H{"accepted": true, "fileNo": req.FileNo, "state": StateUploaded})
}

// contractProcess 推进一批状态机（每文件前进一步；单条失败不影响整批）。
func (h *Handler) contractProcess(c *gin.Context) {
	counts, err := h.Proc.Step(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error(), "counts": counts})
		return
	}
	c.JSON(http.StatusOK, gin.H{"counts": counts})
}

// contractList 文件列表（可按状态过滤）。
func (h *Handler) contractList(c *gin.Context) {
	limit := 100
	if v, err := strconv.Atoi(c.DefaultQuery("limit", "100")); err == nil && v > 0 && v <= 500 {
		limit = v
	}
	files, err := h.Store.List(c.Request.Context(), c.Query("state"), limit)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	if files == nil {
		files = []File{}
	}
	c.JSON(http.StatusOK, gin.H{"files": files})
}

// contractDetail 单文件明细：状态 + 抽取字段。
func (h *Handler) contractDetail(c *gin.Context) {
	f, err := h.Store.GetByFileNo(c.Request.Context(), c.Query("fileNo"))
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	merged, _ := h.Store.GetExtract(c.Request.Context(), c.Query("fileNo"))
	c.JSON(http.StatusOK, gin.H{"file": f, "mergedFields": merged,
		"fieldOrder": ContractFields})
}

// contractRetry 采集失败手动重试（FAILED → COLLECTING，重试计数保留；上限 30 由状态机管）。
func (h *Handler) contractRetry(c *gin.Context) {
	fileNo := c.Query("fileNo")
	if fileNo == "" {
		c.JSON(http.StatusOK, gin.H{"error": "fileNo 必填"})
		return
	}
	if err := h.Store.Transition(c.Request.Context(), fileNo, StateFailed, StateCollecting); err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "仅 FAILED 状态可重试: " + err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"retried": true, "fileNo": fileNo})
}

// ---- 工单建单 ----

// ticketCategories 7 类清单。
func (h *Handler) ticketCategories(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"categories": TicketCategories})
}

type assistRequest struct {
	Text   string `json:"text"`   // 客户描述（APP 语音转写文本也走这里）
	Source string `json:"source"` // pc / app
	UserID string `json:"userId"`
}

// ticketAssist AI 辅助：分类 + 生成建单参数（不落库不建单，前端确认后调 create）。
func (h *Handler) ticketAssist(c *gin.Context) {
	var req assistRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.Text == "" {
		c.JSON(http.StatusOK, gin.H{"error": "text 必填（APP 语音转写文本同样传入 text）"})
		return
	}
	source := req.Source
	if source != "pc" && source != "app" {
		source = "pc"
	}
	category, params, err := h.Classify.ClassifyAndBuild(c.Request.Context(), req.Text)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"category": category, "params": params, "source": source})
}

type createRequest struct {
	RequestID string         `json:"requestId"` // 防重复提交标识
	UserID    string         `json:"userId"`
	Source    string         `json:"source"`
	Text      string         `json:"text"` // 与 assist 相同的描述
	Category  string         `json:"category"`
	Params    map[string]any `json:"params"`
}

// ticketCreate 建单（幂等；远端成功本地失败只补写）。
func (h *Handler) ticketCreate(c *gin.Context) {
	var req createRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.RequestID == "" || req.Text == "" {
		c.JSON(http.StatusOK, gin.H{"error": "requestId 与 text 必填"})
		return
	}
	source := req.Source
	if source != "pc" && source != "app" {
		source = "pc"
	}
	userID := req.UserID
	if userID == "" {
		userID = userIDOf(c)
	}
	// 先落 classified（防重标识立即生效；若已存在沿用首次分类）
	category, params := req.Category, req.Params
	if category == "" || params == nil {
		cat, p, err := h.Classify.ClassifyAndBuild(c.Request.Context(), req.Text)
		if err != nil {
			c.JSON(http.StatusOK, gin.H{"error": err.Error()})
			return
		}
		category, params = cat, p
	}
	rec := &TicketRequest{RequestID: req.RequestID, UserID: userID, Source: source,
		RawText: req.Text, Category: category}
	paramsJSON, _ := json.Marshal(params)
	rec.ParamsJSON = string(paramsJSON)
	if err := h.Tickets.SaveClassified(c.Request.Context(), rec); err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	result, err := CreateTicket(c.Request.Context(), h.Tickets, h.Platform,
		req.RequestID, userID, source, category, params)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

// ticketHistory 历史查询（PC/APP 双端共用）。
func (h *Handler) ticketHistory(c *gin.Context) {
	userID := c.Query("userId")
	if userID == "" {
		userID = userIDOf(c)
	}
	limit := 20
	if v, err := strconv.Atoi(c.DefaultQuery("limit", "20")); err == nil && v > 0 && v <= 100 {
		limit = v
	}
	list, err := h.Tickets.History(c.Request.Context(), userID, limit)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"history": list})
}
