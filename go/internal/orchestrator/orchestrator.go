// Package orchestrator 是 aismp-orchestrator（Java 图编排器）的 Go 实现（P6）。
//
// 三条路线 + 五帧 SSE 契约（逐字保留，前端零改动）：
//
//	event: start      {"conversation_id": "orch-xxxx"}
//	event: progress   {"node": "...", "produced": [...]}
//	event: agent_step {"seq": 1, "text": "..."}
//	event: error      "编排异常：..."          （data 是字符串）
//	event: result     {"agent_response","cards","strategy","route","conversation_id"}
//
// 路线（意图分流，确定性算法不过 LLM 取数）：
//   - replenish：补货/调拨 —— 调 agent /terminal/health/suggest + snapshot 出确定性建议
//   - chart：图表 —— 调 agent /terminal/inventory/* 生成 ECharts option
//   - kb：知识问答 —— 调内核 /agent/{code}/chat（SERVICE 令牌，代传门户身份）
package orchestrator

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/canvas"
)

// Config 编排器参数。
type Config struct {
	AgentBaseURL     string // terminal-agent :8124
	GatewayAccessKey string // http 节点网关签名
	GatewaySecretKey string
	KernelBaseURL    string // kernel :8126
	KernelUser       string // svc_orchestrator
	KernelPass       string
	KBAgentCode      string // 知识问答路线的数字员工编码
	Addr             string
}

// Engine 编排器。
type Engine struct {
	cfg    Config
	log    *slog.Logger
	client *http.Client

	mu       sync.Mutex
	svcToken string
	tokenAt  time.Time

	Canvas *CanvasEngine // 画布编排（PG 就绪时非空）
}

// New 构造。
func New(cfg Config, log *slog.Logger) *Engine {
	return &Engine{cfg: cfg, log: log,
		client: &http.Client{Timeout: 180 * time.Second}}
}

// Register 挂路由（无前缀，网关已剥 /api/orchestrator）。
func (e *Engine) Register(r gin.IRouter) {
	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "service": "orchestrator"})
	})
	if e.Canvas != nil {
		e.Canvas.RegisterCanvasAPI(r)
	}
	r.POST("/orchestrator/v1/chat/stream", e.chatStream)
	r.GET("/orchestrator/v1/conversation/list", e.conversationList)
	r.POST("/orchestrator/v1/replenish/preview", e.replenishPreview)
}

// sseWriter 五帧输出。
type sseWriter struct {
	c   *gin.Context
	mu  sync.Mutex
	seq int
}

