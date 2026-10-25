// AI 辅助工单建单（简历要点②的载体）：
// LLM 分类（7 类）→ 表单匹配 + 参数生成 → 工单平台创建。
//   - 防重复提交：requestId 唯一约束，重复请求返回首次结果
//   - 远端建单成功但本地保存失败：只补写记录（不重创建），避免重复建单
//   - PC/APP 双端入口（source 区分），APP 语音转写文本直接作为输入
package biz

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// ---- 7 类工单（终端管理域分类体系）----

var TicketCategories = []struct {
	Code string
	Name string
}{
	{"use_apply", "领用申请"},
	{"return_apply", "回收申请"},
	{"fault_repair", "故障报修"},
	{"transfer_apply", "调拨申请"},
	{"stocktake", "盘点申请"},
	{"info_change", "信息变更"},
	{"other_consult", "其他咨询"},
}

// ValidCategory 分类白名单。
func ValidCategory(code string) bool {
	for _, c := range TicketCategories {
		if c.Code == code {
			return true
		}
	}
	return false
}

// ---- 数据模型（ticket_request，api_db 增量表）----

// TicketRequest 一次建单请求。
type TicketRequest struct {
	ID           int64      `json:"id"`
	RequestID    string     `json:"requestId"` // 客户端防重复提交标识
	UserID       string     `json:"userId"`
	Source       string     `json:"source"` // pc / app
	RawText      string     `json:"rawText"`
	Category     string     `json:"category"`
	ParamsJSON   string     `json:"params"`
	PlatformID   *string    `json:"platformTicketId"`
	State        string     `json:"state"` // classified / created / saved / failed
	ErrorMessage *string    `json:"errorMessage,omitempty"`
	CreatedAt    *time.Time `json:"createdAt"`
}

// TicketStore 工单域仓储。
type TicketStore struct {
	db *sql.DB
}

// NewTicketStore 构造。
func NewTicketStore(db *sql.DB) *TicketStore { return &TicketStore{db: db} }

// EnsureTicketSchema 增量建表 + 7 类种子（幂等）。
func (s *TicketStore) EnsureTicketSchema(ctx context.Context) error {
	for _, ddl := range []string{
		`CREATE TABLE IF NOT EXISTS ticket_request (
		   id BIGINT AUTO_INCREMENT PRIMARY KEY,
		   request_id VARCHAR(64) NOT NULL UNIQUE,
		   user_id VARCHAR(64) NOT NULL,
		   source VARCHAR(8) NOT NULL DEFAULT 'pc',
		   raw_text TEXT NOT NULL,
		   category VARCHAR(32) NOT NULL,
		   params JSON NULL,
		   platform_ticket_id VARCHAR(64) NULL,
		   state VARCHAR(16) NOT NULL,
		   error_message VARCHAR(500) NULL,
		   created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
		   INDEX idx_tr_user (user_id, created_at)
		 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS ticket_category (
		   code VARCHAR(32) PRIMARY KEY,
		   name VARCHAR(64) NOT NULL,
		   sort_order INT NOT NULL DEFAULT 100
		 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
	} {
		if _, err := s.db.ExecContext(ctx, ddl); err != nil {
			return err
		}
	}
	for i, c := range TicketCategories {
		if _, err := s.db.ExecContext(ctx,
			`INSERT INTO ticket_category (code, name, sort_order) VALUES (?, ?, ?)
			 ON DUPLICATE KEY UPDATE name = VALUES(name)`, c.Code, c.Name, i); err != nil {
			return err
		}
	}
	return nil
}

// SaveClassified 落一条 classified 记录（requestId 唯一约束兜底防重）。
func (s *TicketStore) SaveClassified(ctx context.Context, r *TicketRequest) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO ticket_request (request_id, user_id, source, raw_text, category, params, state)
		VALUES (?, ?, ?, ?, ?, ?, 'classified')
		ON DUPLICATE KEY UPDATE raw_text = VALUES(raw_text)`,
		r.RequestID, r.UserID, r.Source, r.RawText, r.Category, r.ParamsJSON)
	return err
}

