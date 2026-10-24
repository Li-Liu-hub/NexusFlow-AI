// Service 移植自 TokenUsageService.java：配额闸门 + 落账 + 管理面聚合。
// 与 Java 版一样，这里不抛业务错误——配额校验失败默认放行、落账失败只告警。
package tokenadmin

import (
	"context"
	"log/slog"
	"math"
	"time"
)

// Service 是用量/配额的业务层。
type Service struct {
	repo *Repository
	log  *slog.Logger
	now  func() time.Time
}

// NewService 构造服务。
func NewService(repo *Repository, log *slog.Logger) *Service {
	return &Service{repo: repo, log: log, now: time.Now}
}

// CheckQuota 对话入口的配额闸门（内核经 /inner/token/check 调用）。
// 返回 "" = 放行；否则返回给前端的拒绝原因。
func (s *Service) CheckQuota(ctx context.Context, userID string) string {
	quota, err := s.repo.FindActiveUserQuota(ctx, userID)
	if err != nil || quota == nil {
		if err != nil {
			s.log.Warn("配额校验失败（默认放行）", "err", err)
		}
		return ""
	}
	used, err := s.repo.MonthTokens(ctx, userID, monthStartOf(s.now(), ""))
	if err != nil {
		s.log.Warn("配额校验失败（默认放行）", "err", err)
		return ""
	}
	limit := deref(quota.MonthlyTokens)
	if used >= limit {
		return "本月 token 配额已用尽（已用 " + itoa(used) + " / 配额 " + itoa(limit) +
			"）。请联系管理员调整配额（管理端 → Token 用量分配）。"
	}
	return ""
}

// Charge 落一笔用量（内核经 /inner/token/charge 调用）。
// 幂等落账由仓储层的 ON DUPLICATE KEY UPDATE 保证。
func (s *Service) Charge(ctx context.Context, userID, agentCode, model string,
	requests int, promptTokens, completionTokens, totalTokens int64, toolCalls int) map[string]any {
	err := s.repo.AddUsage(ctx, "", userID, s.now(), model, agentCode,
		maxInt(requests, 1), promptTokens, completionTokens, totalTokens, toolCalls)
	if err != nil {
		s.log.Warn("token 用量落账失败（忽略）", "err", err)
	}
	return map[string]any{"charged": true, "totalTokens": totalTokens, "modelCalls": requests}
}

// Overview 用量总览：某月（缺省当月）每用户的用量 + 配额 + 余量状态。
func (s *Service) Overview(ctx context.Context, month string) ([]Row, error) {
	from := monthStartOf(s.now(), month)
	rows, err := s.repo.MonthOverview(ctx, from, from.AddDate(0, 1, 0))
	if err != nil {
		return nil, err
	}
	for _, row := range rows {
		userID, _ := row["user_id"].(string)
		quota, err := s.repo.FindActiveUserQuota(ctx, userID)
		if err != nil {
			return nil, err
		}
		var used int64
		if v, ok := row["total_tokens"].(int64); ok {
			used = v
		}
		if quota == nil {
			row["quota_tokens"] = nil
			row["quota_status"] = "不限"
		} else {
			limit := deref(quota.MonthlyTokens)
			row["quota_tokens"] = limit
			switch {
			case used >= limit:
				row["quota_status"] = "已超限"
			case used >= int64(float64(limit)*0.8):
				row["quota_status"] = "接近上限"
			default:
				row["quota_status"] = "正常"
			}
			if limit > 0 {
				row["usage_percent"] = math.Round(float64(used)*1000/float64(limit)) / 10
			}
		}
	}
	return rows, nil
}

// DailyUsage 某用户最近 n 天的逐日用量（1..200 钳制与 Java 一致）。
func (s *Service) DailyUsage(ctx context.Context, userID string, days int) ([]Row, error) {
	return s.repo.DailyUsage(ctx, userID, clamp(days, 1, 200))
}

// AgentOverview 某月每个智能体的 token 用量排行。
func (s *Service) AgentOverview(ctx context.Context, month string) ([]Row, error) {
	from := monthStartOf(s.now(), month)
	return s.repo.AgentOverview(ctx, from, from.AddDate(0, 1, 0))
}

