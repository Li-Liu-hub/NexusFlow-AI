// 图解释器 + 节点注册表装配：从 entry 步进执行，条件路由查 map，
// 最大步数 20（对齐 Python 版 recursionLimit），节点访问超限判回路。
package canvas

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
)

// MaxSteps 对齐 Python 版 recursionLimit=20。
const MaxSteps = 20

// maxNodeVisits 单节点最大访问次数（超过判回路：业务流程环）。
const maxNodeVisits = 4

// Executor 执行一份 DSL。
type Executor struct {
	Registry *Registry
	OnNode   func(nodeID string, produced []string) // SSE progress 回调（可空）
}

// Run 从 entry 执行到 __end__ 或步数上限。
// 返回最终 state；回路/超步/未知节点返回错误。
func (x *Executor) Run(ctx context.Context, dsl *DSL, st *State) (*State, error) {
	nodes := map[string]*Node{}
	for i := range dsl.Nodes {
		nodes[dsl.Nodes[i].ID] = &dsl.Nodes[i]
	}
	conds := map[string]*Condition{}
	for i := range dsl.Conditional {
		conds[dsl.Conditional[i].From] = &dsl.Conditional[i]
	}
	next := dsl.Entry
	visits := map[string]int{}
	for step := 0; step < MaxSteps; step++ {
		if next == "__end__" {
			return st, nil
		}
		node := nodes[next]
		if node == nil {
			return st, fmt.Errorf("画布节点「%s」不存在", next)
		}
		visits[next]++
		if visits[next] > maxNodeVisits {
			return st, fmt.Errorf("检测到业务流程回路：节点「%s」访问 %d 次仍未收敛（最大步数 %d）",
				next, visits[next], MaxSteps)
		}
		fn := x.Registry.Nodes[node.Type]
		if fn == nil && node.Type == "http" {
			fn = HTTPNode
		}
		if fn == nil {
			return st, fmt.Errorf("未知节点类型：%s", node.Type)
		}
		before := snapshot(st)
		if err := fn(ctx, st, node.Params); err != nil {
			return st, fmt.Errorf("节点「%s」执行失败: %w", next, err)
		}
		if x.OnNode != nil {
			x.OnNode(next, diffKeys(before, st))
		}
		// 固定边优先；否则查条件路由；都没有 → 结束
		var edge *Edge
		for i := range dsl.Edges {
			if dsl.Edges[i].From == next {
				edge = &dsl.Edges[i]
				break
			}
		}
		if edge != nil {
			next = edge.To
			continue
		}
		if cnd := conds[next]; cnd != nil {
			router := x.Registry.Routers[cnd.Router]
			if router == nil {
				return st, fmt.Errorf("路由「%s」未注册", cnd.Router)
			}
			label := router(st)
			target, ok := cnd.Map[label]
			if !ok {
				return st, fmt.Errorf("路由「%s」返回分支「%s」，画布未配置映射", cnd.Router, label)
			}
			next = target
			continue
		}
		return st, nil // 无出边 = 终点
	}
	return st, fmt.Errorf("超过最大步数 %d（可能存在回路）", MaxSteps)
}

func snapshot(st *State) map[string]any {
	return map[string]any{
		"route": st.Route, "answer": st.Answer, "report": st.Report,
		"inventory": st.Inventory, "suggestion": st.Suggestion, "chart": st.Chart,
	}
}

// diffKeys 对齐 Java 版 diffKeys：报告本节点产出了哪些 key（SSE progress 的 produced）。
func diffKeys(before map[string]any, after *State) []string {
	afterMap := map[string]any{
		"route": after.Route, "answer": after.Answer, "report": after.Report,
		"inventory": after.Inventory, "suggestion": after.Suggestion, "chart": after.Chart,
	}
	var produced []string
	for k, v := range afterMap {
		if fmt.Sprint(v) != fmt.Sprint(before[k]) {
			produced = append(produced, k)
		}
	}
	return produced
}

// httpNode 通用 HTTP 节点工厂（params: {url, method, headers?, body?}，
// body 支持 {{node_id.field}} 占位符从 state 取值）。
func HTTPNode(ctx context.Context, st *State, params map[string]any) error {
	get := func(k string) string {
		if v, ok := params[k].(string); ok {
			return v
		}
		return ""
	}
	urlStr := get("url")
	if urlStr == "" {
		return fmt.Errorf("http 节点缺少 params.url")
	}
	method := strings.ToUpper(get("method"))
	if method == "" {
		method = "GET"
	}
	body := get("body")
	if body != "" {
		body = expandPlaceholders(body, st)
	}
	// 经编排器统一的 HTTP 出口执行（由 Engine 注入）
	if httpDo := st.HTTPDo; httpDo != nil {
		out, err := httpDo(ctx, method, urlStr, body)
		if err != nil {
			return err
		}
		st.Answer = out
	}
	return nil
}

// expandPlaceholders {{node_id.field}} → state 取值（简版：只支持一层 key）。
func expandPlaceholders(s string, st *State) string {
	repl := map[string]string{
		"{{query}}":      st.Query,
		"{{answer}}":     st.Answer,
		"{{report}}":     st.Report,
		"{{route}}":      st.Route,
		"{{warehouse}}":  st.Warehouse,
	}
	for k, v := range repl {
		s = strings.ReplaceAll(s, k, v)
	}
	return s
}

// ToJSON DSL 序列化（存储用）。
func ToJSON(dsl *DSL) ([]byte, error) { return json.Marshal(dsl) }

// FromJSON 反序列化。
func FromJSON(b []byte) (*DSL, error) {
	var dsl DSL
	if err := json.Unmarshal(b, &dsl); err != nil {
		return nil, err
	}
	return &dsl, nil
}