// FindByRequestID 按 requestId 查（幂等语义的核心：重复提交返回首次结果）。
func (s *TicketStore) FindByRequestID(ctx context.Context, requestID string) (*TicketRequest, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, request_id, user_id, source, raw_text, category, IFNULL(params,''),
		       platform_ticket_id, state, error_message, created_at
		  FROM ticket_request WHERE request_id = ?`, requestID)
	return scanTicket(row)
}

func scanTicket(row interface{ Scan(...any) error }) (*TicketRequest, error) {
	var r TicketRequest
	var params, platform, errMsg sql.NullString
	var created sql.NullTime
	if err := row.Scan(&r.ID, &r.RequestID, &r.UserID, &r.Source, &r.RawText, &r.Category,
		&params, &platform, &r.State, &errMsg, &created); err != nil {
		return nil, err
	}
	r.ParamsJSON, r.PlatformID, r.ErrorMessage = params.String, nstr(platform), nstr(errMsg)
	r.CreatedAt = ntime(created)
	return &r, nil
}

// History 用户历史查询（新→旧）。
func (s *TicketStore) History(ctx context.Context, userID string, limit int) ([]*TicketRequest, error) {
	if limit < 1 {
		limit = 20
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, request_id, user_id, source, raw_text, category, IFNULL(params,''),
		       platform_ticket_id, state, error_message, created_at
		  FROM ticket_request WHERE user_id = ? ORDER BY id DESC LIMIT ?`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*TicketRequest
	for rows.Next() {
		r, err := scanTicket(rows)
		if err != nil {
			return nil, err
		}
		list = append(list, r)
	}
	return list, rows.Err()
}

// ---- 建单流程 ----

// TicketPlatform 工单平台适配器（远端建单；demo 为本地实现，生产换联通工单平台）。
type TicketPlatform interface {
	Create(ctx context.Context, category string, params map[string]any) (platformTicketID string, err error)
}

// Classifier LLM 分类 + 参数生成。
type Classifier struct {
	LLMBaseURL string
	LLMAPIKey  string
	LLMModel   string
}

const classifySystem = `你是运营商固网终端管理的工单分类引擎。根据客户描述判断工单类别，` +
	"只能从以下 7 类中选一个：" +
	`use_apply(领用申请), return_apply(回收申请), fault_repair(故障报修), ` +
	`transfer_apply(调拨申请), stocktake(盘点申请), info_change(信息变更), other_consult(其他咨询)。` +
	"输出一个 JSON 对象，不要输出其他内容：" +
	`{"category":"类别代码","title":"工单标题(15字内)","urgency":"normal|high","detail":"补充说明(50字内)"}`

// ClassifyAndBuild LLM 分类 + 生成建单参数。
func (c *Classifier) ClassifyAndBuild(ctx context.Context, text string) (category string, params map[string]any, err error) {
	out, err := ChatOnce(ctx, c.LLMBaseURL, c.LLMAPIKey, c.LLMModel, classifySystem, text)
	if err != nil {
		return "", nil, err
	}
	start := strings.Index(out, "{")
	end := strings.LastIndex(out, "}")
	if start < 0 || end <= start {
		return "", nil, fmt.Errorf("分类输出不含 JSON")
	}
	var parsed struct {
		Category string `json:"category"`
		Title    string `json:"title"`
		Urgency  string `json:"urgency"`
		Detail   string `json:"detail"`
	}
	if err := json.Unmarshal([]byte(out[start:end+1]), &parsed); err != nil {
		return "", nil, fmt.Errorf("分类 JSON 解析失败: %w", err)
	}
	if !ValidCategory(parsed.Category) {
		return "", nil, fmt.Errorf("无效的工单类别: %s", parsed.Category)
	}
	params = map[string]any{
		"title":   parsed.Title,
		"urgency": parsed.Urgency,
		"detail":  parsed.Detail,
		"source":  "ai_assist",
	}
	return parsed.Category, params, nil
}

func jsonMap(raw string) map[string]any {
	m := map[string]any{}
	_ = json.Unmarshal([]byte(raw), &m)
	return m
}

