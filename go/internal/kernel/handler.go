// HTTP 层：对话（同步 + SSE 流式）、会话管理、管理台 CRUD、审计、健康探针。
// 路由对齐 AgentChatController / AgentAdminController / SkillAdminController /
// ToolAdminController / KbAdminController / McpAdminController / AuditController。
package kernel

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"
)

// Engine 组合全部依赖。
type Engine struct {
	Cfg      Config
	Terminal *Store
	Auth     *sql.DB   // api_db（a_user / a_auth_token）
	API      *sql.DB   // SQL 工具业务库
	PG       *sql.DB // terminal_kb（vector_store）
	Registry *Registry
	LLM      *LLM
	Log      *slog.Logger
	HTTP     *http.Client
}

// Principal 是鉴权后的身份（对齐 UserPrincipal）。
type Principal struct {
	UserCode string
	Role     string
	Service  bool
}

// roleOf 判断是否 SERVICE。
func roleOf(role string) Principal {
	return Principal{UserCode: "", Role: role, Service: role == "SERVICE"}
}

// ---- 鉴权中间件（对齐 AuthInterceptor：Bearer → a_auth_token（api_db）→ 角色）----

func (e *Engine) auth(c *gin.Context) {
	header := c.GetHeader("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "未登录（缺少 Bearer token）", "status": 401})
		return
	}
	token := strings.TrimSpace(header[len("Bearer "):])
	var userCode, role string
	err := e.Auth.QueryRowContext(c.Request.Context(),
		`SELECT user_code, role FROM a_auth_token WHERE token = ? AND expires_at > NOW()`,
		token).Scan(&userCode, &role)
	if err != nil {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "登录已过期或 token 无效，请重新登录", "status": 401})
		return
	}
	p := Principal{UserCode: userCode, Role: role, Service: role == "SERVICE"}
	c.Set("principal", p)
}

// principalOf 取当前身份。
func principalOf(c *gin.Context) *Principal {
	if v, ok := c.Get("principal"); ok {
		if p, ok := v.(Principal); ok {
			return &p
		}
	}
	return nil
}

// resolveUserId 对齐：真人以登录身份为准；SERVICE 可代传。
func resolveUserId(c *gin.Context, claimed string) string {
	p := principalOf(c)
	if p == nil {
		return claimed
	}
	if p.Service && claimed != "" {
		return claimed
	}
	return p.UserCode
}

// withDataScope 把数据权限片段拼到提问前（对齐 AgentChatController.withDataScope）。
func withDataScope(message string, scope []string) string {
	if len(scope) == 0 {
		return message
	}
	return "【数据权限】当前用户只能访问以下仓位的数据：" + strings.Join(scope, "、") +
		"。涉及库存数字/报表/分布的问题，只能在这个范围内取数回答；" +
		"调用库存查询工具时把范围作为筛选条件（单仓直接用它），" +
		"范围外仓位的任何库存数据不得出现在回答中。\n\n" + message
}

// ---- 配额闸门（HTTP 调 backend /inner/token/check）----

func (e *Engine) checkQuota(ctx context.Context, userID string) string {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet,
		strings.TrimSuffix(e.Cfg.BackendBaseURL, "/")+"/inner/token/check?userId="+urlEscape(userID), nil)
	resp, err := e.HTTP.Do(req)
	if err != nil {
		return "" // 失败默认放行
	}
	defer resp.Body.Close()
	var out struct {
		Denied string `json:"denied"`
	}
	if json.NewDecoder(resp.Body).Decode(&out) == nil {
		return out.Denied
	}
	return ""
}

// chargeQuota 落账（HTTP 调 backend /inner/token/charge）。
func (e *Engine) chargeQuota(userID, agentCode, model string, prompt, completion, total int64) {
	payload, _ := json.Marshal(map[string]any{
		"userId": userID, "agentCode": agentCode, "model": model,
		"promptTokens": prompt, "completionTokens": completion, "totalTokens": total,
	})
	req, _ := http.NewRequest(http.MethodPost,
		strings.TrimSuffix(e.Cfg.BackendBaseURL, "/")+"/inner/token/charge",
		strings.NewReader(string(payload)))
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.HTTP.Do(req)
	if err == nil {
		resp.Body.Close()
	}
}

// ---- 对话 ----

type chatRequest struct {
	Message        string   `json:"message"`
	ConversationID string   `json:"conversationId"`
	UserID         string   `json:"userId"`
	DataScope      []string `json:"dataScope"`
}

