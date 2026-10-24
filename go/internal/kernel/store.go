// Package kernel 是 aismp-digital-human（数字员工内核）的 Go 实现（P3）。
//
// 数据面：配置在 terminal_db（MySQL：a_agent / a_agent_skill / a_llm_tool /
// a_role_def / a_knowledge_base / a_tool_call_log / a_chat_conversation /
// a_chat_message）；账号在 api_db（a_user / a_auth_token，76 号迁移后两库同源）。
//
// 引擎：ReactAgent 工具循环（对齐 Spring AI Alibaba ReactAgent 的行为面）——
// OpenAI 兼容 chat/completions + function calling，循环执行工具直到模型不再请求工具。
// 工具四类：SQL_TEMPLATE（SqlGuard 只读守卫）、API_CALL（网关签名转发）、
// KNOWLEDGE_RETRIEVAL（HTTP 调 agent 的 /inner/kb）、MCP（远端 MCP server 探测/调用）。
//
// 计量：对话入口/出口 HTTP 调 backend 的 /inner/token/check|charge（配额闸门 + 落账）。
package kernel

import (
	"bufio"
	"crypto/sha256"
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

// ---- 配置 ----

// Config 内核运行参数。
type Config struct {
	TerminalDSN string // terminal_db（配置/会话/审计）
	APIDSN      string // api_db（a_user / a_auth_token / SQL 工具的业务表）

	LLMBaseURL string // OpenAI 兼容端点（MiMo）
	LLMAPIKey  string
	LLMModel   string // 缺省模型（装配时优先用 a_agent.model_name）

	EmbedBaseURL string // SiliconFlow（bge-m3）
	EmbedAPIKey  string
	EmbedModel   string

	PGDSN string // terminal_kb（vector_store）——KB 管理与检索

	BackendBaseURL string // /inner/token/*
	AgentBaseURL   string // /inner/kb/*
	GatewayBaseURL string // API_CALL 工具经网关
	GatewayAK      string
	GatewaySK      string

	Addr string
}

// ---- 数据结构（列名同名）----

// Agent 对齐 a_agent（AgentPO）。
type Agent struct {
	ID              int64    `json:"id"`
	AgentCode       string   `json:"agentCode"`
	AgentName       string   `json:"agentName"`
	Avatar          *string  `json:"avatar"`
	Description     *string  `json:"description"`
	SystemPrompt    *string  `json:"systemPrompt"`
	ModelName       *string  `json:"modelName"`
	Temperature     *float64 `json:"temperature"`
	MaxTurns        *int     `json:"maxTurns"`
	KnowledgeBaseID *int64   `json:"knowledgeBaseId"`
	Status          *string  `json:"status"`
	Version         *int     `json:"version"`
	TenantCode      *string  `json:"tenantCode"`
	CreateTime      *string  `json:"createTime"`
	UpdateTime      *string  `json:"updateTime"`
}

// Skill 对齐 a_agent_skill（SkillPO）。
type Skill struct {
	ID             int64    `json:"id"`
	SkillCode      *string  `json:"skillCode"`
	SkillName      string   `json:"skillName"`
	Description    *string  `json:"description"`
	PromptFragment *string  `json:"promptFragment"`
	Examples       *string  `json:"examples"`
	SortOrder      *int     `json:"sortOrder"`
	Status         *string  `json:"status"`
	CreateTime     *string  `json:"createTime"`
	UpdateTime     *string  `json:"updateTime"`
}

// ToolDef 对齐 a_llm_tool（ToolDefPO）。
type ToolDef struct {
	ID              int64    `json:"id"`
	ToolType        *string  `json:"toolType"`
	ToolName        string   `json:"toolName"`
	DisplayName     *string  `json:"displayName"`
	ToolDesc        *string  `json:"toolDesc"`
	InputSchema     *string  `json:"inputSchema"`
	SQLTemplate     *string  `json:"sqlTemplate"`
	ConfigJSON      *string  `json:"configJson"`
	TableAlias      *string  `json:"tableAlias"`
	ResultLimit     *int     `json:"resultLimit"`
	ApprovalRequired *int    `json:"approvalRequired"`
	RiskLevel       *string  `json:"riskLevel"`
	Status          *string  `json:"status"`
	Remark          *string  `json:"remark"`
}

// ---- Store：terminal_db 配置 + 会话 + 审计 ----

// Store 承载 terminal_db 全部查询。
type Store struct {
	db *sql.DB
}

// NewStore 构造。
func NewStore(db *sql.DB) *Store { return &Store{db: db} }

const agentColumns = `id, agent_code, agent_name, avatar, description, system_prompt,
	model_name, temperature, max_turns, knowledge_base_id, status, version, tenant_code,
	create_time, update_time`

func scanAgent(scan interface{ Scan(...any) error }) (*Agent, error) {
	var a Agent
	var avatar, description, systemPrompt, modelName, status, tenantCode sql.NullString
	var temperature sql.NullFloat64
	var maxTurns, version sql.NullInt64
	var kbID sql.NullInt64
	var create, update sql.NullTime
	if err := scan.Scan(&a.ID, &a.AgentCode, &a.AgentName, &avatar, &description, &systemPrompt,
		&modelName, &temperature, &maxTurns, &kbID, &status, &version, &tenantCode,
		&create, &update); err != nil {
		return nil, err
	}
	a.Avatar, a.Description, a.SystemPrompt, a.ModelName = ns(avatar), ns(description), ns(systemPrompt), ns(modelName)
	a.Status, a.TenantCode = ns(status), ns(tenantCode)
	a.Temperature = nf(temperature)
	a.MaxTurns, a.Version = ni(maxTurns), ni(version)
	if kbID.Valid {
		v := kbID.Int64
		a.KnowledgeBaseID = &v
	}
	a.CreateTime, a.UpdateTime = ts(create), ts(update)
	return &a, nil
}

// AgentByCode 按 code 查。
func (s *Store) AgentByCode(ctx context.Context, code string) (*Agent, error) {
	a, err := scanAgent(s.db.QueryRowContext(ctx,
		`SELECT `+agentColumns+` FROM a_agent WHERE agent_code = ?`, code))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return a, err
}

// AgentsAll 全部（管理台列表）。
func (s *Store) AgentsAll(ctx context.Context) ([]*Agent, error) {
	return s.agentsBy(ctx, `SELECT `+agentColumns+` FROM a_agent ORDER BY id`)
}

// AgentsActive 生效中的（路由候选）。
func (s *Store) AgentsActive(ctx context.Context) ([]*Agent, error) {
	return s.agentsBy(ctx, `SELECT `+agentColumns+` FROM a_agent WHERE status='active' ORDER BY id`)
}

func (s *Store) agentsBy(ctx context.Context, q string) ([]*Agent, error) {
	rows, err := s.db.QueryContext(ctx, q)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*Agent
	for rows.Next() {
		a, err := scanAgent(rows)
		if err != nil {
			return nil, err
		}
		list = append(list, a)
	}
	return list, rows.Err()
}

// SkillsOfAgent 技能包（按 sort_order）。
func (s *Store) SkillsOfAgent(ctx context.Context, agentID int64) ([]*Skill, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT s.id, s.skill_code, s.skill_name, s.description, s.prompt_fragment,
		       s.examples, s.sort_order, s.status, s.create_time, s.update_time
		FROM a_agent_skill s
		JOIN a_agent_skill_rel r ON r.skill_id = s.id
		WHERE r.agent_id = ? ORDER BY r.sort_order, s.id`, agentID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*Skill
	for rows.Next() {
		var k Skill
		var code, description, fragment, examples, status sql.NullString
		var sort sql.NullInt64
		var create, update sql.NullTime
		if err := rows.Scan(&k.ID, &code, &k.SkillName, &description, &fragment,
			&examples, &sort, &status, &create, &update); err != nil {
			return nil, err
		}
		k.SkillCode, k.Description, k.PromptFragment, k.Examples = ns(code), ns(description), ns(fragment), ns(examples)
		k.Status, k.SortOrder = ns(status), ni(sort)
		k.CreateTime, k.UpdateTime = ts(create), ts(update)
		list = append(list, &k)
	}
	return list, rows.Err()
}

// ToolsOfAgent 挂载的工具。
func (s *Store) ToolsOfAgent(ctx context.Context, agentID int64) ([]*ToolDef, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT t.id, t.tool_type, t.tool_name, t.display_name, t.tool_desc, t.input_schema,
		       t.sql_template, t.config_json, t.table_alias, t.result_limit,
		       t.approval_required, t.risk_level, t.status, t.remark
		FROM a_llm_tool t
		JOIN a_agent_tool_rel r ON r.tool_id = t.id
		WHERE r.agent_id = ? ORDER BY r.sort_order, t.id`, agentID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanTools(rows)
}

func scanTools(rows *sql.Rows) ([]*ToolDef, error) {
	var list []*ToolDef
	for rows.Next() {
		var t ToolDef
		var toolType, displayName, toolDesc, inputSchema, sqlTemplate, configJSON sql.NullString
		var tableAlias, riskLevel, status, remark sql.NullString
		var resultLimit, approvalRequired sql.NullInt64
		if err := rows.Scan(&t.ID, &toolType, &t.ToolName, &displayName, &toolDesc, &inputSchema,
			&sqlTemplate, &configJSON, &tableAlias, &resultLimit,
			&approvalRequired, &riskLevel, &status, &remark); err != nil {
			return nil, err
		}
		t.ToolType, t.DisplayName, t.ToolDesc, t.InputSchema, t.SQLTemplate, t.ConfigJSON =
			ns(toolType), ns(displayName), ns(toolDesc), ns(inputSchema), ns(sqlTemplate), ns(configJSON)
		t.TableAlias, t.RiskLevel, t.Status, t.Remark = ns(tableAlias), ns(riskLevel), ns(status), ns(remark)
		t.ResultLimit, t.ApprovalRequired = ni(resultLimit), ni(approvalRequired)
		list = append(list, &t)
	}
	return list, rows.Err()
}

// ToolsAll / ToolByID / ToolsByIDs 管理台目录。
func (s *Store) ToolsAll(ctx context.Context) ([]*ToolDef, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, tool_type, tool_name, display_name, tool_desc,
		input_schema, sql_template, config_json, table_alias, result_limit, approval_required,
		risk_level, status, remark FROM a_llm_tool ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	return scanTools(rows)
}

// KBInfo 知识库元数据（a_knowledge_base）。
func (s *Store) KBInfo(ctx context.Context, id int64) (map[string]any, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, knowledge_base_id, name, IFNULL(description,''), IFNULL(is_default,0), created_at
		FROM a_knowledge_base WHERE id = ?`, id)
	var m = map[string]any{}
	var kbUUID, name string
	var description string
	var isDefault int
	var created string
	var idv int64
	if err := row.Scan(&idv, &kbUUID, &name, &description, &isDefault, &created); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	m["id"], m["knowledge_base_id"], m["name"], m["description"], m["is_default"], m["created_at"] =
		idv, kbUUID, name, description, isDefault, created
	return m, nil
}

// ---- 会话（a_chat_conversation / a_chat_message）----

// EnsureConversation 幂等建会话。
func (s *Store) EnsureConversation(ctx context.Context, agentCode, conversationID, title, userID, source, firstMessage string) error {
	var n int
	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM a_chat_conversation WHERE conversation_id = ?`, conversationID).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO a_chat_conversation (conversation_id, agent_code, user_id, title, source, created_at)
		VALUES (?, ?, ?, ?, ?, NOW())`,
		conversationID, agentCode, userID, firstText(title, firstMessage), firstText(source, "auto"))
	return err
}

func firstText(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			if len([]rune(v)) > 50 {
				return string([]rune(v)[:50])
			}
			return v
		}
	}
	return ""
}

// AppendMessage 落一条消息（状态 success/error）。
func (s *Store) AppendMessage(ctx context.Context, agentCode, conversationID, userID, role, content,
	source, modelName string, elapsedMs int, status, errMsg string) error {
	var contentVal, errVal any
	if content != "" {
		contentVal = content
	}
	if errMsg != "" {
		errVal = errMsg
	}
	var modelVal any
	if modelName != "" {
		modelVal = modelName
	}
	var elapsed any
	if elapsedMs > 0 {
		elapsed = elapsedMs
	}
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO a_chat_message (conversation_id, agent_code, user_id, role, content,
			source, model_name, elapsed_ms, status, error_message, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())`,
		conversationID, agentCode, userID, role, contentVal, source, modelVal, elapsed, status, errVal)
	return err
}