func derefStr(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

// CreateResult 建单执行结果。
type CreateResult struct {
	Duplicate bool    `json:"duplicate"` // true=命中防重复提交，返回首次结果
	State     string  `json:"state"`
	TicketID  *string `json:"platformTicketId,omitempty"`
	Error     string  `json:"error,omitempty"`
}

// TicketRepo 建单执行依赖的仓储能力（便于测试注入内存实现）。
type TicketRepo interface {
	FindByRequestID(ctx context.Context, requestID string) (*TicketRequest, error)
	WriteBack(ctx context.Context, requestID, category string,
		params map[string]any, platformID string, existing *TicketRequest) error
}

// CreateTicket 建单执行（幂等 + 补写语义）：
//  1. requestId 已存在且已建单 → 返回首次结果（duplicate=true）
//  2. requestId 已存在但未建单（上次远端失败）→ 重试建单
//  3. 远端建单成功但本地回写失败 → 本地标记 created（远端已建），下次补写为 saved
//     ——绝不因本地失败而再次调用远端，避免重复建单
func CreateTicket(ctx context.Context, store TicketRepo, platform TicketPlatform,
	requestID, userID, source, category string, params map[string]any) (*CreateResult, error) {
	// ① 幂等检查：重复提交返回首次结果
	existing, err := store.FindByRequestID(ctx, requestID)
	if err != nil && err != sql.ErrNoRows {
		return nil, err
	}
	if existing != nil {
		switch existing.State {
		case "saved":
			// 完全完成 → 重复提交返回首次结果
			return &CreateResult{Duplicate: true, State: existing.State, TicketID: existing.PlatformID}, nil
		case "created":
			// 远端已建单、本地曾保存失败 → 只补写记录，绝不重调平台
			if err := store.WriteBack(ctx, requestID, existing.Category,
				jsonMap(existing.ParamsJSON), derefStr(existing.PlatformID), existing); err != nil {
				return &CreateResult{State: "created", TicketID: existing.PlatformID}, nil
			}
			return &CreateResult{State: "saved", TicketID: existing.PlatformID}, nil
		case "classified":
			// 上次只到分类，继续建单（沿用首次分类，忽略本次输入变化）
			category = existing.Category
			_ = json.Unmarshal([]byte(existing.ParamsJSON), &params)
		case "failed":
			// 上次远端失败 → 本次重试建单
		}
	}
	if !ValidCategory(category) {
		return nil, fmt.Errorf("无效的工单类别: %s", category)
	}

	// ② 远端建单（工单平台适配器）
	platformID, err := platform.Create(ctx, category, params)
	if err != nil {
		// 远端失败：若已有记录标记 failed；无记录则落 failed 记录（防重标识仍生效）
		if existing != nil {
			_ = existing // 远端失败：状态留在 classified，下次同 requestId 重试建单
		}
		return &CreateResult{State: "failed", Error: err.Error()}, nil
	}

	// ③ 本地回写。失败时只标记 created（远端已建单），下次补写为 saved —— 不重创建
	writeErr := store.WriteBack(ctx, requestID, category, params, platformID, existing)
	if writeErr != nil {
		return &CreateResult{State: "created", TicketID: &platformID}, nil
	}
	state := "saved"
	if existing != nil && existing.State == "created" {
		state = "saved" // 补写完成
	}
	return &CreateResult{State: state, TicketID: &platformID}, nil
}

// writeBack 本地记录写入/补写。
func (s *TicketStore) WriteBack(ctx context.Context, requestID, category string,
	params map[string]any, platformID string, existing *TicketRequest) error {
	paramsJSON, _ := json.Marshal(params)
	if existing != nil {
		_, err := s.db.ExecContext(ctx, `
			UPDATE ticket_request SET state='saved', params=?, platform_ticket_id=?, error_message=NULL
			 WHERE request_id = ?`, string(paramsJSON), platformID, requestID)
		return err
	}
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO ticket_request (request_id, user_id, source, raw_text, category, params,
		       platform_ticket_id, state)
		VALUES (?, ?, 'pc', ?, ?, ?, ?, 'saved')`,
		requestID, "system", "(建单时未留存原文)", category, string(paramsJSON), platformID)
	return err
}
