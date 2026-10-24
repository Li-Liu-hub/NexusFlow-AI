// Handler 移植自 TokenAdminController（/admin/token/**）与 InnerTokenController（/inner/token/**）。
//
// 守卫：网关门户通道已校验「令牌已登记」，这里再做一道角色检查（ADMIN/SERVICE 才能改配额）
// —— 与内核原先的拦截器规则一致；未过守卫返回 403。
package tokenadmin

import (
	"context"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/platform/user"
)

// TokenChecker 是守卫需要的令牌校验能力（由 user.Repository 实现，便于单测替身）。
type TokenChecker interface {
	FindToken(ctx context.Context, token string) (user.TokenPrincipal, bool, error)
}

// Handler 承载 /admin/token 与 /inner/token 两组路由。
type Handler struct {
	svc       *Service
	tokens    TokenChecker
	log       *slog.Logger
}

// NewHandler 构造处理器。
func NewHandler(svc *Service, tokens TokenChecker, log *slog.Logger) *Handler {
	return &Handler{svc: svc, tokens: tokens, log: log}
}

// Register 挂载两组路由（等价于 @RequestMapping("/admin/token") 与 ("/inner/token")）。
func (h *Handler) Register(r gin.IRouter) {
	admin := r.Group("/admin/token")
	admin.GET("/usage/overview", h.guard, h.overview)
	admin.GET("/usage/daily", h.guard, h.daily)
	admin.GET("/usage/agents", h.guard, h.agentUsage)
	admin.GET("/usage/monthly", h.guard, h.monthlyUsage)
	admin.GET("/usage/daily-agents", h.guard, h.dailyAgents)
	admin.GET("/quota/list", h.guard, h.listQuotas)
	admin.GET("/target-codes", h.guard, h.targetCodes)
	admin.GET("/feature-usage", h.guard, h.featureUsage)
	admin.POST("/quota", h.guard, h.createQuota)
	admin.PUT("/quota/:id", h.guard, h.updateQuota)
	admin.DELETE("/quota/:id", h.guard, h.deleteQuota)

	inner := r.Group("/inner/token")
	inner.GET("/check", h.check)
	inner.POST("/charge", h.charge)
}

// guard 对齐 AuthRepository.checkAdmin：Bearer 令牌有效且角色为 ADMIN/SERVICE。
// Java 侧抛 ResponseStatusException(FORBIDDEN, reason) → 这里 403 + JSON 体。
func (h *Handler) guard(c *gin.Context) {
	header := c.GetHeader("Authorization")
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		h.deny(c, "未登录（缺少 Bearer token）")
		return
	}
	p, ok, err := h.tokens.FindToken(c.Request.Context(), strings.TrimSpace(header[len(prefix):]))
	if err != nil {
		h.log.Error("守卫校验令牌失败", "err", err)
		h.deny(c, "登录已过期或 token 无效，请重新登录")
		return
	}
	if !ok {
		h.deny(c, "登录已过期或 token 无效，请重新登录")
		return
	}
	if p.Role != "ADMIN" && p.Role != "SERVICE" {
		h.deny(c, "需要管理员权限")
		return
	}
}

func (h *Handler) deny(c *gin.Context, reason string) {
	h.log.Warn("管理面守卫拒绝", "path", c.Request.URL.Path, "reason", reason)
	c.AbortWithStatusJSON(http.StatusForbidden, gin.H{
		"status": http.StatusForbidden, "error": reason, "message": reason,
	})
}

func (h *Handler) overview(c *gin.Context) {
	rows, err := h.svc.Overview(c.Request.Context(), c.Query("month"))
	h.respondRows(c, rows, err)
}

func (h *Handler) daily(c *gin.Context) {
	userID := c.DefaultQuery("userId", "portal_user")
	days, _ := strconv.Atoi(c.DefaultQuery("days", "30"))
	rows, err := h.svc.DailyUsage(c.Request.Context(), userID, days)
	h.respondRows(c, rows, err)
}

func (h *Handler) agentUsage(c *gin.Context) {
	rows, err := h.svc.AgentOverview(c.Request.Context(), c.Query("month"))
	h.respondRows(c, rows, err)
}

