// Package gateway 是 aismp-gateway（Spring Cloud Gateway + CustomGlobalFilter）的 Go 实现。
//
// 职责与 Java 版一致：
//  1. 来源地址前缀白名单（本机 127. / 容器网 172.28.）
//  2. 三协议通道：REST → AK/SK 签名；门户/MCP → Bearer 令牌（Redis 查表映射回 ak）
//  3. 用户与接口登记解析（直连 api_db，Redis 缓存 30 分钟——替代 Java 经 /inner HTTP 查 backend）
//  4. AK/SK 通道防重放（nonce SETNX 5min）+ 时间窗（±5min）+ SHA256(body+"."+sk) 验签
//  5. 身份头重写：剥离客户端伪造的 X-User-*（/oa/** 契约除外），门户通道注入权威身份
//  6. 响应成功后计量一次（直接 HTTP 调 backend /inner 计数，替代 MQ）
//  7. 门户登录应答拦截：token → 身份 JSON 写入 Redis（24h），登出删除
//
// 与 Java 的有意偏差：计数不走 RabbitMQ（Go 栈已无消费者），直接调 backend 计数接口；
// 限流从 Spring Redis 令牌桶简化为 Redis 固定窗口（每 ak 每分钟 N 次，缺省宽松）。
package gateway

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/aismp/platform/internal/httpkit"
)

// Config 是网关的全部可调参数（对齐 application.yml + compose 环境变量）。
type Config struct {
	Addr     string
	MySQLDSN string
	Redis    struct {
		Addr     string
		Password string
	}

	BackendURL string // lb://aismp-backend → http://backend:7529（context-path /api 在路径里）
	AgentURL   string // lb://aismp-agent
	KernelURL  string // lb://aismp-digital-human
	OrchURL    string // 编排层 :8125
	MockURL    string // 兜底 /api/** → Mock :8123（登记外的路径）

	PortalAccessKey string // 门户流量记账的 ak（= backend 的 aismp.client.access-key）
	RatePerMinute   int    // 每 ak 每分钟请求数上限
	CountURL        string // 计数回调（backend /inner/userInterfaceInfo/invokeCount），空=不计数
}

// portalIdentity 对齐 CustomGlobalFilter.PortalIdentity。
// userCode 等为空 = 旧格式登记（纯 ak），只做防伪剥离不注入身份。
type portalIdentity struct {
	AK            string `json:"ak"`
	UserCode      string `json:"userCode"`
	WarehouseCode string `json:"warehouseCode"`
	Role          string `json:"role"`
}

// invokeUser 对齐 `user` 表（gateway 只用 id/secretKey/userName）。
type invokeUser struct {
	ID        int64  `json:"id"`
	UserName  string `json:"userName"`
	SecretKey string `json:"secretKey"`
}

// interfaceInfo 对齐 `interface_info` 表（gateway 只用 id/status）。
type interfaceInfo struct {
	ID     int64 `json:"id"`
	Status int   `json:"status"`
}

// Gateway 承载全部路由与过滤逻辑。
type Gateway struct {
	cfg    Config
	db     *sql.DB
	rdb    *redis.Client
	log    *slog.Logger
	proxy  *httputil.ReverseProxy
	client *http.Client
}

// New 构造网关。
func New(cfg Config, db *sql.DB, rdb *redis.Client, log *slog.Logger) *Gateway {
	g := &Gateway{cfg: cfg, db: db, rdb: rdb, log: log,
		client: &http.Client{Timeout: 30 * time.Second}}
	g.proxy = &httputil.ReverseProxy{
		// SSE：立即 flush 每个 chunk（Spring 侧 writeAndFlushWith 同款语义）
		FlushInterval: 100 * time.Millisecond,
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			log.Error("上游不可达", "target", r.URL.String(), "err", err)
			w.WriteHeader(http.StatusBadGateway)
		},
	}
	return g
}

// ---- 路由解析（对齐 application.yml 的 routes，StripPrefix 语义内联在 target 里）----

type route struct {
	prefixes []string // 命中的路径前缀（保持 /api 原样，因为 Java 路由表按转发后路径登记）
	target   string   // 上游基址
	strip    int      // 剥掉的前缀层数（按 / 分段）
}

