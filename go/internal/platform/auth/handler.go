// Package auth 实现门户登录鉴权。
//
// 移植自 aismp-backend com.aismp.project.auth.AuthController（2026-10-06 批 A 从内核迁来）。
// 三条路径：登录换令牌、查当前身份、登出吊销。
//
//	POST /api/auth/login   {userCode, password} → {token, userCode, userName, role, roleLabel, warehouse…}
//	GET  /api/auth/me      (Bearer) → 当前登录身份
//	POST /api/auth/logout  (Bearer) → 吊销
//
// 令牌是不透明 UUID，落在 a_auth_token，24 小时有效。
// 网关侧约定：/api/auth/** 匿名放行；登录成功时网关把 portal:token:<token> → ak 写进 Redis 做计量归属。
package auth

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
	"github.com/aismp/platform/internal/platform/user"
)

// TokenTTL 令牌有效期，对应 Java 侧 TOKEN_HOURS = 24。
const TokenTTL = 24 * time.Hour

// Repository 是 Handler 需要的存储能力。
//
// 由使用方（本包）定义接口、由 user.Repository 实现——这是 Go 的惯例，
// 也让 Handler 的单测不需要真库。
type Repository interface {
	FindByCode(ctx context.Context, userCode string) (*user.User, error)
	IssueToken(ctx context.Context, userCode, role string, ttl time.Duration) (string, error)
	FindToken(ctx context.Context, token string) (user.TokenPrincipal, bool, error)
	DeleteToken(ctx context.Context, token string) (int64, error)
	PurgeExpired(ctx context.Context, userCode string) error
}

// Handler 承载 /auth 下的三个端点。
type Handler struct {
	repo Repository
	log  *slog.Logger
	ttl  time.Duration
}

// NewHandler 构造鉴权处理器。
func NewHandler(repo Repository, log *slog.Logger) *Handler {
	return &Handler{repo: repo, log: log, ttl: TokenTTL}
}

// Register 把路由挂到 /auth 前缀下（等价于 @RequestMapping("/auth")）。
func (h *Handler) Register(r gin.IRouter) {
	g := r.Group("/auth")
	g.POST("/login", h.login)
	g.GET("/me", h.me)
	g.POST("/logout", h.logout)
}

// loginRequest 用指针字段，以便区分「字段缺失」与「传了空串」——
// Java 侧 record 反序列化后字段可能为 null，两者都判 400。
type loginRequest struct {
	UserCode *string `json:"userCode"`
	Password *string `json:"password"`
}

// loginResponse 字段顺序刻意与 Java 的 LinkedHashMap 一致，便于人工 diff。
type loginResponse struct {
	Token         string  `json:"token"`
	UserCode      string  `json:"userCode"`
	UserName      string  `json:"userName"`
	Role          string  `json:"role"`
	RoleLabel     string  `json:"roleLabel"`
	WarehouseCode *string `json:"warehouseCode"`
	WarehouseName *string `json:"warehouseName"`
	ExpiresInHours int    `json:"expiresInHours"`
}

// login 登录。
//
// 状态码语义（对齐 7655013「登录失败改回真实 HTTP 状态码」）：
//   - 400 缺 userCode / password
//   - 401 账号不存在或密码错误（不区分，避免枚举账号）
//   - 403 账号已停用
//
// body 形状恒定是 {"error": "..."}，前端 if (!resp.ok || data.error) 判失败，改状态码不影响展示。
func (h *Handler) login(c *gin.Context) {
	var req loginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求体格式错误，需为 JSON"})
		return
	}
	if req.UserCode == nil || strings.TrimSpace(*req.UserCode) == "" ||
		req.Password == nil || strings.TrimSpace(*req.Password) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "userCode 和 password 不能为空"})
		return
	}

	code := strings.TrimSpace(*req.UserCode)
	u, err := h.repo.FindByCode(c.Request.Context(), code)
	if errors.Is(err, user.ErrNotFound) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "账号或密码错误"})
		return
	}
	if err != nil {
		h.log.Error("登录查账号失败", "user_code", code, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}
	if !strings.EqualFold(SHA256Hex(*req.Password), u.PasswordHash) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "账号或密码错误"})
		return
	}
	if u.Status != user.StatusActive {
		c.JSON(http.StatusForbidden, gin.H{"error": "账号已停用"})
		return
	}

	// 顺手清理该账号的过期令牌。Java 侧这里若失败会直接 500；
	// Go 版故意降级为告警——清理失败不该挡住本人登录（本轮唯一的主动偏差，见 README）。
	if err := h.repo.PurgeExpired(c.Request.Context(), u.UserCode); err != nil {
		h.log.Warn("清理过期令牌失败，继续登录", "user_code", u.UserCode, "err", err)
	}

	token, err := h.repo.IssueToken(c.Request.Context(), u.UserCode, u.Role, h.ttl)
	if err != nil {
		h.log.Error("签发令牌失败", "user_code", u.UserCode, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}

	c.JSON(http.StatusOK, loginResponse{
		Token:          token,
		UserCode:       u.UserCode,
		UserName:       u.UserName,
		Role:           u.Role,
		RoleLabel:      u.RoleLabel(),
		WarehouseCode:  u.WarehouseCode,
		WarehouseName:  u.WarehouseName,
		ExpiresInHours: int(h.ttl.Hours()),
	})
}