func (s *sseWriter) send(event, data string) {
	if s == nil || s.c == nil || s.c.Writer == nil {
		return // 画布节点复用路线函数时无 SSE 上下文，静默跳过
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	fmt.Fprintf(s.c.Writer, "event: %s\ndata: %s\n\n", event, strings.ReplaceAll(data, "\n", "\ndata: "))
	if f, ok := s.c.Writer.(http.Flusher); ok {
		f.Flush()
	}
}

func (s *sseWriter) sendJSON(event string, v any) {
	b, _ := json.Marshal(v)
	s.send(event, string(b))
}

func (e *Engine) chatStream(c *gin.Context) {
	query := c.Query("query")
	if query == "" {
		// 兼容 body 传参
		var body struct {
			Query string `json:"query"`
		}
		_ = c.ShouldBindJSON(&body)
		query = body.Query
	}
	c.Writer.Header().Set("Content-Type", "text/event-stream")
	c.Writer.Header().Set("Cache-Control", "no-cache")
	s := &sseWriter{c: c}
	convID := "orch-" + fmt.Sprintf("%d", time.Now().UnixNano()/1_000_000)
	s.sendJSON("start", gin.H{"conversation_id": convID})

	if query == "" {
		s.send("error", "编排异常：query 不能为空")
		return
	}

	// 身份：网关注入（门户）或服务方自报
	userCode := c.GetHeader("X-User-Id")
	warehouse := c.GetHeader("X-User-Warehouse")

	// 画布编排：有已发布图时走画布执行（会话粘版本）
	if e.Canvas != nil {
		if done := e.runCanvasStream(c, s, query, userCode, warehouse, convID); done {
			return
		}
	}

	intent, strategy := routeIntent(query)
	s.sendJSON("progress", gin.H{"node": "supervise", "produced": []string{"route"}})
	s.sendJSON("progress", gin.H{"node": "route", "produced": []string{"strategy"}})

	var answer string
	var cards []any
	var err error
	switch intent {
	case "replenish":
		answer, cards, err = e.runReplenish(c.Request.Context(), warehouse, s)
	case "chart":
		answer, cards, err = e.runChart(c.Request.Context(), warehouse, s)
	default:
		answer, err = e.runKB(c.Request.Context(), userCode, convID, query, s)
	}
	if err != nil {
		s.send("error", "编排异常："+err.Error())
		return
	}
	s.sendJSON("agent_step", gin.H{"seq": 1, "text": answer})
	result := gin.H{
		"agent_response":  answer,
		"cards":           cards,
		"strategy":        strategy,
		"route":           intent,
		"conversation_id": convID,
	}
	if intent == "replenish" && len(cards) > 0 {
		result["suggestion_id"] = fmt.Sprintf("sug-%d", time.Now().UnixNano()/1_000_000)
	}
	s.sendJSON("result", result)
}

// routeIntent 意图分流（关键词打分，确定性）。
func routeIntent(query string) (intent, strategy string) {
	q := strings.ToLower(query)
	has := func(words ...string) bool {
		for _, w := range words {
			if strings.Contains(q, w) {
				return true
			}
		}
		return false
	}
	switch {
	case has("补货", "调拨", "缺货", "建议", "库存健康"):
		return "replenish", "inventory_replenish_v3"
	case has("图", "chart", "占比", "趋势", "分布", "对比"):
		return "chart", "chart_v3"
	default:
		return "kb", "kb_rag_v3"
	}
}

// ---- agent 取数 ----

func (e *Engine) agentGet(ctx context.Context, path string) ([]byte, error) {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet,
		strings.TrimSuffix(e.cfg.AgentBaseURL, "/")+path, nil)
	resp, err := e.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(io.LimitReader(resp.Body, 1<<20))
}

// ---- replenish ----