// HistoryTurn 最近 N 条（user/assistant 成对，按时间正序）。
func (s *Store) HistoryTurns(ctx context.Context, agentCode, conversationID string, limit int) ([][2]string, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT role, IFNULL(content,'') FROM a_chat_message
		WHERE agent_code = ? AND conversation_id = ? AND status = 'success'
		  AND role IN ('user','assistant') AND content IS NOT NULL
		ORDER BY created_at DESC, id DESC LIMIT ?`, agentCode, conversationID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var pairs [][2]string
	for rows.Next() {
		var role, content string
		if err := rows.Scan(&role, &content); err != nil {
			return nil, err
		}
		pairs = append(pairs, [2]string{role, content})
	}
	// 反转成时间正序
	for i, j := 0, len(pairs)-1; i < j; i, j = i+1, j-1 {
		pairs[i], pairs[j] = pairs[j], pairs[i]
	}
	return pairs, rows.Err()
}

// ListConversations 会话列表。
func (s *Store) ListConversations(ctx context.Context, agentCode, userID string, limit int) ([]map[string]any, error) {
	where, args := "agent_code = ?", []any{agentCode}
	if userID != "" {
		where += " AND user_id = ?"
		args = append(args, userID)
	}
	args = append(args, limit)
	rows, err := s.db.QueryContext(ctx, `
		SELECT conversation_id, agent_code, user_id, IFNULL(title,''), IFNULL(source,''), created_at
		FROM a_chat_conversation WHERE `+where+` ORDER BY created_at DESC LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []map[string]any
	for rows.Next() {
		var convID, code, uid, title, source, created string
		if err := rows.Scan(&convID, &code, &uid, &title, &source, &created); err != nil {
			return nil, err
		}
		list = append(list, map[string]any{
			"conversationId": convID, "agentCode": code, "userId": uid,
			"title": title, "source": source, "createdAt": created,
		})
	}
	return list, rows.Err()
}

