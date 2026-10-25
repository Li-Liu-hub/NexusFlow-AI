// Package kb 是 aismp-agent 知识检索栈的 Go 实现（简历亮点①的载体）：
// 多查询扩展 → 向量(pgvector) + 关键词(2-gram) 双路 → RRF 融合 → Rerank 精排，
// 以及 /oa/chat/sync 的 RAG 问答（查询重写+分类 → 检索 → 上下文增强）。
//
// 移植自 main-nexus 的 rag/ 包（HybridRetrievalService / KeywordSearcher /
// RrfFuser / RerankClient / OaRagQueryTransformer / OaMultiQueryExpander）与
// InnerKbController（ingest/delete）。每一层可降级的纪律原样保留：
// 扩展失败退原问、关键词异常退纯向量、rerank 失败沿用 RRF 顺序。
package kb

import (
	"math"
	"sort"
	"strings"
)

// RrfFuser：倒数排名融合。score = Σ weight / (k + rank + 1)。
// 只用名次不用分数——向量相似度与关键词命中次数量纲不同，名次天然可比。
func RRFFuse(lists [][]string, weights []float64, k int) []Ranked {
	scores := map[string]float64{}
	if len(lists) == 0 {
		return []Ranked{}
	}
	if k <= 0 {
		k = 60
	}
	for i, list := range lists {
		if len(list) == 0 {
			continue
		}
		w := 1.0
		if i < len(weights) {
			w = weights[i]
		}
		seen := map[string]bool{}
		for rank, key := range list {
			if key == "" || seen[key] {
				continue // 同一路重复 key 只按最好名次计分
			}
			seen[key] = true
			scores[key] += w / float64(k+rank+1)
		}
	}
	out := make([]Ranked, 0, len(scores))
	for key, score := range scores {
		out = append(out, Ranked{Key: key, Score: score})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	return out
}

// Ranked 融合结果条目。
type Ranked struct {
	Key   string
	Score float64
}

// KeywordScore 2-gram 覆盖率打分（查询的相邻二字组在片段中的出现次数，
// 按片段长度衰减）。对中文是够用的近似切词；价值在补向量检索的短板：
// 型号编码、专有名词这类精确字面匹配。
func KeywordScore(query, content string) float64 {
	q := normalizeText(query)
	c := normalizeText(content)
	runes := []rune(q)
	if len(runes) < 2 {
		return 0
	}
	total := 0.0
	for i := 0; i+1 < len(runes); i++ {
		gram := string(runes[i : i+2])
		total += float64(strings.Count(c, gram))
	}
	if total == 0 {
		return 0
	}
	cl := float64(len([]rune(c)))
	if cl <= 0 {
		return 0
	}
	return total / (1 + math.Log(cl))
}

// normalizeText 小写化并去掉空白（英文型号编码大小写无关、空白不参与命中）。
func normalizeText(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if r == ' ' || r == '\t' || r == '\n' || r == '\r' {
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}

// CategoriesOf 从 Spring AI 风格过滤表达式 "category in ['a','b']" 里抠出分类值。
// 解析失败返回空——关键词路降级为不参与（宁可不过滤也不能漏过滤是反的：
// 这里宁可放弃关键词路，也不能绕过分类把不该出现的分类捞进来）。
func CategoriesOf(filterExpression string) []string {
	if filterExpression == "" {
		return nil
	}
	idx := strings.Index(lower(filterExpression), "category in [")
	if idx < 0 {
		return nil
	}
	rest := filterExpression[idx+len("category in ["):]
	end := strings.Index(rest, "]")
	if end < 0 {
		return nil
	}
	var out []string
	for _, part := range strings.Split(rest[:end], ",") {
		v := strings.Trim(strings.TrimSpace(part), "'\"")
		if v != "" {
			out = append(out, v)
		}
	}
	return out
}

func lower(s string) string {
	b := []rune(s)
	for i, r := range b {
		if r >= 'A' && r <= 'Z' {
			b[i] = r + 32
		}
	}
	return string(b)
}

// SplitChunks 切分：段落优先合并到 maxLen，超长段落按句末标点回看硬切。
// 对齐 InnerKbController.split / hardSplit（DEFAULT_CHUNK_SIZE=800，回看 120）。
func SplitChunks(content string, maxLen int) []string {
	if maxLen < 100 {
		maxLen = 800
	}
	text := strings.ReplaceAll(content, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	text = strings.TrimSpace(text)
	var chunks []string
	var buf strings.Builder
	for _, para := range splitBlank(text) {
		p := strings.TrimSpace(para)
		if p == "" {
			continue
		}
		pl := len([]rune(p))
		if bufRuneLen(&buf) > 0 && bufRuneLen(&buf)+pl+2 > maxLen {
			chunks = append(chunks, buf.String())
			buf.Reset()
		}
		if pl > maxLen {
			if bufRuneLen(&buf) > 0 {
				chunks = append(chunks, buf.String())
				buf.Reset()
			}
			chunks = append(chunks, HardSplit(p, maxLen)...)
			continue
		}
		if buf.Len() > 0 {
			buf.WriteString("\n\n")
		}
		buf.WriteString(p)
	}
	if bufRuneLen(&buf) > 0 {
		chunks = append(chunks, buf.String())
	}
	return chunks
}

// bufRuneLen 按字符数计缓冲长度（与 HardSplit 的 rune 口径一致；
// 字节数会把中文分片切成 maxLen/3）。
func bufRuneLen(b *strings.Builder) int {
	return len([]rune(b.String()))
}

// HardSplit 超长段落硬切：在句末标点处回看（窗口 120 字符）尽量不断句。
func HardSplit(p string, maxLen int) []string {
	runes := []rune(p)
	var out []string
	start := 0
	for start < len(runes) {
		end := start + maxLen
		if end > len(runes) {
			end = len(runes)
		}
		if end < len(runes) {
			lookback := start
			if end-120 > lookback {
				lookback = end - 120
			}
			for i := end; i > lookback; i-- {
				c := runes[i-1]
				if c == '。' || c == '！' || c == '？' || c == '；' || c == '\n' ||
					c == '.' || c == '!' || c == '?' || c == ';' {
					end = i
					break
				}
			}
		}
		piece := strings.TrimSpace(string(runes[start:end]))
		if piece != "" {
			out = append(out, piece)
		}
		start = end
	}
	return out
}

func splitBlank(s string) []string {
	var out []string
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] == '\n' && i+1 < len(s) && s[i+1] == '\n' {
			out = append(out, s[start:i])
			i++
			start = i + 1
		}
	}
	out = append(out, s[start:])
	return out
}
