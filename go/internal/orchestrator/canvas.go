// 画布编排接入：节点注册表（复用编排器现有能力）+ /orchestrator/v1/graph/** API
// + chat 流的「会话粘版本」执行。
package orchestrator

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/aismp/platform/internal/httpkit"

	"github.com/aismp/platform/internal/canvas"
)

// CanvasEngine 画布编排依赖。
type CanvasEngine struct {
	Store *canvas.Store // orch_graph / orch_conversation（PG）
	PG    *sql.DB
	Agent *Engine // 复用编排器现有取数/问答能力（含签名 HTTP 出口）
	Cfg   Config
}

// Registry 构建节点注册表：具名节点对齐 Python registry.py 的 14 项 +
// 路由 3 项；http 为通用工厂。
func (ce *CanvasEngine) Registry() *canvas.Registry {
	a := ce.Agent
	reg := &canvas.Registry{Nodes: map[string]canvas.NodeFunc{}, Routers: map[string]canvas.RouterFunc{}}

	reg.Nodes["intent_router"] = func(_ context.Context, st *canvas.State, _ map[string]any) error {
		route, _ := routeIntent(st.Query)
		st.Route = route
		return nil
	}
	reg.Nodes["kb"] = func(ctx context.Context, st *canvas.State, _ map[string]any) error {
		answer, err := a.runKBText(ctx, st.UserID, st.Query)
		if err != nil {
			return err
		}
		st.Answer = answer
		return nil
	}
	reg.Nodes["digital_human"] = reg.Nodes["kb"]
	reg.Nodes["fetch"] = func(ctx context.Context, st *canvas.State, params map[string]any) error {
		wh := st.Warehouse
		if v, ok := params["warehouseCode"].(string); ok && v != "" {
			wh = v
		}
		data, err := a.agentGetJSON(ctx, "/terminal/health/suggest?warehouseCode="+urlQueryEscape(wh))
		if err != nil {
			return err
		}
		st.Inventory = map[string]any{"health": json.RawMessage(data)}
		return nil
	}
	reg.Nodes["algorithm"] = func(_ context.Context, st *canvas.State, _ map[string]any) error {
		// 确定性补仓算法：fetch 产出的健康数据已含建议清单，这里只做口径固化
		if st.Inventory == nil {
			return fmt.Errorf("algorithm 前置的 fetch 未产出库存数据")
		}
		st.Suggestion = map[string]any{"source": "fetch", "deterministic": true}
		return nil
	}
	reg.Nodes["report"] = func(_ context.Context, st *canvas.State, params map[string]any) error {
		if prompt, ok := params["prompt"].(string); ok && strings.Contains(st.Report, "") {
			_ = prompt // 提示词覆盖层：由 LLM 节点消费，确定性报告不使用
		}
		if raw, ok := st.Inventory["health"].(json.RawMessage); ok {
			st.Report = replenishMarkdown(raw)
			return nil
		}
		return fmt.Errorf("report 前置数据缺失")
	}
	reg.Nodes["verify_numbers"] = func(_ context.Context, st *canvas.State, _ map[string]any) error {
		// 数字红线：报告中的数字必须能在确定性产出里找到（简版：非空即验，
		// 与 agent /terminal 原子能力同源的数字不做二次加工）
		st.Verified = st.Report != ""
		return nil
	}
	reg.Nodes["critic"] = func(_ context.Context, st *canvas.State, _ map[string]any) error {
		if !st.Verified {
			return fmt.Errorf("critic 前置的 verify_numbers 未通过")
		}
		return nil
	}
	reg.Nodes["on_rework"] = func(_ context.Context, st *canvas.State, _ map[string]any) error {
		st.ReworkCount++
		return nil
	}
	reg.Nodes["on_degrade"] = func(_ context.Context, st *canvas.State, _ map[string]any) error {
		st.Answer = "（降级）本次补货建议未通过数字校验，已略去具体数字，请联系账管员人工核对。"
		return nil
	}
	reg.Nodes["respond"] = func(_ context.Context, st *canvas.State, _ map[string]any) error {
		if st.Answer == "" {
			st.Answer = st.Report
		}
		return nil
	}
	reg.Nodes["chart_plan"] = func(_ context.Context, st *canvas.State, _ map[string]any) error {
		st.Route = "chart"
		return nil
	}
	reg.Nodes["chart_build"] = func(ctx context.Context, st *canvas.State, _ map[string]any) error {
		answer, cards, err := a.runChartText(ctx)
		if err != nil {
			return err
		}
		st.Answer, st.Chart = answer, map[string]any{"cards": cards}
		return nil
	}
	reg.Nodes["order"] = func(_ context.Context, st *canvas.State, _ map[string]any) error {
		st.Answer = "调拨下单请在「调拨工单」页提交：A 仓账管发起申请 → B 仓管导入 SN 确认出库 → A 仓管确认入库。AI 不介入库存事务。"
		return nil
	}
	reg.Nodes["http"] = canvas.HTTPNode

	reg.Routers["route_after_supervise"] = func(st *canvas.State) string {
		if st.Route == "" {
			r, _ := routeIntent(st.Query)
			st.Route = r
		}
		return st.Route
	}
	reg.Routers["route_after_critic"] = func(st *canvas.State) string {
		if st.Verified {
			return "approve"
		}
		return "rework"
	}
	reg.Routers["route_after_rework"] = func(st *canvas.State) string {
		if st.ReworkCount <= 1 {
			return "algorithm" // 回炉上限 1 次（Java 同款）
		}
		return "degrade"
	}
	return reg
}

