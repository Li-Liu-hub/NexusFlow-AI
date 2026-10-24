// Package tokenadmin 承载 token 用量分配面（/admin/token/**）与内部计量闸门（/inner/token/**）。
//
// 移植自 aismp-backend 的 TokenAdminController + InnerTokenController +
// TokenUsageService + TokenUsageRepository（2026-10-06 批 A 从内核迁到 backend）。
// 表：a_token_usage_daily（用量账本）+ a_token_quota（配额）+ a_user（对象候选），均在 api_db。
//
// 计量链路：内核在每轮对话出口 POST /api/inner/token/charge 落账；
// 下一轮对话入口先 GET /api/inner/token/check，用当月累计比对 a_token_quota。
// 失败语义：落账/校验失败只打日志不抛——计量是旁路，不能因为记账失败让对话报错。
package tokenadmin

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// Quota 对应 a_token_quota 一行（Java 侧 QuotaPO）。
//
// scope 决定 target_code 的解释：USER → user_id；AGENT → agent_code；TENANT → ent_code。
// MonthlyTokens 为 nil 时按 0 写库（=封禁）；时间字段输出 Jackson LocalDateTime 的形态
// （"2006-01-02T15:04:05"，无时区后缀）。
type Quota struct {
	ID            *int64  `json:"id"`
	Scope         *string `json:"scope"`
	TargetCode    *string `json:"targetCode"`
	MonthlyTokens *int64  `json:"monthlyTokens"`
	Status        *string `json:"status"`
	Remark        *string `json:"remark"`
	CreateTime    *string `json:"createTime"`
	UpdateTime    *string `json:"updateTime"`
}

// Repository 是用量账本与配额的仓储，SQL 与 TokenUsageRepository.java 逐句对应。
type Repository struct {
	db *sql.DB
}

// NewRepository 构造仓储。
func NewRepository(db *sql.DB) *Repository { return &Repository{db: db} }

// Row 是查询结果的通用行。Java 侧 queryForList 返回 List<Map<String,Object>>，
// 列名即 key（user_id / total_tokens …），服务层再往里塞派生列——这里用 map 保持同构。
type Row map[string]any

// AddUsage 累加一笔用量（同天同人同模型同智能体累加到同一行）。
//
// 注意 agent_code 自 2026-10-08 起纳入 uk_daily 唯一键（local-init/86），
// 此前同用户同天多智能体会被并成一行，表现为计量盲区——Go 版直接落在新口径上。
func (r *Repository) AddUsage(ctx context.Context, entCode, userID string, date time.Time,
	model, agentCode string, requests int,
	promptTokens, completionTokens, totalTokens int64, toolCalls int) error {
	if entCode == "" {
		entCode = "default"
	}
	if userID == "" {
		userID = "anonymous"
	}
	if model == "" {
		model = "unknown"
	}
	_, err := r.db.ExecContext(ctx, `
		INSERT INTO a_token_usage_daily
		  (ent_code, user_id, usage_date, model, agent_code,
		   request_count, total_prompt_tokens, total_completion_tokens,
		   total_tokens, total_tool_calls)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON DUPLICATE KEY UPDATE
		   request_count            = request_count + VALUES(request_count),
		   total_prompt_tokens      = total_prompt_tokens + VALUES(total_prompt_tokens),
		   total_completion_tokens  = total_completion_tokens + VALUES(total_completion_tokens),
		   total_tokens             = total_tokens + VALUES(total_tokens),
		   total_tool_calls         = total_tool_calls + VALUES(total_tool_calls)`,
		entCode, userID, date.Format("2006-01-02"), model, agentCode,
		requests, promptTokens, completionTokens, totalTokens, toolCalls)
	if err != nil {
		return fmt.Errorf("tokenadmin: 落账失败: %w", err)
	}
	return nil
}

// MonthTokens 某用户当月累计 token（从月初起算）。
func (r *Repository) MonthTokens(ctx context.Context, userID string, monthStart time.Time) (int64, error) {
	if userID == "" {
		userID = "anonymous"
	}
	var total sql.NullFloat64
	err := r.db.QueryRowContext(ctx, `
		SELECT COALESCE(SUM(total_tokens), 0) FROM a_token_usage_daily
		 WHERE user_id = ? AND usage_date >= ?`,
		userID, monthStart.Format("2006-01-02")).Scan(&total)
	if err != nil {
		return 0, fmt.Errorf("tokenadmin: 查月累计失败: %w", err)
	}
	return int64(total.Float64), nil
}