func (e *Engine) runReplenish(ctx context.Context, warehouse string, s *sseWriter) (string, []any, error) {
	s.sendJSON("progress", gin.H{"node": "fetch", "produced": []string{"snapshot"}})
	if warehouse == "" {
		warehouse = "9BZA" // 演示缺省（与门户种子一致）；服务方通道应显式传
	}
	body, err := e.agentGet(ctx, "/terminal/health/suggest?warehouseCode="+url.QueryEscape(warehouse))
	if err != nil {
		return "", nil, fmt.Errorf("取数失败: %w", err)
	}
	var health struct {
		Data struct {
			WarehouseName  string  `json:"warehouseName"`
			TotalStock     int64   `json:"totalStock"`
			AvailableQty   int64   `json:"availableQty"`
			LongAgingQty   int64   `json:"longAgingQty"`
			LongAgingRatio float64 `json:"longAgingRatio"`
			ModelCount     int     `json:"modelCount"`
			HealthStatus   string  `json:"healthStatus"`
			Summary        string  `json:"summary"`
			Suggestions    []struct {
				MaterialCode      string  `json:"materialCode"`
				Model             string  `json:"model"`
				MaterialName      string  `json:"materialName"`
				FromWarehouseName string  `json:"fromWarehouseName"`
				FromWarehouse     string  `json:"fromWarehouse"`
				AvailableAtFrom   int64   `json:"availableAtFrom"`
				AvailableAtTo     int64   `json:"availableAtTo"`
				SuggestQty        int     `json:"suggestQty"`
				Reason            string  `json:"reason"`
				FromAvgAgingDays  float64 `json:"fromAvgAgingDays"`
			} `json:"suggestions"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &health); err != nil {
		return "", nil, fmt.Errorf("健康检查响应解析失败: %w", err)
	}
	h := health.Data
	var b strings.Builder
	fmt.Fprintf(&b, "## %s 补货建议\n\n%s\n", h.WarehouseName, h.Summary)
	cards := []any{}
	if len(h.Suggestions) == 0 {
		b.WriteString("\n当前无需跨仓调拨：各物料可用量均在安全线以上。")
	} else {
		b.WriteString("\n| 物料 | 型号 | 本仓可用 | 调出仓 | 对方可用 | 建议调入 |\n|---|---|---|---|---|---|\n")
		for _, sg := range h.Suggestions {
			fmt.Fprintf(&b, "| %s | %s | %d | %s | %d | %d |\n",
				sg.MaterialCode, sg.Model, sg.AvailableAtTo, sg.FromWarehouseName,
				sg.AvailableAtFrom, sg.SuggestQty)
			cards = append(cards, gin.H{
				"type": "replenish_suggestion", "materialCode": sg.MaterialCode,
				"model": sg.Model, "materialName": sg.MaterialName,
				"fromWarehouse": sg.FromWarehouse, "fromWarehouseName": sg.FromWarehouseName,
				"availableAtFrom": sg.AvailableAtFrom, "availableAtTo": sg.AvailableAtTo,
				"suggestQty": sg.SuggestQty, "reason": sg.Reason,
			})
		}
		b.WriteString("\n以上建议可直接在「调拨工单」页一键建单（AI 不介入库存事务，出库/入库由仓管操作）。")
	}
	return b.String(), cards, nil
}

// replenishPreview 同步预览（前端「补货建议预览」按钮）。
func (e *Engine) replenishPreview(c *gin.Context) {
	var body struct {
		WarehouseCode string `json:"warehouseCode"`
	}
	_ = c.ShouldBindJSON(&body)
	if body.WarehouseCode == "" {
		body.WarehouseCode = c.GetHeader("X-User-Warehouse")
	}
	warehouse := body.WarehouseCode
	if warehouse == "" {
		warehouse = "9BZA"
	}
	resp, err := e.agentGet(c.Request.Context(),
		"/terminal/health/suggest?warehouseCode="+url.QueryEscape(warehouse))
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "取数失败: " + err.Error()})
		return
	}
	c.Data(http.StatusOK, "application/json", resp)
}

// ---- chart ----

func (e *Engine) runChart(ctx context.Context, warehouse string, s *sseWriter) (string, []any, error) {
	s.sendJSON("progress", gin.H{"node": "chart_plan", "produced": []string{"plan"}})
	body, err := e.agentGet(ctx, "/terminal/inventory/byModel")
	if err != nil {
		return "", nil, fmt.Errorf("取数失败: %w", err)
	}
	var resp struct {
		Data []struct {
			Model        string  `json:"model"`
			AvailableQty int64   `json:"availableQty"`
			InTransitQty int64   `json:"inTransitQty"`
			TotalQty     int64   `json:"totalQty"`
			AvgAgingDays float64 `json:"avgAgingDays"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return "", nil, fmt.Errorf("聚合响应解析失败: %w", err)
	}
	// 只取前 8 个型号，长尾并入「其他」
	type row struct {
		name         string
		avail, total int64
		aging        float64
	}
	rows := []row{}
	otherAvail, otherTotal := int64(0), int64(0)
	for i, r := range resp.Data {
		if i < 8 {
			rows = append(rows, row{r.Model, r.AvailableQty, r.TotalQty, r.AvgAgingDays})
		} else {
			otherAvail += r.AvailableQty
			otherTotal += r.TotalQty
		}
	}
	if otherTotal > 0 {
		rows = append(rows, row{"其他", otherAvail, otherTotal, 0})
	}
	var names []string
	var availVals, totalVals []int64
	for _, r := range rows {
		names = append(names, r.name)
		availVals = append(availVals, r.avail)
		totalVals = append(totalVals, r.total)
	}
	option := gin.H{
		"title":   gin.H{"text": "终端库存可用量分布（按型号）"},
		"tooltip": gin.H{"trigger": "axis"},
		"xAxis":   gin.H{"type": "category", "data": names},
		"yAxis":   gin.H{"type": "value"},
		"series": []any{
			gin.H{"name": "可用", "type": "bar", "data": availVals},
			gin.H{"name": "在库总量", "type": "bar", "data": totalVals},
		},
	}
	answer := fmt.Sprintf("全省在库终端按型号分布如下（共 %d 个型号，展示前 %d）：可用量最高的是 %s。",
		len(resp.Data), len(rows), names[0])
	return answer, []any{gin.H{"type": "echarts", "option": option}}, nil
}

// ---- kb（内核对话）----

func (e *Engine) kernelToken(ctx context.Context) (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.svcToken != "" && time.Since(e.tokenAt) < 23*time.Hour {
		return e.svcToken, nil
	}
	payload, _ := json.Marshal(map[string]string{
		"userCode": e.cfg.KernelUser, "password": e.cfg.KernelPass,
	})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(e.cfg.KernelBaseURL, "/")+"/auth/login",
		bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	resp, err := e.client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		Token string `json:"token"`
		Error string `json:"error"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	if out.Token == "" {
		return "", fmt.Errorf("内核登录失败: %s", out.Error)
	}
	e.svcToken, e.tokenAt = out.Token, time.Now()
	return e.svcToken, nil
}

func (e *Engine) runKB(ctx context.Context, userCode, convID, query string, s *sseWriter) (string, error) {
	s.sendJSON("progress", gin.H{"node": "kb_agent", "produced": []string{"answer"}})
	token, err := e.kernelToken(ctx)
	if err != nil {
		return "", err
	}
	payload, _ := json.Marshal(map[string]any{
		"message": query, "userId": userCode, "conversationId": convID,
	})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(e.cfg.KernelBaseURL, "/")+"/agent/"+e.cfg.KBAgentCode+"/chat",
		bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := e.client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var out struct {
		Answer string `json:"answer"`
		Error  string `json:"error"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return "", fmt.Errorf("内核响应解析失败: %w", err)
	}
	if out.Error != "" {
		return "", fmt.Errorf("%s", out.Error)
	}
	return out.Answer, nil
}