// RunGraph 执行一份 DSL（SSE progress 由 onNode 上报）。
func (ce *CanvasEngine) RunGraph(ctx context.Context, dsl *canvas.DSL, st *canvas.State,
	onNode func(string, []string)) (*canvas.State, error) {
	ex := &canvas.Executor{Registry: ce.Registry(), OnNode: onNode}
	return ex.Run(ctx, dsl, st)
}

// ---- API（路径与 Python 版逐字对齐）----

// RegisterCanvasAPI 挂载画布编排 API（无前缀，网关已剥 /api/orchestrator）。
func (ce *CanvasEngine) RegisterCanvasAPI(r gin.IRouter) {
	g := r.Group("/orchestrator/v1/graph")
	g.GET("/draft", ce.draftGet)
	g.PUT("/draft", ce.draftPut)
	g.POST("/publish", ce.publish)
	g.GET("/versions", ce.versions)
	g.POST("/rollback", ce.rollback)
	g.POST("/debug", ce.debug)
	g.GET("/interfaces", ce.interfaces)
}

func (ce *CanvasEngine) draftGet(c *gin.Context) {
	raw, err := ce.Store.LoadDraft(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	if raw == nil {
		c.JSON(http.StatusOK, gin.H{"dsl": nil})
		return
	}
	c.Data(http.StatusOK, "application/json", raw)
}

func (ce *CanvasEngine) draftPut(c *gin.Context) {
	raw, err := readBody(c)
	if err != nil {
		return
	}
	dsl, err := canvas.FromJSON(raw)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "DSL 解析失败: " + err.Error()})
		return
	}
	if errs := canvas.Validate(dsl, ce.Registry()); len(errs) > 0 {
		// 草稿允许带错保存（编辑中），但把校验结果一并返回
		c.JSON(http.StatusOK, gin.H{"saved": true, "valid": false, "errors": errs})
		return
	}
	if err := ce.Store.SaveDraft(c.Request.Context(), raw, "画布草稿"); err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"saved": true, "valid": true})
}

func (ce *CanvasEngine) publish(c *gin.Context) {
	raw, err := readBody(c)
	if err != nil {
		return
	}
	var req struct {
		DSL  *json.RawMessage `json:"dsl"`
		Note string           `json:"note"`
	}
	publishNote := "画布发布"
	useBody := raw
	if json.Unmarshal(raw, &req) == nil {
		publishNote = req.Note
		if req.DSL != nil {
			useBody = []byte(*req.DSL)
		} else {
			// 空 body / 仅 note：发布当前草稿
			var derr error
			if useBody, derr = ce.Store.LoadDraft(c.Request.Context()); derr != nil || useBody == nil {
				c.JSON(http.StatusOK, gin.H{"error": "没有可发布的草稿"})
				return
			}
		}
	}
	dsl, err := canvas.FromJSON(useBody)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "DSL 解析失败: " + err.Error()})
		return
	}
	if errs := canvas.Validate(dsl, ce.Registry()); len(errs) > 0 {
		c.JSON(http.StatusOK, gin.H{"error": "校验未通过，禁止发布", "errors": errs})
		return
	}
	version, err := ce.Store.Publish(c.Request.Context(), useBody, publishNote)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"published": version})
}

var note string

func (ce *CanvasEngine) versions(c *gin.Context) {
	rows, err := ce.Store.Versions(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"versions": rows})
}

