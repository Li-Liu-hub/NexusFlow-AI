// 工具执行器（对齐 ToolCallbackFactory + 四个 Builder + AuditedToolCallback）。
package kernel

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"
)

// ToolContext 一次对话的工具执行上下文（审计落点）。
type ToolContext struct {
	Terminal *Store
	API      *sql.DB // SQL 工具的业务库（api_db）
	Tenant   string

	TraceID        string
	ConversationID string
	UserID         string
	AgentCode      string

	GatewayBaseURL string
	GatewayAK      string
	GatewaySK      string

	AgentBaseURL string // KB 检索（agent 服务）
	HTTPClient   *http.Client
}

// Execute 调用工具并返回给模型的 JSON 文本。统一计审计（对齐 AuditedToolCallback）。
func Execute(ctx context.Context, tc *ToolContext, def *ToolDef, argsJSON string) string {
	start := time.Now()
	result, count, err := executeRaw(ctx, tc, def, argsJSON)
	elapsed := time.Since(start).Milliseconds()
	status, errMsg := "success", ""
	if err != nil {
		status, errMsg = "error", err.Error()
		if result == "" {
			result = `{"error":` + jsonString(errMsg) + `}`
		}
	}
	tc.Terminal.LogToolCall(ctx, tc.TraceID, tc.ConversationID, tc.UserID, tc.Tenant,
		def.ToolName, argsJSON, count, elapsed, status, errMsg)
	return result
}

func executeRaw(ctx context.Context, tc *ToolContext, def *ToolDef, argsJSON string) (string, int, error) {
	toolType := ns2(def.ToolType)
	if toolType == "" {
		toolType = "SQL_TEMPLATE"
	}
	switch toolType {
	case "SQL_TEMPLATE":
		return execSQLTool(ctx, tc, def, argsJSON)
	case "API_CALL":
		return execAPICallTool(ctx, tc, def, argsJSON)
	case "KNOWLEDGE_RETRIEVAL":
		return execKBTool(ctx, tc, def, argsJSON)
	case "MCP":
		return execMCPTool(ctx, tc, def, argsJSON)
	default:
		return "", 0, fmt.Errorf("不支持的工具类型 [%s]", toolType)
	}
}

// ---- SQL_TEMPLATE ----

var namedParamRe = regexp.MustCompile(`:([a-zA-Z_][a-zA-Z0-9_]*)`)

var sqlForbiddenRe = regexp.MustCompile(`(?i)\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|replace|rename|call|exec|execute)\b|into\s+outfile|into\s+dumpfile|load_file|benchmark|sleep`)

var sqlAllowedPrefix = []string{"select", "with", "show", "desc", "describe", "explain"}

// sqlGuardValidate 对齐 SqlGuard.assertSafe：只读白名单 + 危险词黑名单。
func sqlGuardValidate(tpl string) (string, error) {
	trimmed := strings.TrimSuffix(strings.TrimSpace(tpl), ";")
	if trimmed == "" {
		return "", errors.New("SQL 模板不能为空")
	}
	head := strings.ToLower(trimmed)
	allowed := false
	for _, p := range sqlAllowedPrefix {
		if strings.HasPrefix(head, p) {
			allowed = true
			break
		}
	}
	if !allowed {
		return "", errors.New("SQL 模板必须以 SELECT/WITH/SHOW/DESC/EXPLAIN 开头（数字红线：工具只读）")
	}
	if sqlForbiddenRe.MatchString(trimmed) {
		return "", errors.New("SQL 模板包含被禁止的写操作/危险关键字")
	}
	return trimmed, nil
}

func execSQLTool(ctx context.Context, tc *ToolContext, def *ToolDef, argsJSON string) (string, int, error) {
	tpl, err := sqlGuardValidate(ns2(def.SQLTemplate))
	if err != nil {
		return "", 0, err
	}
	// 命名参数白名单：模板里出现的 :param 才放行
	params := map[string]any{}
	if strings.Contains(tpl, ":__tenant") {
		params["__tenant"] = tc.Tenant
	}
	if argsJSON != "" {
		var raw map[string]any
		if json.Unmarshal([]byte(argsJSON), &raw) == nil {
			for _, name := range namedParamRe.FindAllStringSubmatch(tpl, -1) {
				if name[1] == "__tenant" {
					continue
				}
				if v, ok := raw[name[1]]; ok && v != nil {
					params[name[1]] = v
				}
			}
		}
	}
	// 命名参数 → 问号占位
	var args []any
	sqlText := namedParamRe.ReplaceAllStringFunc(tpl, func(m string) string {
		name := m[1:]
		if v, ok := params[name]; ok {
			args = append(args, v)
		} else {
			args = append(args, nil)
		}
		return "?"
	})
	limit := 50
	if def.ResultLimit != nil && *def.ResultLimit > 0 {
		limit = *def.ResultLimit
	}
	if !strings.HasSuffix(strings.ToLower(strings.TrimSpace(sqlText)), "limit "+fmt.Sprint(limit)) {
		// 结果行数封顶（result_limit），避免模型拖全表
		sqlText = "SELECT * FROM (" + sqlText + ") _t LIMIT " + fmt.Sprint(limit)
	}
	rows, err := tc.API.QueryContext(ctx, sqlText, args...)
	if err != nil {
		return "", 0, err
	}
	defer rows.Close()
	cols, err := rows.Columns()
	if err != nil {
		return "", 0, err
	}
	var out []map[string]any
	for rows.Next() {
		raw := make([]any, len(cols))
		ptr := make([]any, len(cols))
		for i := range raw {
			ptr[i] = &raw[i]
		}
		if err := rows.Scan(ptr...); err != nil {
			return "", 0, err
		}
		row := map[string]any{}
		for i, c := range cols {
			row[c] = normalize(raw[i])
		}
		out = append(out, row)
	}
	if out == nil {
		out = []map[string]any{}
	}
	b, _ := json.Marshal(out)
	return string(b), len(out), rows.Err()
}