// ConversationDetail 会话 + 消息。
func (s *Store) ConversationDetail(ctx context.Context, conversationID string, messageLimit int) (map[string]any, error) {
	var code, uid, title, created string
	err := s.db.QueryRowContext(ctx, `
		SELECT agent_code, IFNULL(user_id,''), IFNULL(title,''), created_at
		FROM a_chat_conversation WHERE conversation_id = ?`, conversationID).
		Scan(&code, &uid, &title, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT role, IFNULL(content,''), IFNULL(status,''), created_at
		FROM a_chat_message WHERE conversation_id = ?
		ORDER BY created_at ASC, id ASC LIMIT ?`, conversationID, messageLimit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	messages := []map[string]any{}
	for rows.Next() {
		var role, content, status, created string
		if err := rows.Scan(&role, &content, &status, &created); err != nil {
			return nil, err
		}
		messages = append(messages, map[string]any{
			"role": role, "content": content, "status": status, "createdAt": created,
		})
	}
	return map[string]any{
		"conversationId": conversationID, "agentCode": code, "userId": uid,
		"title": title, "createdAt": created, "messages": messages,
	}, nil
}

// ArchiveConversation 归档。
func (s *Store) ArchiveConversation(ctx context.Context, conversationID string) (int64, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE a_chat_conversation SET status='archived' WHERE conversation_id = ?`, conversationID)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// LogToolCall 工具审计（a_tool_call_log）。
func (s *Store) LogToolCall(ctx context.Context, traceID, conversationID, userID, entCode,
	toolName, argumentsJSON string, resultCount int, durationMs int64, status, errMsg string) {
	var argsVal any
	if argumentsJSON != "" {
		argsVal = argumentsJSON
	}
	var errVal any
	if errMsg != "" {
		errVal = errMsg
	}
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO a_tool_call_log (trace_id, conversation_id, user_id, ent_code, tool_name,
			arguments_json, result_count, duration_ms, status, error_message, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())`,
		traceID, conversationID, userID, entCode, toolName, argsVal, resultCount, durationMs, status, errVal)
	if err != nil {
		slog.Warn("工具审计落库失败", "err", err)
	}
}

// RecentToolCalls 审计最近 N 条。
func (s *Store) RecentToolCalls(ctx context.Context, limit int) ([]map[string]any, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT trace_id, conversation_id, tool_name, IFNULL(arguments_json,''),
		       IFNULL(result_count,0), IFNULL(duration_ms,0), IFNULL(status,''), created_at
		FROM a_tool_call_log ORDER BY created_at DESC, id DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []map[string]any
	for rows.Next() {
		var trace, conv, tool, args, status, created string
		var cnt, dur int64
		if err := rows.Scan(&trace, &conv, &tool, &args, &cnt, &dur, &status, &created); err != nil {
			return nil, err
		}
		list = append(list, map[string]any{
			"traceId": trace, "conversationId": conv, "toolName": tool,
			"argumentsJson": args, "resultCount": cnt, "durationMs": dur,
			"status": status, "createdAt": created,
		})
	}
	return list, rows.Err()
}

// ToolCallStats 工具调用统计。
func (s *Store) ToolCallStats(ctx context.Context) ([]map[string]any, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT tool_name, COUNT(*) AS calls,
		       SUM(CASE WHEN status='success' THEN 1 ELSE 0 END) AS success,
		       IFNULL(AVG(duration_ms),0) AS avg_ms
		FROM a_tool_call_log GROUP BY tool_name ORDER BY calls DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []map[string]any
	for rows.Next() {
		var tool string
		var calls, success int64
		var avg float64
		if err := rows.Scan(&tool, &calls, &success, &avg); err != nil {
			return nil, err
		}
		list = append(list, map[string]any{
			"toolName": tool, "calls": calls, "success": success, "avgMs": avg,
		})
	}
	return list, rows.Err()
}


// ---- 可空列辅助 ----

func ns(s sql.NullString) *string {
	if !s.Valid {
		return nil
	}
	v := s.String
	return &v
}

func ns2(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

func ni(s sql.NullInt64) *int {
	if !s.Valid {
		return nil
	}
	v := int(s.Int64)
	return &v
}

func nf(s sql.NullFloat64) *float64 {
	if !s.Valid {
		return nil
	}
	v := s.Float64
	return &v
}

func ts(s sql.NullTime) *string {
	if !s.Valid {
		return nil
	}
	v := s.Time.Format("2006-01-02T15:04:05")
	return &v
}

func sha256Sum(b []byte) []byte {
	// 单独引入 crypto/sha256 由 admin.go 使用；这里提供包级别名避免循环 import
	return sha256SumImpl(b)
}

// ---- Registry：按 version 缓存装配结果 ----

// Assembled 对齐 AssembledAgent。
type Assembled struct {
	Agent       *Agent
	SystemPrompt string
	Skills      []*Skill
	Tools       []*ToolDef
	AssembledAt int64
}

// Registry 按 agent_code 缓存，version 变化即失效。
type Registry struct {
	store *Store
	mu    sync.Mutex
	cache map[string]*Assembled
}

// NewRegistry 构造。
func NewRegistry(store *Store) *Registry {
	return &Registry{store: store, cache: map[string]*Assembled{}}
}

// Get 装配（带缓存）；不存在/停用返回 nil。
func (r *Registry) Get(ctx context.Context, code string) (*Assembled, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	cached, ok := r.cache[code]
	if ok {
		// version 变了就重建
		var cur int
		if err := r.store.db.QueryRowContext(ctx,
			`SELECT version FROM a_agent WHERE agent_code = ?`, code).Scan(&cur); err == nil &&
			cached.Agent.Version != nil && int(cur) == *cached.Agent.Version {
			return cached, nil
		}
		delete(r.cache, code)
	}
	a, err := r.store.AgentByCode(ctx, code)
	if err != nil {
		return nil, err
	}
	if a == nil || ns2(a.Status) != "active" {
		return nil, nil
	}
	skills, err := r.store.SkillsOfAgent(ctx, a.ID)
	if err != nil {
		return nil, err
	}
	tools, err := r.store.ToolsOfAgent(ctx, a.ID)
	if err != nil {
		return nil, err
	}
	var sb strings.Builder
	sb.WriteString(ns2(a.SystemPrompt))
	for _, k := range skills {
		if k.PromptFragment != nil && *k.PromptFragment != "" {
			sb.WriteString("\n\n")
			sb.WriteString(*k.PromptFragment)
		}
		if k.Examples != nil && *k.Examples != "" {
			sb.WriteString("\n参考示例：")
			sb.WriteString(*k.Examples)
		}
	}
	if a.KnowledgeBaseID != nil {
		if kb, err := r.store.KBInfo(ctx, *a.KnowledgeBaseID); err == nil && kb != nil {
			sb.WriteString(fmt.Sprintf("\n\n【已挂载知识库】《%v》。凡涉及业务流程、操作规范、管理办法类的问题，先调用知识检索工具核对手册原文再回答，手册没有的内容如实说明，不要编造。", kb["name"]))
		}
	}
	asm := &Assembled{Agent: a, SystemPrompt: sb.String(), Skills: skills, Tools: tools,
		AssembledAt: time.Now().UnixMilli()}
	r.cache[code] = asm
	return asm, nil
}

// Evict 手动逐出（管理台 reload 用）。
func (r *Registry) Evict(code string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.cache, code)
}

// ---- LLM 客户端（OpenAI 兼容）----

// LLM 客户端。
type LLM struct {
	baseURL string
	apiKey  string
	client  *http.Client
}

// Message 是对话消息（content 或 tool_calls / tool_call_id）。
type Message struct {
	Role       string      `json:"role"`
	Content    any         `json:"content,omitempty"`
	ToolCalls  []ToolCall  `json:"tool_calls,omitempty"`
	ToolCallID string      `json:"tool_call_id,omitempty"`
	Name       string      `json:"name,omitempty"`
}

// ToolCall 模型请求的工具调用。
type ToolCall struct {
	ID       string `json:"id"`
	Type     string `json:"type"`
	Function struct {
		Name      string `json:"name"`
		Arguments string `json:"arguments"`
	} `json:"function"`
}

// ToolDefJSON 是发给模型的 function 定义。
type ToolDefJSON struct {
	Type     string `json:"type"`
	Function struct {
		Name        string         `json:"name"`
		Description string         `json:"description"`
		Parameters  map[string]any `json:"parameters"`
	} `json:"function"`
}

// chatRequest / chunk：流式解析用。
type llmRequest struct {
	Model       string    `json:"model"`
	Messages    []Message `json:"messages"`
	Tools       []ToolDefJSON `json:"tools,omitempty"`
	Temperature float64   `json:"temperature"`
	Stream      bool      `json:"stream"`
}

type chatChoice struct {
	Delta struct {
		Content   string     `json:"content"`
		ToolCalls []struct {
			Index    int    `json:"index"`
			ID       string `json:"id"`
			Type     string `json:"type"`
			Function struct {
				Name      string `json:"name"`
				Arguments string `json:"arguments"`
			} `json:"function"`
		} `json:"tool_calls"`
	} `json:"delta"`
	FinishReason *string `json:"finish_reason"`
}

type chatChunk struct {
	Choices []chatChoice `json:"choices"`
	Usage   struct {
		PromptTokens     int64 `json:"prompt_tokens"`
		CompletionTokens int64 `json:"completion_tokens"`
		TotalTokens      int64 `json:"total_tokens"`
	} `json:"usage"`
}

// StreamChat 发起流式补全，逐段回调 delta 与累积的 tool_calls；返回 usage。
// content 为 nil 的 assistant 消息（纯工具调用轮）必须传空串以外的东西——
// OpenAI 兼容端点接受 content:""。
func (l *LLM) StreamChat(ctx context.Context, model string, temperature float64,
	messages []Message, tools []ToolDefJSON,
	onDelta func(string) error, onToolCall func(ToolCall) error) (int64, int64, error) {
	reqBody, err := json.Marshal(llmRequest{
		Model: model, Messages: messages, Tools: tools,
		Temperature: temperature, Stream: true,
	})
	if err != nil {
		return 0, 0, err
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(l.baseURL, "/")+"/v1/chat/completions",
		bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+l.apiKey)
	resp, err := l.client.Do(req)
	if err != nil {
		return 0, 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return 0, 0, fmt.Errorf("LLM %d: %s", resp.StatusCode, string(body))
	}
	// SSE 解析
	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 1024*1024), 1024*1024)
	var prompt, completion int64
	toolAcc := map[int]*ToolCall{}
	var order []int
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		data := strings.TrimSpace(line[len("data: "):])
		if data == "" || data == "[DONE]" {
			continue
		}
		var chunk chatChunk
		if err := json.Unmarshal([]byte(data), &chunk); err != nil {
			continue
		}
		if chunk.Usage.TotalTokens > 0 {
			prompt, completion = chunk.Usage.PromptTokens, chunk.Usage.CompletionTokens
		}
		for _, ch := range chunk.Choices {
			if ch.Delta.Content != "" {
				if err := onDelta(ch.Delta.Content); err != nil {
					return prompt, completion, err
				}
			}
			for _, tc := range ch.Delta.ToolCalls {
				acc, ok := toolAcc[tc.Index]
				if !ok {
					acc = &ToolCall{ID: tc.ID, Type: "function"}
					acc.Function.Name = tc.Function.Name
					toolAcc[tc.Index] = acc
					order = append(order, tc.Index)
				}
				if tc.ID != "" {
					acc.ID = tc.ID
				}
				if tc.Function.Name != "" {
					acc.Function.Name += tc.Function.Name
				}
				if tc.Function.Arguments != "" {
					acc.Function.Arguments += tc.Function.Arguments
				}
			}
			if ch.FinishReason != nil && *ch.FinishReason == "tool_calls" {
				for _, idx := range order {
					if err := onToolCall(*toolAcc[idx]); err != nil {
						return prompt, completion, err
					}
				}
				toolAcc = map[int]*ToolCall{}
				order = order[:0]
			}
		}
	}
	// 部分 OpenAI 兼容端点不带 finish_reason=tool_calls：流结束时若有累积的工具调用也交出
	for _, idx := range order {
		if err := onToolCall(*toolAcc[idx]); err != nil {
			return prompt, completion, err
		}
	}
	return prompt, completion, scanner.Err()
}

// newUUID 造 id。
func newUUID() string { return uuid.NewString() }

// sha256SumImpl 实际实现（放在本文件底部，避免多文件重复 import）。
func sha256SumImpl(b []byte) []byte {
	sum := sha256.Sum256(b)
	return sum[:]
}