// DailyUsage 某用户最近 n 天的每日用量。
func (r *Repository) DailyUsage(ctx context.Context, userID string, days int) ([]Row, error) {
	if userID == "" {
		userID = "anonymous"
	}
	return r.queryRows(ctx, `
		SELECT usage_date, model, agent_code, request_count,
		       total_prompt_tokens, total_completion_tokens, total_tokens, total_tool_calls
		  FROM a_token_usage_daily
		 WHERE user_id = ?
		 ORDER BY usage_date DESC, model
		 LIMIT ?`, userID, days)
}

// MonthOverview 某月 [from, to) 全体用户用量总览（管理台配额页数据源）。
func (r *Repository) MonthOverview(ctx context.Context, from, to time.Time) ([]Row, error) {
	return r.queryRows(ctx, `
		SELECT user_id,
		       SUM(request_count)              AS requests,
		       SUM(total_prompt_tokens)        AS prompt_tokens,
		       SUM(total_completion_tokens)    AS completion_tokens,
		       SUM(total_tokens)               AS total_tokens,
		       MAX(usage_date)                 AS last_active,
		       SUBSTRING_INDEX(GROUP_CONCAT(DISTINCT model), ',', 1) AS main_model
		  FROM a_token_usage_daily
		 WHERE usage_date >= ? AND usage_date < ?
		 GROUP BY user_id
		 ORDER BY total_tokens DESC`,
		from.Format("2006-01-02"), to.Format("2006-01-02"))
}

// AgentOverview 某月智能体用量排行；未带 agent_code 的落账行不计入。
func (r *Repository) AgentOverview(ctx context.Context, from, to time.Time) ([]Row, error) {
	return r.queryRows(ctx, `
		SELECT agent_code,
		       SUM(request_count)           AS requests,
		       SUM(total_tokens)            AS total_tokens,
		       COUNT(DISTINCT user_id)      AS users,
		       MAX(usage_date)              AS last_active
		  FROM a_token_usage_daily
		 WHERE usage_date >= ? AND usage_date < ?
		   AND agent_code IS NOT NULL AND agent_code <> ''
		 GROUP BY agent_code
		 ORDER BY total_tokens DESC`,
		from.Format("2006-01-02"), to.Format("2006-01-02"))
}

// ListAccounts 平台账号列表（a_user，USER 维度配额对象候选）。
// 数字员工/租户目录在内核库（terminal_db），backend 不跨库读取。
func (r *Repository) ListAccounts(ctx context.Context) ([]Row, error) {
	return r.queryRows(ctx, `
		SELECT user_code, user_name, role
		  FROM a_user
		 WHERE status = 'active'
		 ORDER BY role, user_code`)
}

const quotaColumns = `id, scope, target_code, monthly_tokens, status, remark, create_time, update_time`

func scanQuota(scanner interface{ Scan(...any) error }) (*Quota, error) {
	var q Quota
	var (
		id, monthly                                   sql.NullFloat64
		scope, target, status, remark                 sql.NullString
		createTime, updateTime                        sql.NullTime
	)
	if err := scanner.Scan(&id, &scope, &target, &monthly, &status, &remark, &createTime, &updateTime); err != nil {
		return nil, err
	}
	i := int64(id.Float64)
	q.ID = &i
	if scope.Valid {
		s := scope.String
		q.Scope = &s
	}
	if target.Valid {
		s := target.String
		q.TargetCode = &s
	}
	m := int64(monthly.Float64)
	q.MonthlyTokens = &m
	if status.Valid {
		s := status.String
		q.Status = &s
	}
	if remark.Valid {
		s := remark.String
		q.Remark = &s
	}
	if createTime.Valid {
		s := createTime.Time.Format("2006-01-02T15:04:05")
		q.CreateTime = &s
	}
	if updateTime.Valid {
		s := updateTime.Time.Format("2006-01-02T15:04:05")
		q.UpdateTime = &s
	}
	return &q, nil
}

// ListQuotas 全量配额。
func (r *Repository) ListQuotas(ctx context.Context) ([]*Quota, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT `+quotaColumns+` FROM a_token_quota ORDER BY scope, target_code`)
	if err != nil {
		return nil, fmt.Errorf("tokenadmin: 查配额列表失败: %w", err)
	}
	defer rows.Close()
	var list []*Quota
	for rows.Next() {
		q, err := scanQuota(rows)
		if err != nil {
			return nil, fmt.Errorf("tokenadmin: 读配额行失败: %w", err)
		}
		list = append(list, q)
	}
	return list, rows.Err()
}

// FindQuotaByID 按主键查配额行；不存在返回 nil。
func (r *Repository) FindQuotaByID(ctx context.Context, id int64) (*Quota, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT `+quotaColumns+` FROM a_token_quota WHERE id = ?`, id)
	q, err := scanQuota(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("tokenadmin: 查配额失败: %w", err)
	}
	return q, nil
}