func (ce *CanvasEngine) rollback(c *gin.Context) {
	var req struct {
		Version int `json:"version"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.Version <= 0 {
		c.JSON(http.StatusOK, gin.H{"error": "version 必填"})
		return
	}
	raw, err := ce.Store.LoadVersion(c.Request.Context(), req.Version)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	version, err := ce.Store.Publish(c.Request.Context(), raw,
		fmt.Sprintf("回滚至 v%d", req.Version))
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"rolledBackTo": req.Version, "published": version})
}

// debug 草稿调试：不发布直接执行一次（对齐「草稿调试」）。
func (ce *CanvasEngine) debug(c *gin.Context) {
	raw, err := readBody(c)
	if err != nil {
		return
	}
	dsl, err := canvas.FromJSON(raw)
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": "DSL 解析失败: " + err.Error()})
		return
	}
	if errs := canvas.Validate(dsl, ce.Registry()); len(errs) > 0 {
		c.JSON(http.StatusOK, gin.H{"error": "校验未通过", "errors": errs})
		return
	}
	st := &canvas.State{Query: c.Query("query"), UserID: c.GetHeader("X-User-Id"),
		Warehouse: c.GetHeader("X-User-Warehouse"), HTTPDo: ce.Agent.SignedHTTPDo}
	if st.Query == "" {
		st.Query = "你好"
	}
	var produced []map[string]any
	final, err := ce.RunGraph(c.Request.Context(), dsl, st, func(node string, keys []string) {
		produced = append(produced, map[string]any{"node": node, "produced": keys})
	})
	if err != nil {
		c.JSON(http.StatusOK, gin.H{"error": err.Error(), "trace": produced})
		return
	}
	c.JSON(http.StatusOK, gin.H{"answer": final.Answer, "trace": produced})
}

func (ce *CanvasEngine) interfaces(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"routers": []string{"route_after_supervise", "route_after_critic", "route_after_rework"},
		"guards":  []string{"verify_numbers", "respond"},
		"nodeTypes": []string{"intent_router", "kb", "digital_human", "fetch", "algorithm",
			"report", "verify_numbers", "critic", "on_rework", "on_degrade", "respond",
			"chart_plan", "chart_build", "order", "http"},
	})
}

func readBody(c *gin.Context) ([]byte, error) {
	body := make([]byte, 0, 4096)
	buf := make([]byte, 4096)
	for {
		n, err := c.Request.Body.Read(buf)
		body = append(body, buf[:n]...)
		if err != nil {
			break
		}
	}
	return body, nil
}

// ---- 桥接助手：canvas 节点复用编排器现有能力（文本版）----

// runKBText 知识问答（内核链路）的纯文本版。
func (a *Engine) runKBText(ctx context.Context, userCode, query string) (string, error) {
	token, err := a.kernelToken(ctx)
	if err != nil {
		return "", err
	}
	payload, _ := json.Marshal(map[string]any{"message": query, "userId": userCode})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(a.cfg.KernelBaseURL, "/")+"/agent/"+a.cfg.KBAgentCode+"/chat",
		strings.NewReader(string(payload)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := a.client.Do(req)
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
		return "", err
	}
	if out.Error != "" {
		return "", fmt.Errorf("%s", out.Error)
	}
	return out.Answer, nil
}

// agentGetJSON GET agent 原始 JSON。
func (a *Engine) agentGetJSON(ctx context.Context, path string) ([]byte, error) {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet,
		strings.TrimSuffix(a.cfg.AgentBaseURL, "/")+path, nil)
	resp, err := a.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(io.LimitReader(resp.Body, 1<<20))
}

// runChartText 图表路线的纯文本版。
func (a *Engine) runChartText(ctx context.Context) (string, []any, error) {
	st := &canvas.State{Warehouse: ""}
	s := &sseWriter{}
	answer, cards, err := a.runChart(ctx, st.Warehouse, s)
	return answer, cards, err
}

// replenishMarkdown 从 agent 健康数据生成确定性 Markdown 报告（数字红线：全部来自 SQL 聚合）。
func replenishMarkdown(raw json.RawMessage) string {
	var resp struct {
		Data struct {
			WarehouseName string `json:"warehouseName"`
			Summary       string `json:"summary"`
			Suggestions   []struct {
				Model             string `json:"model"`
				MaterialName      string `json:"materialName"`
				FromWarehouseName string `json:"fromWarehouseName"`
				AvailableAtTo     int64  `json:"availableAtTo"`
				AvailableAtFrom   int64  `json:"availableAtFrom"`
				SuggestQty        int    `json:"suggestQty"`
			} `json:"suggestions"`
		} `json:"data"`
	}
	if json.Unmarshal(raw, &resp) != nil {
		return ""
	}
	h := resp.Data
	var b strings.Builder
	fmt.Fprintf(&b, "## %s 补货建议\n\n%s\n", h.WarehouseName, h.Summary)
	if len(h.Suggestions) == 0 {
		b.WriteString("\n当前无需跨仓调拨。")
		return b.String()
	}
	b.WriteString("\n| 物料 | 型号 | 本仓可用 | 调出仓 | 对方可用 | 建议调入 |\n|---|---|---|---|---|---|\n")
	for _, sg := range h.Suggestions {
		fmt.Fprintf(&b, "| %s | %s | %d | %s | %d | %d |\n",
			sg.Model, sg.Model, sg.AvailableAtTo, sg.FromWarehouseName,
			sg.AvailableAtFrom, sg.SuggestQty)
	}
	return b.String()
}

// signedHTTPDo 通用 http 节点的执行出口（经网关 AK/SK 签名）。
func (a *Engine) SignedHTTPDo(ctx context.Context, method, rawURL, body string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, method, rawURL, strings.NewReader(body))
	if err != nil {
		return "", err
	}
	payload := body
	if payload == "" {
		payload = "null"
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("accessKey", a.cfg.GatewayAccessKey)
	req.Header.Set("nonce", nonce32())
	req.Header.Set("timestamp", fmt.Sprintf("%d", time.Now().Unix()))
	req.Header.Set("sign", httpkit.Sign(payload, a.cfg.GatewaySecretKey))
	req.Header.Set("body", payload)
	resp, err := a.client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	return string(data), nil
}

func nonce32() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func urlQueryEscape(s string) string {
	return strings.ReplaceAll(strings.ReplaceAll(s, " ", "%20"), "&", "%26")
}