// ListQuotas 全量配额列表。
func (s *Service) ListQuotas(ctx context.Context) ([]*Quota, error) {
	return s.repo.ListQuotas(ctx)
}

// TargetCodes 配额对象候选；仅 USER 范围由 backend 提供，其余返回空。
func (s *Service) TargetCodes(ctx context.Context, scope string) ([]Row, error) {
	if scope == "USER" { // Java 用 equalsIgnoreCase
		return s.repo.ListAccounts(ctx)
	}
	return []Row{}, nil
}

// SaveQuota 新增或更新配额。字段校验的中文错误文案与 Java 完全一致（前端直接展示）。
func (s *Service) SaveQuota(ctx context.Context, q *Quota) (map[string]any, error) {
	if q.Scope == nil || q.TargetCode == nil || *q.TargetCode == "" {
		return map[string]any{"error": "scope 与 target_code 必填"}, nil
	}
	switch *q.Scope {
	case "USER", "AGENT", "TENANT":
	default:
		return map[string]any{"error": "scope 只支持 USER/AGENT/TENANT"}, nil
	}
	if q.ID == nil {
		id, err := s.repo.InsertQuota(ctx, q)
		if err != nil {
			return nil, err
		}
		return map[string]any{"id": id, "saved": true}, nil
	}
	// 更新：status 未传时保留原行状态——status 列 NOT NULL，置空会 500
	// （2026-10-08 修复：管理台「调整配额」只回传 scope/targetCode/月配额/备注）。
	if q.Status == nil || *q.Status == "" {
		existing, err := s.repo.FindQuotaByID(ctx, *q.ID)
		if err != nil {
			return nil, err
		}
		status := "active"
		if existing != nil && existing.Status != nil && *existing.Status != "" {
			status = *existing.Status
		}
		q.Status = &status
	}
	updated, err := s.repo.UpdateQuota(ctx, q)
	if err != nil {
		return nil, err
	}
	return map[string]any{"updated": updated, "saved": true}, nil
}

// DeleteQuota 删除配额。
func (s *Service) DeleteQuota(ctx context.Context, id int64) (map[string]any, error) {
	deleted, err := s.repo.DeleteQuota(ctx, id)
	if err != nil {
		return nil, err
	}
	return map[string]any{"deleted": deleted}, nil
}

// MonthlyUsage 某月用量汇总（按用户 + 按智能体）。
func (s *Service) MonthlyUsage(ctx context.Context, month string) (map[string]any, error) {
	from := monthStartOf(s.now(), month)
	byUser, err := s.repo.MonthUserUsage(ctx, from, from.AddDate(0, 1, 0))
	if err != nil {
		return nil, err
	}
	byAgent, err := s.repo.MonthAgentUsage(ctx, from, from.AddDate(0, 1, 0))
	if err != nil {
		return nil, err
	}
	return map[string]any{
		"month":   from.Format("2006-01"),
		"byUser":  byUser,
		"byAgent": byAgent,
	}, nil
}

// DailyAgents 某对象某月逐日 × 智能体用量。
func (s *Service) DailyAgents(ctx context.Context, month, userID, agentCode string) ([]Row, error) {
	from := monthStartOf(s.now(), month)
	return s.repo.DailyAgentUsage(ctx, userID, agentCode, from, from.AddDate(0, 1, 0))
}

// FeatureUsage 某月功能点调用次数。
func (s *Service) FeatureUsage(ctx context.Context, month string) ([]Row, error) {
	return s.repo.FeatureUsage(ctx, monthStartOf(s.now(), month).Format("2006-01"))
}

// monthStartOf 解析 yyyy-MM；缺省/非法一律回退到本月月初（与 Java monthStart 一致）。
func monthStartOf(now time.Time, month string) time.Time {
	if month != "" {
		if t, err := time.ParseInLocation("2006-01-02", month+"-01", now.Location()); err == nil {
			return t
		}
	}
	return time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, now.Location())
}

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func itoa(n int64) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