// FindActiveUserQuota 某用户生效中的 USER 维度配额；没有配额行返回 nil（=不限）。
func (r *Repository) FindActiveUserQuota(ctx context.Context, userID string) (*Quota, error) {
	if userID == "" {
		userID = "anonymous"
	}
	row := r.db.QueryRowContext(ctx,
		`SELECT `+quotaColumns+` FROM a_token_quota
		 WHERE scope='USER' AND target_code=? AND status='active'`, userID)
	q, err := scanQuota(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("tokenadmin: 查生效配额失败: %w", err)
	}
	return q, nil
}

// InsertQuota 新增配额，返回自增主键。
func (r *Repository) InsertQuota(ctx context.Context, q *Quota) (int64, error) {
	monthly := int64(0)
	if q.MonthlyTokens != nil {
		monthly = *q.MonthlyTokens
	}
	status := "active"
	if q.Status != nil && *q.Status != "" {
		status = *q.Status
	}
	var remark any
	if q.Remark != nil {
		remark = *q.Remark
	}
	res, err := r.db.ExecContext(ctx,
		`INSERT INTO a_token_quota (scope, target_code, monthly_tokens, status, remark) VALUES (?, ?, ?, ?, ?)`,
		deref(q.Scope), deref(q.TargetCode), monthly, status, remark)
	if err != nil {
		return 0, fmt.Errorf("tokenadmin: 新增配额失败: %w", err)
	}
	return res.LastInsertId()
}

// UpdateQuota 按主键更新配额。
func (r *Repository) UpdateQuota(ctx context.Context, q *Quota) (int64, error) {
	monthly := int64(0)
	if q.MonthlyTokens != nil {
		monthly = *q.MonthlyTokens
	}
	var remark any
	if q.Remark != nil {
		remark = *q.Remark
	}
	res, err := r.db.ExecContext(ctx, `
		UPDATE a_token_quota
		   SET scope = ?, target_code = ?, monthly_tokens = ?, status = ?, remark = ?
		 WHERE id = ?`,
		deref(q.Scope), deref(q.TargetCode), monthly, deref(q.Status), remark, deref(q.ID))
	if err != nil {
		return 0, fmt.Errorf("tokenadmin: 更新配额失败: %w", err)
	}
	return res.RowsAffected()
}

// DeleteQuota 删除配额。
func (r *Repository) DeleteQuota(ctx context.Context, id int64) (int64, error) {
	res, err := r.db.ExecContext(ctx, `DELETE FROM a_token_quota WHERE id = ?`, id)
	if err != nil {
		return 0, fmt.Errorf("tokenadmin: 删除配额失败: %w", err)
	}
	return res.RowsAffected()
}

// MonthUserUsage / MonthAgentUsage 某月按用户 / 按智能体汇总（配额表「本月已使用」列）。
func (r *Repository) MonthUserUsage(ctx context.Context, from, to time.Time) ([]Row, error) {
	return r.queryRows(ctx, `
		SELECT user_id,
		       SUM(request_count) AS requests,
		       SUM(total_tokens)   AS total_tokens
		  FROM a_token_usage_daily
		 WHERE usage_date >= ? AND usage_date < ?
		 GROUP BY user_id`,
		from.Format("2006-01-02"), to.Format("2006-01-02"))
}

func (r *Repository) MonthAgentUsage(ctx context.Context, from, to time.Time) ([]Row, error) {
	return r.queryRows(ctx, `
		SELECT agent_code,
		       SUM(request_count) AS requests,
		       SUM(total_tokens)   AS total_tokens
		  FROM a_token_usage_daily
		 WHERE usage_date >= ? AND usage_date < ?
		   AND agent_code IS NOT NULL AND agent_code <> ''
		 GROUP BY agent_code`,
		from.Format("2006-01-02"), to.Format("2006-01-02"))
}