// meResponse 对应 Java 的 /auth/me 返回体。
type meResponse struct {
	UserCode      string  `json:"userCode"`
	Role          string  `json:"role"`
	UserName      string  `json:"userName"`
	RoleLabel     string  `json:"roleLabel"`
	WarehouseCode *string `json:"warehouseCode"`
	WarehouseName *string `json:"warehouseName"`
}

// me 返回当前登录身份。
//
// 注意：Java 版这里未登录也返回 HTTP 200 + {"error":"未登录"}（与 login 的修正不一致）。
// 本移植**照抄这个行为**，等前端两个入口统一按状态码判断后再一起改，
// 否则单独改这里会让门户的静默降级逻辑失效。
func (h *Handler) me(c *gin.Context) {
	token, ok := httpkit.BearerToken(c)
	if !ok {
		c.JSON(http.StatusOK, gin.H{"error": "未登录"})
		return
	}
	p, valid, err := h.repo.FindToken(c.Request.Context(), token)
	if err != nil {
		h.log.Error("校验令牌失败", "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}
	if !valid {
		c.JSON(http.StatusOK, gin.H{"error": "未登录"})
		return
	}

	// 账号可能已被删除，但令牌还在有效期——此时按令牌里的身份返回，用户名退化为 userCode。
	u, err := h.repo.FindByCode(c.Request.Context(), p.UserCode)
	if err != nil && !errors.Is(err, user.ErrNotFound) {
		h.log.Error("查账号失败", "user_code", p.UserCode, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}

	if u == nil {
		c.JSON(http.StatusOK, meResponse{
			UserCode:  p.UserCode,
			Role:      p.Role,
			UserName:  p.UserCode,
			RoleLabel: "",
		})
		return
	}
	c.JSON(http.StatusOK, meResponse{
		UserCode:      p.UserCode,
		Role:          p.Role,
		UserName:      u.UserName,
		RoleLabel:     u.RoleLabel(),
		WarehouseCode: u.WarehouseCode,
		WarehouseName: u.WarehouseName,
	})
}

// logout 吊销当前令牌。无论成功与否都返回 200，body 里用 loggedOut 表达结果。
func (h *Handler) logout(c *gin.Context) {
	token, ok := httpkit.BearerToken(c)
	if !ok {
		c.JSON(http.StatusOK, gin.H{"loggedOut": false})
		return
	}
	removed, err := h.repo.DeleteToken(c.Request.Context(), token)
	if err != nil {
		h.log.Error("吊销令牌失败", "err", err)
		c.JSON(http.StatusOK, gin.H{"loggedOut": false})
		return
	}
	c.JSON(http.StatusOK, gin.H{"loggedOut": removed > 0})
}

// SHA256Hex 计算 SHA-256 的小写十六进制串。
//
// 与 a_user.password_hash 的生成方式对齐（迁移脚本里的 SHA2(password, 256)，
// 演示账号口令 123456 → 8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92）。
func SHA256Hex(input string) string {
	sum := sha256.Sum256([]byte(input))
	return hex.EncodeToString(sum[:])
}