func normalize(v any) any {
	switch t := v.(type) {
	case nil:
		return nil
	case []byte:
		return string(t)
	default:
		return v
	}
}

// ---- API_CALL（经网关签名转发）----

func execAPICallTool(ctx context.Context, tc *ToolContext, def *ToolDef, argsJSON string) (string, int, error) {
	// config_json: {"path": "...", "method": "POST"}；入参作为查询串或 body
	var cfg struct {
		Path   string `json:"path"`
		Method string `json:"method"`
	}
	if def.ConfigJSON != nil && *def.ConfigJSON != "" {
		if err := json.Unmarshal([]byte(*def.ConfigJSON), &cfg); err != nil {
			return "", 0, fmt.Errorf("config_json 解析失败: %w", err)
		}
	}
	if cfg.Path == "" {
		return "", 0, errors.New("API_CALL 工具缺少 config_json.path")
	}
	method := strings.ToUpper(cfg.Method)
	if method == "" {
		method = http.MethodGet
	}
	target := strings.TrimSuffix(tc.GatewayBaseURL, "/") + cfg.Path
	var bodyReader io.Reader
	if method == http.MethodGet {
		if argsJSON != "" && argsJSON != "{}" {
			var params map[string]any
			if err := json.Unmarshal([]byte(argsJSON), &params); err == nil {
				qs := "?"
				for k, v := range params {
					qs += k + "=" + fmt.Sprintf("%v", v) + "&"
				}
				target += strings.TrimSuffix(qs, "&")
			}
		}
	} else {
		bodyReader = strings.NewReader(argsJSON)
	}
	req, err := http.NewRequestWithContext(ctx, method, target, bodyReader)
	if err != nil {
		return "", 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	// 网关签名（对齐 OpenApiClient）：accessKey/nonce/timestamp/body/sign
	payload := argsJSON
	if payload == "" {
		payload = "null"
	}
	nonce := newUUID()
	nonce = strings.ReplaceAll(nonce, "-", "")
	req.Header.Set("accessKey", tc.GatewayAK)
	req.Header.Set("nonce", nonce)
	req.Header.Set("timestamp", fmt.Sprintf("%d", time.Now().Unix()))
	req.Header.Set("body", payload)
	sum := sha256.Sum256([]byte(payload + "." + tc.GatewaySK))
	req.Header.Set("sign", hex.EncodeToString(sum[:]))

	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return "", 0, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", 0, err
	}
	return string(body), 1, nil
}

// ---- KNOWLEDGE_RETRIEVAL（HTTP 调 agent 的 /inner/kb/search）----

func execKBTool(ctx context.Context, tc *ToolContext, def *ToolDef, argsJSON string) (string, int, error) {
	var in struct {
		Question string `json:"question"`
		Query    string `json:"query"`
		TopK     int    `json:"topK"`
	}
	if argsJSON != "" {
		_ = json.Unmarshal([]byte(argsJSON), &in)
	}
	q := in.Question
	if q == "" {
		q = in.Query
	}
	if q == "" {
		return "", 0, errors.New("缺少 question 参数")
	}
	payload, _ := json.Marshal(map[string]any{"query": q, "topK": in.TopK})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(tc.AgentBaseURL, "/")+"/inner/kb/search",
		strings.NewReader(string(payload)))
	req.Header.Set("Content-Type", "application/json")
	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return "", 0, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", 0, err
	}
	var parsed struct {
		Hits []map[string]any `json:"hits"`
	}
	count := 0
	if json.Unmarshal(body, &parsed) == nil {
		count = len(parsed.Hits)
	}
	return string(body), count, nil
}

// ---- MCP（远端 MCP server 的 tools/call，streamable HTTP 简化版）----

func execMCPTool(ctx context.Context, tc *ToolContext, def *ToolDef, argsJSON string) (string, int, error) {
	var cfg struct {
		URL string `json:"url"`
	}
	if def.ConfigJSON != nil && *def.ConfigJSON != "" {
		_ = json.Unmarshal([]byte(*def.ConfigJSON), &cfg)
	}
	if cfg.URL == "" {
		return "", 0, errors.New("MCP 工具缺少 config_json.url")
	}
	payload, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0", "id": 1, "method": "tools/call",
		"params": map[string]any{
			"name":      def.ToolName,
			"arguments": json.RawMessage(orEmptyObj(argsJSON)),
		},
	})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, cfg.URL, strings.NewReader(string(payload)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return "", 0, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", 0, err
	}
	return string(body), 1, nil
}

func orEmptyObj(s string) string {
	if strings.TrimSpace(s) == "" {
		return "{}"
	}
	return s
}

// ToolDefsToOpenAI 把 a_llm_tool 定义转成模型的 function 定义。
func ToolDefsToOpenAI(defs []*ToolDef) []ToolDefJSON {
	var out []ToolDefJSON
	for _, def := range defs {
		if ns2(def.Status) != "" && ns2(def.Status) != "active" {
			continue
		}
		var params map[string]any
		if def.InputSchema != nil && *def.InputSchema != "" {
			if err := json.Unmarshal([]byte(*def.InputSchema), &params); err != nil {
				params = map[string]any{"type": "object", "properties": map[string]any{}}
			}
		} else {
			params = map[string]any{"type": "object", "properties": map[string]any{}}
		}
		var td ToolDefJSON
		td.Type = "function"
		td.Function.Name = def.ToolName
		td.Function.Description = ns2(def.ToolDesc)
		td.Function.Parameters = params
		out = append(out, td)
	}
	return out
}