// runChat 执行一轮对话（同步），对齐 AgentChatController.chat。
func (e *Engine) runChat(c *gin.Context, agentCode string, req chatRequest) map[string]any {
	start := time.Now()
	asm, err := e.Registry.Get(c.Request.Context(), agentCode)
	if err != nil || asm == nil {
		return map[string]any{"error": "数字员工不可用（配置不存在或已停用）: " + agentCode}
	}
	if req.Message == "" {
		return map[string]any{"error": "message 不能为空"}
	}
	userID := resolveUserId(c, req.UserID)
	traceID := newUUID()
	convID := req.ConversationID
	if convID == "" {
		convID = newUUID()
	}
	if denied := e.checkQuota(c.Request.Context(), userID); denied != "" {
		return map[string]any{"error": denied, "quotaDenied": true}
	}

	history := e.loadHistory(c.Request.Context(), agentCode, convID)
	e.safePersist(c.Request.Context(), func() {
		e.Terminal.EnsureConversation(c.Request.Context(), agentCode, convID, "", userID, "auto", req.Message)
		e.Terminal.AppendMessage(c.Request.Context(), agentCode, convID, userID, "user", req.Message,
			"auto", ns2(asm.Agent.ModelName), 0, "success", "")
	})

	answer := e.executeLoop(c.Request.Context(), asm, tc{
		TraceID: traceID, ConversationID: convID, UserID: userID,
		AgentCode: agentCode, DataScope: req.DataScope,
	}, withDataScope(req.Message, req.DataScope), history, nil)

	elapsed := time.Since(start).Milliseconds()
	if answer.Err != "" {
		e.safePersist(c.Request.Context(), func() {
			e.Terminal.AppendMessage(c.Request.Context(), agentCode, convID, userID,
				"assistant", "", "auto", ns2(asm.Agent.ModelName), int(elapsed), "error", answer.Err)
		})
		return map[string]any{
			"agentCode": agentCode, "version": asm.Agent.Version, "traceId": traceID,
			"conversationId": convID, "historyTurns": 0,
			"error": answer.Err, "elapsedMs": elapsed,
		}
	}
	e.safePersist(c.Request.Context(), func() {
		e.Terminal.AppendMessage(c.Request.Context(), agentCode, convID, userID,
			"assistant", answer.Text, "auto", ns2(asm.Agent.ModelName), int(elapsed), "success", "")
	})
	e.chargeQuota(userID, agentCode, ns2(asm.Agent.ModelName), answer.promptTotal, answer.completionTotal, answer.promptTotal+answer.completionTotal)
	return map[string]any{
		"agentCode": agentCode, "version": asm.Agent.Version, "traceId": traceID,
		"conversationId": convID, "historyTurns": len(history),
		"answer": answer.Text, "elapsedMs": elapsed,
		"tokenUsage": map[string]any{"promptTokens": answer.promptTotal, "completionTokens": answer.completionTotal, "totalTokens": answer.promptTotal + answer.completionTotal},
	}
}

// llmAnswer 是一轮循环的产出。
type llmAnswer struct {
	Text       string
	Err        string
	promptTotal     int64
	completionTotal int64
}

// tc 工具上下文构造参数。
type tc struct {
	TraceID        string
	ConversationID string
	UserID         string
	AgentCode      string
	DataScope      []string
}

