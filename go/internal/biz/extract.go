// AI 抽取适配器与字段合并：正文 → 集团智能体；HTML 审签单 → Qwen；
// JSON Schema 结构校验；非空字段合并为 21 项合同信息。
package biz

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"time"
)

// ---- 21 项合同信息（Schema 即字段清单；类型与是否必填在这里定死）----

// FieldSchema 单字段定义。
type FieldSchema struct {
	Name     string `json:"name"`
	Required bool   `json:"required"`
	Numeric  bool   `json:"numeric,omitempty"`
	Date     bool   `json:"date,omitempty"`
}

// ContractFields 21 项合同信息（顺序即回写顺序）。
var ContractFields = []FieldSchema{
	{"contract_no", true, false, false},         // 合同编号
	{"contract_name", true, false, false},       // 合同名称
	{"contract_type", true, false, false},       // 合同类型
	{"party_a_name", true, false, false},        // 甲方名称
	{"party_a_uscc", false, false, false},       // 甲方统一社会信用代码
	{"party_b_name", true, false, false},        // 乙方名称
	{"party_b_uscc", false, false, false},       // 乙方统一社会信用代码
	{"amount", true, true, false},               // 合同金额
	{"currency", false, false, false},           // 币种
	{"sign_date", true, false, true},            // 签订日期
	{"effective_date", false, false, true},      // 生效日期
	{"expire_date", false, false, true},         // 到期日期
	{"purchase_method", false, false, false},    // 采购方式
	{"handler", false, false, false},            // 经办人
	{"handler_dept", false, false, false},       // 经办部门
	{"payment_terms", false, false, false},      // 付款方式
	{"performance_bond", false, true, false},    // 履约保证金
	{"breach_clause", false, false, false},      // 违约条款
	{"dispute_resolution", false, false, false}, // 争议解决方式
	{"copies", false, true, false},              // 合同份数
	{"remark", false, false, false},             // 备注
}

// ---- 抽取器 ----

// LLMExtractor 双路抽取器：正文走 GroupBaseURL（集团智能体适配层），
// HTML 审签单走 AuditBaseURL（Qwen3 侧）——都是 OpenAI 兼容端点。
type LLMExtractor struct {
	GroupBaseURL string
	GroupAPIKey  string
	GroupModel   string
	AuditBaseURL string
	AuditAPIKey  string
	AuditQwen    string // Qwen 模型名（避免与方法重名）
	Client       *http.Client
	Now          func() time.Time
}

// BodyModel / AuditModel 回写账本用的模型标识。
func (e *LLMExtractor) BodyModel() string  { return e.GroupModel }
func (e *LLMExtractor) AuditModel() string { return e.AuditQwen }

const bodyExtractSystem = `你是合同信息抽取引擎。从合同正文抽取字段，只输出一个 JSON 对象，` +
	`键为以下英文小写下划线字段名（没有的字段省略，不要编造）：` +
	"contract_no, contract_name, contract_type, party_a_name, party_a_uscc, party_b_name, " +
	"party_b_uscc, amount, currency, sign_date, effective_date, expire_date, purchase_method, " +
	"handler, handler_dept, payment_terms, performance_bond, breach_clause, dispute_resolution, " +
	"copies, remark。" +
	"约束：amount/performance_bond/copies 为数字（纯数值不带单位）；" +
	"sign_date/effective_date/expire_date 为 YYYY-MM-DD。不要输出 JSON 以外的任何内容。"

const auditExtractSystem = `你是审签单解析引擎。输入是合同审签单的 HTML 片段，` +
	`剥离标签后抽取字段，只输出一个 JSON 对象（字段名同合同抽取：contract_no 等 21 项，` +
	"没有的省略）。amount/copies 为数字；日期为 YYYY-MM-DD。不要输出 JSON 以外的内容。"

// ExtractBody 正文抽取（集团智能体）。
func (e *LLMExtractor) ExtractBody(ctx context.Context, content string) (map[string]any, error) {
	return e.extract(ctx, e.GroupBaseURL, e.GroupAPIKey, e.GroupModel, bodyExtractSystem,
		truncateText(content, 24000))
}

// ExtractAudit HTML 审签单抽取（Qwen 侧；先剥 HTML 标签）。
func (e *LLMExtractor) ExtractAudit(ctx context.Context, html string) (map[string]any, error) {
	if strings.TrimSpace(html) == "" {
		return nil, fmt.Errorf("无审签单 HTML")
	}
	return e.extract(ctx, e.AuditBaseURL, e.AuditAPIKey, e.AuditQwen, auditExtractSystem,
		stripHTML(truncateText(html, 16000)))
}

func (e *LLMExtractor) extract(ctx context.Context, baseURL, apiKey, model, system, user string) (map[string]any, error) {
	if baseURL == "" || apiKey == "" {
		return nil, fmt.Errorf("抽取端点未配置")
	}
	body, _ := json.Marshal(map[string]any{
		"model": model,
		"messages": []map[string]string{
			{"role": "system", "content": system},
			{"role": "user", "content": user},
		},
		"temperature": 0.1,
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(baseURL, "/")+"/v1/chat/completions", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+apiKey)
	resp, err := e.client().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<21))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("LLM %d: %s", resp.StatusCode, clipErr(string(data)))
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(data, &out); err != nil || len(out.Choices) == 0 {
		return nil, fmt.Errorf("LLM 响应解析失败")
	}
	return parseExtractedJSON(out.Choices[0].Message.Content)
}