// conversationList 会话列表（代理内核，按员工隔离）。
func (e *Engine) conversationList(c *gin.Context) {
	token, err := e.kernelToken(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusOK, []any{})
		return
	}
	req, _ := http.NewRequestWithContext(c.Request.Context(), http.MethodGet,
		strings.TrimSuffix(e.cfg.KernelBaseURL, "/")+"/agent/conversation/list?agentCode="+
			url.QueryEscape(c.DefaultQuery("agentCode", e.cfg.KBAgentCode))+
			"&userId="+url.QueryEscape(c.Query("userId"))+
			"&limit="+c.DefaultQuery("limit", "20"), nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := e.client.Do(req)
	if err != nil {
		c.JSON(http.StatusOK, []any{})
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	c.Data(http.StatusOK, "application/json", body)
}

// runCanvasStream 画布执行路径：有已发布图 → 会话粘版本执行。
// 返回 true 表示已产出完整五帧（调用方直接 return）；
// false = 没有已发布图（回退内置路线）。
func (e *Engine) runCanvasStream(c *gin.Context, s *sseWriter, query, userCode, warehouse, convID string) bool {
	if e.Canvas == nil || e.Canvas.Store == nil {
		return false
	}
	raw, version, err := e.Canvas.Store.LoadPublished(c.Request.Context())
	if err != nil || raw == nil {
		return false
	}
	// 会话粘版本：已有会话沿用其绑定版本，新会话绑定当前版本
	bound := version
	if bound0, err := e.Canvas.Store.ConversationVersion(c.Request.Context(), convID); err == nil && bound0 > 0 {
		raw0, v, err2 := e.Canvas.Store.LoadPublished(c.Request.Context())
		if err2 == nil && raw0 != nil && bound0 <= v {
			if dslBytes, err3 := e.Canvas.Store.LoadVersion(c.Request.Context(), bound0); err3 == nil {
				raw, bound = dslBytes, bound0
			}
		}
	}
	_ = e.Canvas.Store.BindConversation(c.Request.Context(), convID, userCode, warehouse, bound)
	dsl, err := canvas.FromJSON(raw)
	if err != nil {
		s.send("error", "编排异常：画布 DSL 解析失败: "+err.Error())
		return true
	}
	st := &canvas.State{Query: query, UserID: userCode, Warehouse: warehouse,
		HTTPDo: e.Canvas.Agent.SignedHTTPDo}
	var produced []map[string]any
	final, err := e.Canvas.RunGraph(c.Request.Context(), dsl, st, func(node string, keys []string) {
		produced = append(produced, map[string]any{"node": node, "produced": keys})
		s.sendJSON("progress", gin.H{"node": node, "produced": keys})
	})
	if err != nil {
		s.send("error", "编排异常："+err.Error())
		return true
	}
	s.sendJSON("agent_step", gin.H{"seq": 1, "text": final.Answer})
	result := gin.H{
		"agent_response":  final.Answer,
		"strategy":        fmt.Sprintf("canvas_v%d", bound),
		"route":           final.Route,
		"conversation_id": convID,
		"graph_version":   bound,
	}
	if final.Chart != nil {
		result["cards"] = final.Chart["cards"]
	}
	s.sendJSON("result", result)
	return true
}