// executeLoop ReactAgent 工具循环（对齐 ReactAgent 的行为面）。
// onEvent 非 nil 时按 SSE 语义回调（"delta" 文本 / "tool" 工具回执）。
func (e *Engine) executeLoop(ctx context.Context, asm *Assembled, t tc,
	message string, history [][2]string, onEvent func(phase, text string) error) llmAnswer {

	model := ns2(asm.Agent.ModelName)
	if model == "" {
		model = e.Cfg.LLMModel
	}
	temp := 0.7
	if asm.Agent.Temperature != nil {
		temp = *asm.Agent.Temperature
	}
	maxTurns := 8
	if asm.Agent.MaxTurns != nil && *asm.Agent.MaxTurns > 0 {
		maxTurns = *asm.Agent.MaxTurns
	}
	toolCtx := &ToolContext{
		Terminal: e.Terminal, API: e.API, Tenant: ns2(asm.Agent.TenantCode),
		TraceID: t.TraceID, ConversationID: t.ConversationID, UserID: t.UserID, AgentCode: t.AgentCode,
		GatewayBaseURL: e.Cfg.GatewayBaseURL, GatewayAK: e.Cfg.GatewayAK, GatewaySK: e.Cfg.GatewaySK,
		AgentBaseURL: e.Cfg.AgentBaseURL, HTTPClient: e.HTTP,
	}
	messages := []Message{{Role: "system", Content: asm.SystemPrompt}}
	for _, h := range history {
		messages = append(messages, Message{Role: h[0], Content: h[1]})
	}
	messages = append(messages, Message{Role: "user", Content: message})
	tools := ToolDefsToOpenAI(asm.Tools)
	toolByName := map[string]*ToolDef{}
	for _, d := range asm.Tools {
		toolByName[d.ToolName] = d
	}

	var full strings.Builder
	var promptTotal, completionTotal int64
	for turn := 0; turn < maxTurns; turn++ {
		var toolCalls []ToolCall
		var prompt, completion int64
		var err error
		prompt, completion, err = e.LLM.StreamChat(ctx, model, temp, messages, tools,
			func(delta string) error {
				full.WriteString(delta)
				if onEvent != nil {
					return onEvent("delta", delta)
				}
				return nil
			},
			func(call ToolCall) error {
				toolCalls = append(toolCalls, call)
				return nil
			})
		promptTotal += prompt
		completionTotal += completion
		if err != nil {
			return llmAnswer{Err: err.Error()}
		}
		if len(toolCalls) == 0 {
			break
		}
		// 把 assistant 的工具调用请求与执行结果回填对话
		assistantMsg := Message{Role: "assistant", Content: full.String(), ToolCalls: toolCalls}
		if full.Len() == 0 {
			assistantMsg.Content = ""
		}
		messages = append(messages, assistantMsg)
		for _, call := range toolCalls {
			def := toolByName[call.Function.Name]
			var result string
			if def == nil {
				result = fmt.Sprintf(`{"error":"未挂载的工具: %s"}`, call.Function.Name)
			} else {
				result = Execute(ctx, toolCtx, def, call.Function.Arguments)
				if onEvent != nil {
					receipt := call.Function.Name + " → " + truncate(result, 400)
					if err := onEvent("tool", receipt); err != nil {
						return llmAnswer{Err: err.Error()}
					}
				}
			}
			messages = append(messages, Message{Role: "tool", ToolCallID: call.ID,
				Name: call.Function.Name, Content: result})
		}
	}
	return llmAnswer{Text: full.String(), promptTotal: promptTotal, completionTotal: completionTotal}
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

func (e *Engine) loadHistory(ctx context.Context, agentCode, convID string) [][2]string {
	pairs, err := e.Terminal.HistoryTurns(ctx, agentCode, convID, 20)
	if err != nil {
		e.Log.Warn("加载会话历史失败，本轮按无上下文处理", "err", err)
		return nil
	}
	return pairs
}

func (e *Engine) safePersist(ctx context.Context, fn func()) {
	defer func() { _ = recover() }()
	fn()
}

// ---- SSE 流式（GET /{code}/chat/stream）----

var sseMu sync.Mutex // emitter 写串行化（单请求内本就串行，这里防全局并发写乱序无必要，保留局部使用）

func (e *Engine) sseChat(c *gin.Context) {
	agentCode := c.Param("agentCode")
	message := c.Query("message")
	convID := c.Query("conversationId")
	if convID == "" {
		convID = newUUID()
	}
	userID := resolveUserId(c, c.Query("userId"))

	c.Writer.Header().Set("Content-Type", "text/event-stream")
	c.Writer.Header().Set("Cache-Control", "no-cache")
	c.Writer.Header().Set("Connection", "keep-alive")
	flusher, _ := c.Writer.(http.Flusher)

	send := func(event, data string) {
		sseMu.Lock()
		defer sseMu.Unlock()
		fmt.Fprintf(c.Writer, "event: %s\ndata: %s\n\n", event, strings.ReplaceAll(data, "\n", "\ndata: "))
		if flusher != nil {
			flusher.Flush()
		}
	}

	start := time.Now()
	traceID := newUUID()
	if denied := e.checkQuota(c.Request.Context(), userID); denied != "" {
		send("error", denied)
		return
	}
	asm, err := e.Registry.Get(c.Request.Context(), agentCode)
	if err != nil || asm == nil {
		send("error", "数字员工不可用: "+agentCode)
		return
	}
	history := e.loadHistory(c.Request.Context(), agentCode, convID)
	send("meta", fmt.Sprintf(`{"version":%d,"conversationId":%q,"historyTurns":%d,"modelName":%q}`,
		derefI(asm.Agent.Version), convID, len(history), ns2(asm.Agent.ModelName)))
	e.safePersist(c.Request.Context(), func() {
		e.Terminal.EnsureConversation(c.Request.Context(), agentCode, convID, "", userID, "auto", message)
		e.Terminal.AppendMessage(c.Request.Context(), agentCode, convID, userID, "user", message,
			"auto", ns2(asm.Agent.ModelName), 0, "success", "")
	})
	answer := e.executeLoop(c.Request.Context(), asm, tc{
		TraceID: traceID, ConversationID: convID, UserID: userID, AgentCode: agentCode,
	}, message, history, func(phase, text string) error {
		send(phase, text)
		return nil
	})
	elapsed := time.Since(start).Milliseconds()
	if answer.Err != "" {
		send("error", answer.Err)
		return
	}
	e.safePersist(c.Request.Context(), func() {
		e.Terminal.AppendMessage(c.Request.Context(), agentCode, convID, userID,
			"assistant", answer.Text, "auto", ns2(asm.Agent.ModelName), int(elapsed), "success", "")
	})
	send("done", fmt.Sprintf(`{"elapsedMs":%d,"traceId":%q,"agentCode":%q,"totalTokens":%d}`,
		elapsed, traceID, agentCode, answer.promptTotal+answer.completionTotal))
}

// derefI 指针解引用。
func derefI(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}

// ---- 路由 ----

// Register 挂全部路由（无 context-path，网关已剥 /api 与 /digitalHuman 段）。
func (e *Engine) Register(r gin.IRouter) {
	// 内核自己的登录（svc 账号，api_db）
	r.POST("/auth/login", e.kernelLogin)

	g := r.Group("/agent")
	g.Use(e.auth)
	g.POST("/:agentCode/chat", func(c *gin.Context) {
		var req chatRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusOK, gin.H{"error": "message 不能为空"})
			return
		}
		c.JSON(http.StatusOK, e.runChat(c, c.Param("agentCode"), req))
	})
	g.GET("/:agentCode/chat/stream", e.sseChat)
	g.GET("/:agentCode/introspect", e.introspect)
	g.GET("/agents-for-role", e.agentsForRole)
	g.GET("/conversation/list", e.conversationList)
	g.GET("/conversation/:conversationId", e.conversationDetail)
	g.POST("/conversation/:conversationId/archive", e.conversationArchive)
	g.GET("/conversation/stats", e.conversationStats)
	g.GET("/conversation/stats/by-agent", e.conversationStatsByAgent)

	// 管理面（对齐 *AdminController）
	a := r.Group("/admin", e.auth)
	a.GET("/agent/list", e.adminAgentList)
	a.GET("/agent/catalog/skills", e.adminSkillList)
	a.GET("/agent/catalog/tools", e.adminToolList)
	a.GET("/agent/catalog/roles", e.adminRoleList)
	a.GET("/agent/catalog/kbs", e.adminKbList)
	a.GET("/agent/catalog/kbs/:kbId/docs", e.adminKbDocs)
	a.POST("/agent/:code/reload", e.adminAgentReload)
	a.GET("/agent/runtime/cache", e.adminRuntimeCache)
	a.GET("/audit/recent", e.auditRecent)
	a.GET("/audit/stats", e.auditStats)
	a.GET("/tool/list", e.adminToolList)
	a.GET("/tool/db-schema", e.adminDBSchema)
	a.GET("/tool/template", e.adminToolTemplate)
}