// routes 顺序即 Spring 路由顺序（先命中先转发）。
func (g *Gateway) routes() []route {
	return []route{
		{[]string{"/api/terminal/", "/api/transferOrder/"}, g.cfg.AgentURL, 1},   // → /terminal/** /transferOrder/**
		{[]string{"/api/orchestrator/"}, g.cfg.OrchURL, 1},                      // → /orchestrator/**
		{[]string{"/api/auth/", "/api/admin/token/"}, g.cfg.BackendURL, 0},      // backend 带 context-path
		{[]string{"/api/agent/", "/api/admin/"}, g.cfg.KernelURL, 1},            // → /agent/** /admin/**
		{[]string{"/digitalHuman/"}, g.cfg.KernelURL, 1},                        // → /agent/** 等
		{[]string{"/oa/", "/sse", "/mcp"}, g.cfg.AgentURL, 0},                   // OA / MCP（内核在 agent 内）
		{[]string{"/orchestrator/"}, g.cfg.OrchURL, 0},                          // 直连形态
		{[]string{"/api/"}, g.cfg.MockURL, 0},                                   // 兜底 Mock
	}
}

// ServeHTTP 是网关唯一入口。
func (g *Gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	method := r.Method
	start := time.Now()

	// 1. 来源地址白名单（对齐 getLocalAddress 的前缀匹配）
	if local, ok := r.Context().Value(http.LocalAddrContextKey).(net.Addr); ok {
		host := localAddrHost(local)
		if !hasPrefix(host, "127.", "172.28.") {
			g.log.Warn("来源不在白名单", "local", host, "remote", r.RemoteAddr)
			w.WriteHeader(http.StatusForbidden)
			return
		}
	}

	// 2. 通道分类（对齐 doFilter 的 isPortalAuthPath / isMcpPath / isPortalPath / dual）
	isAuthPath := strings.HasPrefix(path, "/api/auth")
	isLogout := strings.HasPrefix(path, "/api/auth/logout")
	isMcp := strings.HasPrefix(path, "/sse") || strings.HasPrefix(path, "/mcp")
	isDualBiz := strings.HasPrefix(path, "/api/terminal") || strings.HasPrefix(path, "/api/transferOrder")
	isPortal := isAuthPath ||
		strings.HasPrefix(path, "/api/agent") || strings.HasPrefix(path, "/api/admin") ||
		strings.HasPrefix(path, "/api/orchestrator") || isDualBiz ||
		strings.HasPrefix(path, "/orchestrator/")

	// 登录/登出：匿名放行，响应层登记/吊销令牌
	if isAuthPath {
		g.proxyAuth(w, r, isLogout)
		g.log.Info("auth", "path", path, "status", "?", "ms", time.Since(start).Milliseconds())
		return
	}

	authorization := r.Header.Get("Authorization")
	hasBearer := strings.HasPrefix(authorization, "Bearer ")
	var identity *portalIdentity
	var accessKey string
	tokenChannel := false

	switch {
	case isMcp:
		if !hasBearer {
			g.noAuth(w)
			return
		}
		token := strings.TrimSpace(authorization[len("Bearer "):])
		ak, err := g.rdb.Get(r.Context(), "mcp:token:"+token).Result()
		if err != nil || ak == "" {
			g.noAuth(w)
			return
		}
		accessKey = ak
		tokenChannel = true
	case isPortal && (!isDualBiz || hasBearer):
		if !hasBearer {
			g.unauthorized(w, "未登录（缺少 Bearer token）")
			return
		}
		token := strings.TrimSpace(authorization[len("Bearer "):])
		raw, err := g.rdb.Get(r.Context(), "portal:token:"+token).Result()
		if err != nil || raw == "" {
			g.unauthorized(w, "登录态未在网关登记或已过期，请重新登录")
			return
		}
		identity = g.parsePortalIdentity(raw)
		accessKey = identity.AK
		tokenChannel = true
	default:
		accessKey = r.Header.Get("accessKey")
	}

	// 3. 用户解析（Redis 30min 缓存 → MySQL）
	user, err := g.findUser(r.Context(), accessKey)
	if err != nil || user == nil {
		g.noAuth(w)
		return
	}

	// 4. AK/SK 通道：nonce 防重放 + 时间窗 + 验签
	if !tokenChannel {
		nonce := r.Header.Get("nonce")
		timestamp := r.Header.Get("timestamp")
		sign := r.Header.Get("sign")
		bodyHeader := r.Header.Get("body")
		if nonce == "" || len(nonce) > 64 {
			g.noAuth(w)
			return
		}
		ok, err := g.rdb.SetNX(r.Context(),
			"nonce:"+accessKey+":"+nonce, "1", 5*time.Minute).Result()
		if err != nil || !ok {
			g.log.Info("nonce 重复，疑似重放攻击", "nonce", nonce)
			g.noAuth(w)
			return
		}
		ts, err := strconv.ParseInt(timestamp, 10, 64)
		if err != nil || time.Now().Unix()-ts >= 300 {
			g.noAuth(w)
			return
		}
		// SignUtils.getSign(body, sk)：body 为 null（GET 无 body 头）时拼 "null."+sk
		payload := bodyHeader
		if payload == "" {
			payload = "null"
		}
		if sign == "" || sign != httpkit.Sign(payload, user.SecretKey) {
			g.noAuth(w)
			return
		}
	}

	// 5. 接口登记校验（Redis 30min 缓存 → MySQL，精确 + Ant 通配）
	info, err := g.findInterface(r.Context(), path, method)
	if err != nil || info == nil {
		g.noAuth(w)
		return
	}

	// 6. 限流（每 ak 固定窗口；宽松缺省，防滥用不防业务）
	if g.cfg.RatePerMinute > 0 {
		if !g.allowRate(r.Context(), accessKey) {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
	}

	// 7. 身份头重写：/oa/** 契约路径不剥不注；其余先剥伪造头，门户通道再注入权威身份
	oaContract := strings.HasPrefix(path, "/oa/")
	out := r.Clone(r.Context())
	if !oaContract {
		out.Header.Del("X-User-Id")
		out.Header.Del("X-User-Warehouse")
		out.Header.Del("X-User-Role")
		out.Header.Del("X-Area-Code")
	}
	if !oaContract && identity != nil && identity.UserCode != "" {
		out.Header.Set("X-User-Id", identity.UserCode)
		if identity.WarehouseCode != "" {
			out.Header.Set("X-User-Warehouse", identity.WarehouseCode)
		}
		if identity.Role != "" {
			out.Header.Set("X-User-Role", identity.Role)
		}
	}

	// 8. 转发（body 原样；httputil 反代会处理 Host/X-Forwarded-*）
	target, stripped := g.resolve(path)
	u, _ := url.Parse(target + stripped)
	out.URL.Scheme = u.Scheme
	out.URL.Host = u.Host
	out.URL.Path = u.Path
	out.URL.RawQuery = r.URL.RawQuery
	out.Host = u.Host

	// 计数：响应 2xx 后一次（goroutine，不阻塞响应）
	if g.cfg.CountURL != "" {
		iid, uid := info.ID, user.ID
		countURL := fmt.Sprintf("%s?interfaceInfoId=%d&userId=%d", g.cfg.CountURL, iid, uid)
		after := func(status int) {
			if status >= 200 && status < 300 {
				go func() {
					ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
					defer cancel()
					req, _ := http.NewRequestWithContext(ctx, http.MethodPost, countURL, nil)
					resp, err := g.client.Do(req)
					if err != nil {
						g.log.Error("计数失败", "iid", iid, "err", err)
						return
					}
					resp.Body.Close()
				}()
			}
		}
		rec := &statusRecorder{ResponseWriter: w, after: after}
		g.serveProxy(rec, out)
		g.log.Info("proxy", "path", path, "status", rec.status, "ms", time.Since(start).Milliseconds())
		return
	}
	g.serveProxy(w, out)
	g.log.Info("proxy", "path", path, "ms", time.Since(start).Milliseconds())
}

// serveProxy 转发到上游。
func (g *Gateway) serveProxy(w http.ResponseWriter, r *http.Request) {
	g.proxy.ServeHTTP(w, r)
}

// resolve 按路由表求上游地址与剥离后的路径。
func (g *Gateway) resolve(path string) (base, outPath string) {
	for _, rt := range g.routes() {
		for _, p := range rt.prefixes {
			if strings.HasPrefix(path, p) {
				if rt.strip == 0 {
					return rt.target, path
				}
				// strip=N：剥掉前 N 段（/api/terminal/x → /x 当 N=1 段为 /api）
				segs := strings.SplitN(strings.TrimPrefix(path, "/"), "/", rt.strip+1)
				if len(segs) > rt.strip {
					return rt.target, "/" + segs[rt.strip]
				}
				return rt.target, "/"
			}
		}
	}
	return g.cfg.MockURL, path
}

// ---- 门户登录/登出的令牌登记 ----

// proxyAuth 匿名转发 /api/auth/**，登录成功把 token→身份 JSON 写 Redis（24h），登出删除。
// 等价 CustomGlobalFilter.handlePortalAuth + updatePortalTokenMapping。
func (g *Gateway) proxyAuth(w http.ResponseWriter, r *http.Request, isLogout bool) {
	rec := &bodyRecorder{ResponseWriter: w}
	g.serveProxy(rec, r)
	if rec.status != http.StatusOK || len(rec.body) == 0 {
		return
	}
	if isLogout {
		if auth := r.Header.Get("Authorization"); strings.HasPrefix(auth, "Bearer ") {
			g.rdb.Del(context.Background(),
				"portal:token:"+strings.TrimSpace(auth[len("Bearer "):]))
		}
		return
	}
	var resp struct {
		Token         string `json:"token"`
		UserCode      string `json:"userCode"`
		WarehouseCode string `json:"warehouseCode"`
		Role          string `json:"role"`
	}
	if err := json.Unmarshal(rec.body, &resp); err != nil || resp.Token == "" {
		return // 登录失败应答没有 token，什么都不做
	}
	identity := portalIdentity{
		AK: g.cfg.PortalAccessKey, UserCode: resp.UserCode,
		WarehouseCode: resp.WarehouseCode, Role: resp.Role,
	}
	raw, _ := json.Marshal(identity)
	g.rdb.Set(context.Background(), "portal:token:"+resp.Token, string(raw), 24*time.Hour)
	g.log.Info("门户登录登记", "userCode", identity.UserCode, "warehouse", identity.WarehouseCode)
}

// ---- 存储查询（直连 api_db；Redis 缓存 30 分钟，等价 Java 经 /inner 查 backend）----

func (g *Gateway) findUser(ctx context.Context, ak string) (*invokeUser, error) {
	if ak == "" {
		return nil, nil
	}
	key := "user:ak:" + ak
	if cached, err := g.rdb.Get(ctx, key).Result(); err == nil && cached != "" {
		var u invokeUser
		if json.Unmarshal([]byte(cached), &u) == nil && u.ID > 0 {
			return &u, nil
		}
	}
	var u invokeUser
	err := g.db.QueryRowContext(ctx,
		`SELECT id, IFNULL(userName,''), IFNULL(secretKey,'') FROM user WHERE accessKey = ? AND isDelete = 0`,
		ak).Scan(&u.ID, &u.UserName, &u.SecretKey)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if raw, err := json.Marshal(u); err == nil {
		g.rdb.Set(ctx, key, string(raw), 30*time.Minute)
	}
	return &u, nil
}

func (g *Gateway) findInterface(ctx context.Context, path, method string) (*interfaceInfo, error) {
	key := "interface:" + path + ":" + method
	if cached, err := g.rdb.Get(ctx, key).Result(); err == nil && cached != "" {
		var i interfaceInfo
		if json.Unmarshal([]byte(cached), &i) == nil && i.ID > 0 {
			return &i, nil
		}
	}
	// 精确优先
	var i interfaceInfo
	err := g.db.QueryRowContext(ctx,
		`SELECT id, IFNULL(status,0) FROM interface_info WHERE url = ? AND method = ? AND isDelete = 0`,
		path, method).Scan(&i.ID, &i.Status)
	if err == sql.ErrNoRows {
		// 通配兜底：登记含 * 的同 method 行逐条 Ant 匹配（复用 innerapi 的匹配器）
		rows, qerr := g.db.QueryContext(ctx,
			`SELECT id, IFNULL(status,0), url FROM interface_info WHERE method = ? AND url LIKE '%*%' AND isDelete = 0`,
			method)
		if qerr != nil {
			return nil, qerr
		}
		defer rows.Close()
		for rows.Next() {
			var c interfaceInfo
			var pattern string
			if err := rows.Scan(&c.ID, &c.Status, &pattern); err != nil {
				return nil, err
			}
			if antMatch(pattern, path) {
				i = c
				err = nil
				break
			}
			err = sql.ErrNoRows
		}
		if err == sql.ErrNoRows {
			return nil, nil
		}
	} else if err != nil {
		return nil, err
	}
	if raw, jerr := json.Marshal(i); jerr == nil {
		g.rdb.Set(ctx, key, string(raw), 30*time.Minute)
	}
	return &i, nil
}

func (g *Gateway) allowRate(ctx context.Context, ak string) bool {
	if ak == "" {
		ak = "anonymous"
	}
	key := fmt.Sprintf("ratelimit:%s:%d", ak, time.Now().Unix()/60)
	n, err := g.rdb.Incr(ctx, key).Result()
	if err != nil {
		return true // Redis 故障不挡流量
	}
	if n == 1 {
		g.rdb.Expire(ctx, key, 2*time.Minute)
	}
	return n <= int64(g.cfg.RatePerMinute)
}

func (g *Gateway) parsePortalIdentity(raw string) *portalIdentity {
	if raw == "" {
		return nil
	}
	if !strings.HasPrefix(raw, "{") {
		return &portalIdentity{AK: raw} // 旧格式：纯 ak
	}
	var id portalIdentity
	if err := json.Unmarshal([]byte(raw), &id); err != nil {
		return &portalIdentity{AK: raw}
	}
	if id.AK == "" {
		id.AK = g.cfg.PortalAccessKey
	}
	return &id
}

// ---- 错误应答（形状与 Java 完全一致）----

func (g *Gateway) noAuth(w http.ResponseWriter) {
	w.WriteHeader(http.StatusForbidden)
}

func (g *Gateway) unauthorized(w http.ResponseWriter, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusUnauthorized)
	fmt.Fprintf(w, `{"error":%q,"status":401}`, message)
}