// DailyAgentUsage 某对象某月逐日 × 智能体用量（调整弹窗的日柱状图 + 智能体分布饼图）。
// userId / agentCode 为空表示不限该维度；usage_date 用 DATE_FORMAT 输出字符串。
func (r *Repository) DailyAgentUsage(ctx context.Context, userID, agentCode string, from, to time.Time) ([]Row, error) {
	query := `
		SELECT DATE_FORMAT(usage_date, '%Y-%m-%d') AS usage_date, agent_code,
		       SUM(request_count) AS requests,
		       SUM(total_tokens)   AS total_tokens
		  FROM a_token_usage_daily
		 WHERE usage_date >= ? AND usage_date < ?`
	args := []any{from.Format("2006-01-02"), to.Format("2006-01-02")}
	if userID != "" {
		query += " AND user_id = ?"
		args = append(args, userID)
	}
	if agentCode != "" {
		query += " AND agent_code = ?"
		args = append(args, agentCode)
	}
	query += " GROUP BY usage_date, agent_code ORDER BY usage_date"
	return r.queryRows(ctx, query, args...)
}

// FeatureUsage 某月功能点调用次数（a_feature_usage 演示账本，按 usage_month 过滤）。
func (r *Repository) FeatureUsage(ctx context.Context, usageMonth string) ([]Row, error) {
	return r.queryRows(ctx, `
		SELECT feature_code, feature_name, usage_month, call_count, last_called_at
		  FROM a_feature_usage
		 WHERE usage_month = ?
		 ORDER BY call_count DESC, feature_code`, usageMonth)
}

// queryRows 执行查询并把每行转成「列名 → Go 值」的 map，
// 数值保持 JSON 数字、DATE 输出 ISO 日期串、DATETIME 输出 ISO 日期时间串——
// 与 Jackson 在本项目里的实际输出形态一致（管理台按字符串切片取月/日）。
func (r *Repository) queryRows(ctx context.Context, query string, args ...any) ([]Row, error) {
	rows, err := r.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("tokenadmin: 查询失败: %w", err)
	}
	defer rows.Close()
	cols, err := rows.Columns()
	if err != nil {
		return nil, fmt.Errorf("tokenadmin: 读列名失败: %w", err)
	}
	types, err := rows.ColumnTypes()
	if err != nil {
		return nil, fmt.Errorf("tokenadmin: 读列类型失败: %w", err)
	}
	dbTypes := make([]string, len(types))
	for i, t := range types {
		dbTypes[i] = strings.ToUpper(t.DatabaseTypeName())
	}
	var list []Row
	for rows.Next() {
		raw := make([]any, len(cols))
		ptr := make([]any, len(cols))
		for i := range raw {
			ptr[i] = &raw[i]
		}
		if err := rows.Scan(ptr...); err != nil {
			return nil, fmt.Errorf("tokenadmin: 读行失败: %w", err)
		}
		row := Row{}
		for i, c := range cols {
			row[c] = normalizeDBValue(raw[i], dbTypes[i])
		}
		list = append(list, row)
	}
	return list, rows.Err()
}

// normalizeDBValue 把 driver.Value 规整为 Jackson 等价的 JSON 形态。
// dbType 是列的数据库类型名：数值列（SUM 的 DECIMAL、COUNT 的 BIGINT）转 JSON 数字，
// 字符/日期列保持字符串——Java 的 queryForList 对两类列就是这个区分。
func normalizeDBValue(v any, dbType string) any {
	switch t := v.(type) {
	case nil:
		return nil
	case []byte:
		s := string(t)
		if isNumericDBType(dbType) {
			if n, err := strconv.ParseInt(s, 10, 64); err == nil {
				return n
			}
			if f, err := strconv.ParseFloat(s, 64); err == nil {
				return f
			}
		}
		return s
	case time.Time:
		// DATE 列的时间部分为零值 → 只输出日期；DATETIME 带时间。
		if t.Hour() == 0 && t.Minute() == 0 && t.Second() == 0 && t.Nanosecond() == 0 {
			return t.Format("2006-01-02")
		}
		return t.Format("2006-01-02T15:04:05")
	case float64:
		if t == float64(int64(t)) {
			return int64(t)
		}
		return t
	default:
		return v
	}
}

func isNumericDBType(dbType string) bool {
	switch {
	case strings.Contains(dbType, "INT"),
		strings.Contains(dbType, "DECIMAL"),
		strings.Contains(dbType, "NUMERIC"),
		strings.Contains(dbType, "FLOAT"),
		strings.Contains(dbType, "DOUBLE"),
		strings.Contains(dbType, "REAL"):
		return true
	}
	return false
}

func deref[T any](p *T) T {
	if p == nil {
		var zero T
		return zero
	}
	return *p
}