func (e *LLMExtractor) client() *http.Client {
	if e.Client != nil {
		return e.Client
	}
	return &http.Client{Timeout: 120 * time.Second}
}

// parseExtractedJSON 从模型输出里抠出 JSON（容忍 ```json 围栏与前后噪声）并校验结构。
func parseExtractedJSON(content string) (map[string]any, error) {
	text := strings.TrimSpace(content)
	text = strings.TrimPrefix(text, "```json")
	text = strings.TrimPrefix(text, "```")
	text = strings.TrimSuffix(text, "```")
	start := strings.Index(text, "{")
	end := strings.LastIndex(text, "}")
	if start < 0 || end <= start {
		return nil, fmt.Errorf("输出中不含 JSON 对象")
	}
	var fields map[string]any
	if err := json.Unmarshal([]byte(text[start:end+1]), &fields); err != nil {
		return nil, fmt.Errorf("JSON 解析失败: %w", err)
	}
	if err := ValidateFields(fields); err != nil {
		return nil, err
	}
	return fields, nil
}

// ValidateFields JSON Schema 校验（结构层）：未知键拒绝（防止模型幻觉字段）、
// 必填缺失拒绝、数字/日期类型校验。
func ValidateFields(fields map[string]any) error {
	known := map[string]FieldSchema{}
	for _, f := range ContractFields {
		known[f.Name] = f
	}
	for k, v := range fields {
		schema, ok := known[k]
		if !ok {
			return fmt.Errorf("未知字段「%s」不在 21 项合同信息定义中", k)
		}
		if v == nil {
			delete(fields, k)
			continue
		}
		if schema.Numeric {
			switch v.(type) {
			case float64, int64, int:
			default:
				return fmt.Errorf("字段「%s」应为数字，得到 %T", k, v)
			}
		}
		if schema.Date {
			s, ok := v.(string)
			if !ok || !dateRe.MatchString(s) {
				return fmt.Errorf("字段「%s」应为 YYYY-MM-DD 日期，得到 %v", k, v)
			}
		}
		if s, ok := v.(string); ok && strings.TrimSpace(s) == "" {
			delete(fields, k) // 空串视为未抽到
		}
	}
	var missing []string
	for _, f := range ContractFields {
		if f.Required {
			if _, ok := fields[f.Name]; !ok {
				missing = append(missing, f.Name)
			}
		}
	}
	if len(missing) > 0 {
		return fmt.Errorf("必填字段缺失: %s", strings.Join(missing, ","))
	}
	return nil
}

var dateRe = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)

// MergeFields 非空字段合并：两路都抽到的字段取正文优先（正文是权威源），
// 审签单只补正文缺失项。输出固定按 21 项定义排序（缺项不出现在结果里）。
func MergeFields(body, audit map[string]any) map[string]any {
	merged := map[string]any{}
	for _, f := range ContractFields {
		if v, ok := body[f.Name]; ok && v != nil {
			merged[f.Name] = v
		} else if v, ok := audit[f.Name]; ok && v != nil {
			merged[f.Name] = v
		}
	}
	return merged
}

// ---- 文本工具 ----

var htmlTagRe = regexp.MustCompile(`<[^>]*>`)

// stripHTML 剥离 HTML 标签并压缩空白（审签单 → 纯文本）。
func stripHTML(s string) string {
	s = htmlTagRe.ReplaceAllString(s, " ")
	s = strings.ReplaceAll(s, "&nbsp;", " ")
	s = strings.ReplaceAll(s, "&amp;", "&")
	s = strings.ReplaceAll(s, "&lt;", "<")
	s = strings.ReplaceAll(s, "&gt;", ">")
	return strings.Join(strings.Fields(s), " ")
}

func truncateText(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

// ---- LLM 工单分类适配（ticket.go 共用）----

// ChatOnce 单轮补全（指定端点）。
func ChatOnce(ctx context.Context, baseURL, apiKey, model, system, user string) (string, error) {
	body, _ := json.Marshal(map[string]any{
		"model": model,
		"messages": []map[string]string{
			{"role": "system", "content": system},
			{"role": "user", "content": user},
		},
		"temperature": 0.1,
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimSuffix(baseURL, "/")+"/v1/chat/completions", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+apiKey)
	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("LLM %d: %s", resp.StatusCode, clipErr(string(data)))
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(data, &out); err != nil || len(out.Choices) == 0 {
		return "", fmt.Errorf("LLM 响应解析失败")
	}
	return out.Choices[0].Message.Content, nil
}

// jsonMarshal / jsonUnmarshal 局部别名（避免到处引 encoding/json）。
func jsonMarshal(v any) ([]byte, error) { return json.Marshal(v) }

func jsonUnmarshal(data []byte, v any) error { return json.Unmarshal(data, v) }