// ---- 小工具 ----

// statusRecorder 记录状态码并在写头后触发回调（计量一次）。
type statusRecorder struct {
	http.ResponseWriter
	status    int
	after     func(status int)
	committed bool
}

func (s *statusRecorder) WriteHeader(code int) {
	if !s.committed {
		s.status = code
		s.committed = true
		if s.after != nil {
			s.after(code)
		}
	}
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Flush() {
	if f, ok := s.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

// bodyRecorder 捕获响应体（登录应答很小，整段缓冲安全）。
type bodyRecorder struct {
	http.ResponseWriter
	status int
	body   []byte
}

func (b *bodyRecorder) WriteHeader(code int) {
	if b.status == 0 {
		b.status = code
	}
	b.ResponseWriter.WriteHeader(code)
}

func (b *bodyRecorder) Write(p []byte) (int, error) {
	b.body = append(b.body, p...)
	return b.ResponseWriter.Write(p)
}

// antMatch Ant 风格匹配（* 任意串 / ** 跨段 / ? 单字符），与 innerapi 同算法。
func antMatch(pattern, path string) bool {
	return matchSegs(strings.Split(pattern, "/"), strings.Split(path, "/"))
}

func matchSegs(pattern, path []string) bool {
	switch {
	case len(pattern) == 0:
		return len(path) == 0
	case pattern[0] == "**":
		for i := 0; i <= len(path); i++ {
			if matchSegs(pattern[1:], path[i:]) {
				return true
			}
		}
		return false
	case len(path) == 0:
		return false
	case matchRunes(pattern[0], path[0]):
		return matchSegs(pattern[1:], path[1:])
	default:
		return false
	}
}

func matchRunes(pattern, s string) bool {
	if pattern == "" {
		return s == ""
	}
	switch pattern[0] {
	case '*':
		for i := 0; i <= len(s); i++ {
			if matchRunes(pattern[1:], s[i:]) {
				return true
			}
		}
		return false
	case '?':
		return s != "" && matchRunes(pattern[1:], s[1:])
	default:
		return s != "" && pattern[0] == s[0] && matchRunes(pattern[1:], s[1:])
	}
}

func localAddrHost(a net.Addr) string {
	host, _, err := net.SplitHostPort(a.String())
	if err != nil {
		return a.String()
	}
	return host
}

func hasPrefix(s string, prefixes ...string) bool {
	for _, p := range prefixes {
		if strings.HasPrefix(s, p) {
			return true
		}
	}
	return false
}
