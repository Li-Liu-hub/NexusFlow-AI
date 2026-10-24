// Package dhproxy 实现管理端数字员工配置的代理出口：
// 管理端前端/运维脚本 → backend（登录态鉴权）→ 网关（AK/SK 签名）→ 数字员工内核。
//
// 移植自 DigitalHumanAdminProxyController。存在理由：内核管理接口（/admin/**）在网关走
// AK/SK 鉴权，而 AK/SK 是发给第三方开发者的长期凭证，不能放进浏览器；
// 管理端正确的鉴权模型是「session 登录态 + admin 角色」，凭证只留在服务端。
//
// 路径映射：前端调 /api/admin/digitalHuman/agent/list → 网关 /digitalHuman/admin/agent/list。
package dhproxy

import (
	"bytes"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
	"github.com/aismp/platform/internal/platform/market"
)

// LocalPrefix 是本控制器映射的前端路径前缀。
const LocalPrefix = "/admin/digitalHuman"

// Config 是代理出口的运行参数（对应 application.yml 的 aismp.* 配置）。
type Config struct {
	GatewayBaseURL    string // aismp.gateway.base-url
	GatewayPrefix     string // aismp.gateway.digital-human-prefix
	DigitalHumanURL   string // aismp.digital-human.base-url（仅服务账号登录用）
	AccessKey         string // aismp.client.access-key
	SecretKey         string // aismp.client.secret-key
}

// svcLogin 是内核服务账号（编排/代理共用的 SERVICE 账号）。
const (
	svcUserCode = "svc_orchestrator"
	svcPassword = "svc-orch-2026"
)

// Handler 承载 /admin/digitalHuman/** 的统一转发。
type Handler struct {
	cfg        Config
	repo       *market.Repository
	session    *market.Session
	log        *slog.Logger
	client     *http.Client
	mu         sync.Mutex
	svcToken   string
	svcFetched time.Time
}

// NewHandler 构造代理处理器。
func NewHandler(cfg Config, repo *market.Repository, session *market.Session, log *slog.Logger) *Handler {
	return &Handler{
		cfg: cfg, repo: repo, session: session, log: log,
		client: &http.Client{Timeout: 60 * time.Second},
	}
}

// Register 挂载通配转发入口（GET/POST/PUT/DELETE 全走同一逻辑）。
func (h *Handler) Register(r gin.IRouter) {
	r.Any("/admin/digitalHuman", h.proxy)
	r.Any("/admin/digitalHuman/*rest", h.proxy)
}

// proxy 统一转发入口。
func (h *Handler) proxy(c *gin.Context) {
	// 登录态 + 管理员角色校验：前端只需带 session，AK/SK 始终留在服务端。
	user, ok := h.currentUser(c)
	if !ok || user == nil {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNotLogin))
		return
	}
	if user.UserRole == nil || *user.UserRole != market.AdminRole {
		c.JSON(http.StatusOK, httpkit.Fail(httpkit.CodeNoAuth))
		return
	}

	uri := c.Request.URL.Path // gin 已去掉 context-path /api，等价 getServletPath()
	subPath := ""
	if len(uri) > len(LocalPrefix) {
		subPath = uri[len(LocalPrefix):]
	}
	target := h.cfg.GatewayBaseURL + h.cfg.GatewayPrefix + subPath
	if q := c.Request.URL.RawQuery; q != "" {
		target += "?" + q
	}

	body, _ := io.ReadAll(c.Request.Body)
	payload := ""
	if len(bytes.TrimSpace(body)) > 0 {
		// header 只允许 ASCII：中文塞进 body 头会乱码 → 签名不匹配 → 403。
		// Unicode 转义在 JSON 中合法，与编排层 Python 侧 ensure_ascii=True 一致。
		payload = toASCIIJSON(string(body))
	}

	status, contentType, data, err := h.exchange(c.Request.Method, target, payload)
	if err != nil {
		h.log.Error("代理转发失败", "target", target, "err", err)
		c.Data(http.StatusBadGateway, "application/json",
			[]byte(`{"error":"网关不可达","message":"`+sanitize(err.Error())+`"}`))
		return
	}

	// 服务令牌过期：清缓存重登一次再转发（只重试一轮，避免死循环）。
	if status == http.StatusUnauthorized {
		h.mu.Lock()
		h.svcToken = ""
		h.mu.Unlock()
		if s2, ct2, d2, err2 := h.exchange(c.Request.Method, target, payload); err2 == nil {
			status, contentType, data = s2, ct2, d2
		}
	}

	// 透传网关的状态码与响应体，前端不必为「经代理」适配另一套格式。
	if contentType == "" {
		contentType = "application/json"
	}
	c.Data(status, contentType, data)
}

