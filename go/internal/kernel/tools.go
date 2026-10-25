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
	"net/url"
	"regexp"
	"strings"
	"time"

	"log/slog"
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
	slog.Info("工具执行", "tool", def.ToolName, "type", ns2(def.ToolType),
		"resultLen", len(result), "count", count, "err", err)
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
	case "KB_RETRIEVAL", "KNOWLEDGE_RETRIEVAL":
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

// ---- API_CALL（经网关签名转发；config_json 对齐 a_llm_tool 真实形态：
// {"serviceName","endpoint","method","params":{"k":"${var}"}}，模板变量从模型入参替换）----

func execAPICallTool(ctx context.Context, tc *ToolContext, def *ToolDef, argsJSON string) (string, int, error) {
	var cfg struct {
		ServiceName string            `json:"serviceName"`
		Endpoint    string            `json:"endpoint"`
		Method      string            `json:"method"`
		Params      map[string]string `json:"params"`
	}
	if def.ConfigJSON != nil && *def.ConfigJSON != "" {
		if err := json.Unmarshal([]byte(*def.ConfigJSON), &cfg); err != nil {
			return "", 0, fmt.Errorf("config_json 解析失败: %w", err)
		}
	}
	if cfg.Endpoint == "" {
		return "", 0, errors.New("API_CALL 工具缺少 config_json.endpoint")
	}
	method := strings.ToUpper(cfg.Method)
	if method == "" {
		method = http.MethodGet
	}
	var input map[string]any
	if argsJSON != "" {
		_ = json.Unmarshal([]byte(argsJSON), &input)
	}
	substitute := func(tpl string) string {
		for k, v := range input {
			tpl = strings.ReplaceAll(tpl, "${"+k+"}", fmt.Sprintf("%v", v))
		}
		for {
			i := strings.Index(tpl, "${")
			if i < 0 {
				break
			}
			j := strings.Index(tpl[i:], "}")
			if j < 0 {
				break
			}
			tpl = tpl[:i] + tpl[i+j+1:]
		}
		return tpl
	}
	target := strings.TrimSuffix(tc.GatewayBaseURL, "/") + cfg.Endpoint
	var req *http.Request
	var err error
	if method == http.MethodGet {
		qs := url.Values{}
		for k, tpl := range cfg.Params {
			if v := substitute(tpl); v != "" {
				qs.Set(k, v)
			}
		}
		if len(qs) > 0 {
			target += "?" + qs.Encode()
		}
		req, err = http.NewRequestWithContext(ctx, method, target, nil)
	} else {
		body := orEmptyObj(argsJSON)
		req, err = http.NewRequestWithContext(ctx, method, target, strings.NewReader(body))
	}
	if err != nil {
		return "", 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	payload := argsJSON
	if payload == "" {
		payload = "null"
	}
	req.Header.Set("accessKey", tc.GatewayAK)
	req.Header.Set("nonce", strings.ReplaceAll(newUUID(), "-", ""))
	req.Header.Set("timestamp", fmt.Sprintf("%d", time.Now().Unix()))
	req.Header.Set("body", payload)
	req.Header.Set("sign", gatewaySign(payload, tc.GatewaySK))

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

// ---- KNOWLEDGE_RETRIEVAL（对齐 KnowledgeRetrievalToolCallback：
// config_json.endpoint 缺省 /oa/chat/sync，经网关 AK/SK 签名调用 agent 的 RAG 问答，
// 返回 {answer}——答案是检索增强后的成文，不是原始分片）----

func execKBTool(ctx context.Context, tc *ToolContext, def *ToolDef, argsJSON string) (string, int, error) {
	var in struct {
		Question string `json:"question"`
		Query    string `json:"query"`
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
	endpoint := "/oa/chat/sync"
	if def.ConfigJSON != nil && *def.ConfigJSON != "" {
		var cfg struct {
			Endpoint string `json:"endpoint"`
		}
		if json.Unmarshal([]byte(*def.ConfigJSON), &cfg) == nil && cfg.Endpoint != "" {
			endpoint = cfg.Endpoint
		}
	}
	payload := map[string]any{
		"message":        q,
		"conversationId": newUUID(), // 独立子调用，不污染会话记忆（Java 同款）
	}
	rawBody, _ := json.Marshal(payload)
	asciiBody := toASCIIJSONBytes(rawBody)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(tc.GatewayBaseURL, "/")+endpoint, strings.NewReader(asciiBody))
	if err != nil {
		return "", 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("accessKey", tc.GatewayAK)
	req.Header.Set("nonce", strings.ReplaceAll(newUUID(), "-", ""))
	req.Header.Set("timestamp", fmt.Sprintf("%d", time.Now().Unix()))
	req.Header.Set("sign", gatewaySign(asciiBody, tc.GatewaySK))
	req.Header.Set("body", asciiBody)
	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return "", 0, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		// 不把错误页塞给模型：只回状态码，让它如实转述
		return fmt.Sprintf("{\"error\":\"知识手册检索服务暂时不可用（status=%d）\"}", resp.StatusCode), 0, nil
	}
	return fmt.Sprintf("{\"answer\":%s}", jsonString(string(body))), 1, nil
}

// gatewaySign 网关签名：SHA256(body + "." + sk) 小写 hex。
func gatewaySign(body, sk string) string {
	sum := sha256.Sum256([]byte(body + "." + sk))
	return hex.EncodeToString(sum[:])
}

// toASCIIJSONBytes JSON 内非 ASCII 转 \uXXXX（body 头只允许 ASCII，转义是合法 JSON 不破坏结构）。
func toASCIIJSONBytes(b []byte) string {
	var sb strings.Builder
	for _, r := range string(b) {
		if r < 0x80 {
			sb.WriteByte(byte(r))
		} else {
			fmt.Fprintf(&sb, "\\u%04x", r)
		}
	}
	return sb.String()
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
		if ns2(def.Status) != "" && !strings.EqualFold(ns2(def.Status), "active") {
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