func (h *Handler) monthlyUsage(c *gin.Context) {
	result, err := h.svc.MonthlyUsage(c.Request.Context(), c.Query("month"))
	h.respondRows(c, result, err)
}

func (h *Handler) dailyAgents(c *gin.Context) {
	rows, err := h.svc.DailyAgents(c.Request.Context(),
		c.Query("month"), c.Query("userId"), c.Query("agentCode"))
	h.respondRows(c, rows, err)
}

func (h *Handler) listQuotas(c *gin.Context) {
	quotas, err := h.svc.ListQuotas(c.Request.Context())
	if err != nil {
		h.respondErr(c, err)
		return
	}
	if quotas == nil {
		quotas = []*Quota{}
	}
	c.JSON(http.StatusOK, quotas)
}

func (h *Handler) targetCodes(c *gin.Context) {
	rows, err := h.svc.TargetCodes(c.Request.Context(), c.DefaultQuery("scope", "USER"))
	h.respondRows(c, rows, err)
}

func (h *Handler) featureUsage(c *gin.Context) {
	rows, err := h.svc.FeatureUsage(c.Request.Context(), c.Query("month"))
	h.respondRows(c, rows, err)
}

func (h *Handler) createQuota(c *gin.Context) {
	var q Quota
	if err := c.ShouldBindJSON(&q); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误"})
		return
	}
	result, err := h.svc.SaveQuota(c.Request.Context(), &q)
	h.respondRows(c, result, err)
}

func (h *Handler) updateQuota(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "id 需为数字"})
		return
	}
	var q Quota
	if err := c.ShouldBindJSON(&q); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误"})
		return
	}
	q.ID = &id
	result, err := h.svc.SaveQuota(c.Request.Context(), &q)
	h.respondRows(c, result, err)
}

func (h *Handler) deleteQuota(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "id 需为数字"})
		return
	}
	result, err := h.svc.DeleteQuota(c.Request.Context(), id)
	h.respondRows(c, result, err)
}

// respondRows 统一「查询失败 → 500、成功 → 200 直出」。
// Java 侧这些端点直接返回 List/Map（不是 BaseResponse 包装），这里保持一致。
func (h *Handler) respondRows(c *gin.Context, data any, err error) {
	if err != nil {
		h.log.Error("token 管理面查询失败", "path", c.Request.URL.Path, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}
	if data == nil {
		data = []any{}
	}
	c.JSON(http.StatusOK, data)
}

func (h *Handler) respondErr(c *gin.Context, err error) {
	h.log.Error("token 管理面操作失败", "path", c.Request.URL.Path, "err", err)
	c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
}

// ---- /inner/token：内核对话入口/出口的配额闸门与落账（同机直连，不经网关）----

func (h *Handler) check(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"denied": h.svc.CheckQuota(c.Request.Context(), c.Query("userId"))})
}

// chargeRequest 缺省值对齐内核的落账口径：requests>=1、用量>=0、toolCalls>=0。
type chargeRequest struct {
	UserID           *string `json:"userId"`
	AgentCode        *string `json:"agentCode"`
	Model            *string `json:"model"`
	Requests         *int    `json:"requests"`
	PromptTokens     *int64  `json:"promptTokens"`
	CompletionTokens *int64  `json:"completionTokens"`
	TotalTokens      *int64  `json:"totalTokens"`
	ToolCalls        *int    `json:"toolCalls"`
}

func (h *Handler) charge(c *gin.Context) {
	var req chargeRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误"})
		return
	}
	requests := 1
	if req.Requests != nil {
		requests = maxInt(*req.Requests, 1)
	}
	result := h.svc.Charge(c.Request.Context(),
		derefString(req.UserID), derefString(req.AgentCode), derefString(req.Model),
		requests,
		derefInt64(req.PromptTokens), derefInt64(req.CompletionTokens),
		derefInt64(req.TotalTokens), derefInt(req.ToolCalls))
	c.JSON(http.StatusOK, result)
}

func derefString(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

func derefInt64(p *int64) int64 {
	if p == nil {
		return 0
	}
	return *p
}

func derefInt(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}