// urlEscape query 值转义。
func urlEscape(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r == ' ' {
			b.WriteString("%20")
		} else if r == '&' || r == '=' || r == '?' || r == '#' {
			fmt.Fprintf(&b, "%%%02X", r)
		} else {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// jsonString JSON 字符串转义。
func jsonString(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// handlersJSON 通用响应。
func handlersJSON(c *gin.Context, data any, err error) {
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "系统内部异常"})
		return
	}
	if data == nil {
		data = []any{}
	}
	c.JSON(http.StatusOK, data)
}

func rowsToMaps(rows *sql.Rows) ([]map[string]any, error) {
	cols, err := rows.Columns()
	if err != nil {
		return nil, err
	}
	var list []map[string]any
	for rows.Next() {
		raw := make([]any, len(cols))
		ptr := make([]any, len(cols))
		for i := range raw {
			ptr[i] = &raw[i]
		}
		if err := rows.Scan(ptr...); err != nil {
			return nil, err
		}
		row := map[string]any{}
		for i, c := range cols {
			row[c] = normalize(raw[i])
		}
		list = append(list, row)
	}
	return list, rows.Err()
}

// dbMaps 执行查询转 map 列表。
func dbMaps(ctx context.Context, db *sql.DB, query string, args ...any) ([]map[string]any, error) {
	rows, err := db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return rowsToMaps(rows)
}

var _ = context.Background()
var _ = httpkit.OK