// exchange 完成一次「签名转发 + 读响应」，无遗留状态。
func (h *Handler) exchange(method, target, payload string) (int, string, []byte, error) {
	resp, err := h.forward(method, target, payload)
	if err != nil {
		return 0, "", nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, "", nil, err
	}
	return resp.StatusCode, resp.Header.Get("Content-Type"), data, nil
}

// forward 发起一次对网关的签名转发。
func (h *Handler) forward(method, target, payload string) (*http.Response, error) {
	var bodyReader io.Reader
	if payload != "" {
		bodyReader = strings.NewReader(payload)
	}
	req, err := http.NewRequest(strings.ToUpper(method), target, bodyReader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("accessKey", h.cfg.AccessKey)
	req.Header.Set("nonce", randomHex32())
	req.Header.Set("timestamp", fmt.Sprintf("%d", time.Now().Unix()))
	signPayload := payload
	if signPayload == "" {
		signPayload = "null" // Java：body 为 null 时拼 "null." + sk，网关对 GET 同款口径
	}
	req.Header.Set("sign", market.Sign(signPayload, h.cfg.SecretKey))
	if payload != "" {
		req.Header.Set("body", payload)
	}
	if token := h.serviceToken(); token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	return h.client.Do(req)
}

// serviceToken 用 SERVICE 账号在内核换 Bearer 令牌，缓存到进程内。
// 登录失败不阻塞转发——内核侧给 401，问题在下一次调用时暴露（与 Java 一致）。
func (h *Handler) serviceToken() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.svcToken != "" && time.Since(h.svcFetched) < 23*time.Hour {
		return h.svcToken
	}
	loginBody := fmt.Sprintf(`{"userCode":%q,"password":%q}`, svcUserCode, svcPassword)
	req, err := http.NewRequest(http.MethodPost, h.cfg.DigitalHumanURL+"/auth/login",
		strings.NewReader(loginBody))
	if err != nil {
		return ""
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := h.client.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return ""
	}
	// 内核登录响应是平铺 JSON，直接找 "token" 字段（不引 JSON 依赖也稳）。
	token := extractJSONString(string(data), "token")
	if token == "" {
		return ""
	}
	h.svcToken = token
	h.svcFetched = time.Now()
	return h.svcToken
}

// currentUser 与 market.currentUser 同口径：session 里的 id 回查数据库。
func (h *Handler) currentUser(c *gin.Context) (*market.User, bool) {
	sid, err := c.Cookie(market.CookieName)
	if err != nil || sid == "" {
		return nil, false
	}
	su, ok := h.session.Get(sid)
	if !ok {
		return nil, false
	}
	u, err := h.repo.UserGetByID(c.Request.Context(), su.ID)
	if err != nil {
		return nil, false
	}
	return u, true
}

// toASCIIJson 把 JSON 里的非 ASCII 字符转成 \uXXXX 转义（不动 JSON 结构字符）。
func toASCIIJSON(s string) string {
	var b strings.Builder
	b.Grow(len(s) + 32)
	for _, r := range s {
		if r < 0x80 {
			b.WriteByte(byte(r))
		} else {
			fmt.Fprintf(&b, "\\u%04x", r)
		}
	}
	return b.String()
}

// extractJSONString 从平铺 JSON 里取顶层字符串字段（够用即可，避免为两个字段引依赖）。
func extractJSONString(body, field string) string {
	key := "\"" + field + "\""
	idx := strings.Index(body, key)
	if idx < 0 {
		return ""
	}
	rest := body[idx+len(key):]
	start := strings.Index(rest, "\"")
	if start < 0 {
		return ""
	}
	rest = rest[start+1:]
	end := strings.Index(rest, "\"")
	if end < 0 {
		return ""
	}
	return rest[:end]
}

// sanitize 防止错误消息里的引号破坏 JSON。
func sanitize(s string) string {
	r := strings.NewReplacer(`"`, `'`, "\\", "/", "\n", " ", "\r", "")
	return r.Replace(s)
}

// randomHex32 生成 32 位 hex 随机串（对齐网关侧高熵 nonce 要求）。
func randomHex32() string { return market.RandomHex32() }
