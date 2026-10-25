// Package canvas 是画布编排与版本管理的 Go 实现（简历亮点③的载体）。
//
// 移植自 Python 编排器 canvas/ 包（loader.validate_dsl / registry / store），
// DSL 形态、校验规则、版本语义逐条对齐：
//
//	DSL: {entry, nodes:[{id,type,params}], edges:[{from,to|"__end__"}],
//	      conditional:[{from,router,map:{label:target}}]}
//	校验: 结构完整 / type 白名单 / 边引用完整 / 数字红线护栏（verify_numbers、
//	      respond 不可删，report 不得直连 respond）
//	版本: orch_graph 表 draft 单槽 + published 版本固化（旧版本永久保留），
//	      会话粘版本（orch_conversation.graph_version），回滚 = 旧版本再发布
//	执行: 从 entry 步进，条件路由查 map，最大步数 20（对齐 recursionLimit），
//	      节点访问超限判回路
package canvas

import (
	"context"
	"fmt"
	"sort"
	"strings"
)

// ---- DSL ----

// DSL 是画布 JSON 的 Go 形态。
type DSL struct {
	Entry       string      `json:"entry"`
	Nodes       []Node      `json:"nodes"`
	Edges       []Edge      `json:"edges,omitempty"`
	Conditional []Condition `json:"conditional,omitempty"`
}

// Node 画布节点。
type Node struct {
	ID     string         `json:"id"`
	Type   string         `json:"type"`
	Params map[string]any `json:"params,omitempty"`
}

// Edge 固定边；to 为 "__end__" 表示结束。
type Edge struct {
	From string `json:"from"`
	To   string `json:"to"`
}

// Condition 条件路由：router 名查注册表，map 是「分支标签 → 目标节点」。
type Condition struct {
	From   string            `json:"from"`
	Router string            `json:"router"`
	Map    map[string]string `json:"map"`
}

// Validate 对齐 loader.validate_dsl 的五条规则；返回错误列表（空 = 可发布）。
func Validate(dsl *DSL, registry *Registry) []string {
	var errs []string
	if len(dsl.Nodes) == 0 {
		return []string{"nodes 为空：画布至少需要一个节点"}
	}
	ids := map[string]bool{}
	var dup []string
	for _, n := range dsl.Nodes {
		if ids[n.ID] && !contains(dup, n.ID) {
			dup = append(dup, n.ID)
		}
		ids[n.ID] = true
	}
	if len(dup) > 0 {
		errs = append(errs, "存在重复的节点 id: "+strings.Join(dup, ","))
	}
	if !ids[dsl.Entry] {
		errs = append(errs, fmt.Sprintf("entry「%s」不是已定义的节点", dsl.Entry))
	}
	for _, n := range dsl.Nodes {
		if !registry.HasNode(n.Type) {
			errs = append(errs, fmt.Sprintf("节点「%s」的 type「%s」不在注册表（具名/工厂均未命中）", n.ID, n.Type))
		}
	}
	for _, e := range dsl.Edges {
		for _, v := range []string{e.From, e.To} {
			if v != "__end__" && !ids[v] {
				errs = append(errs, fmt.Sprintf("边 %s→%s 引用了不存在的节点「%s」", e.From, e.To, v))
			}
		}
	}
	for _, cnd := range dsl.Conditional {
		if !ids[cnd.From] {
			errs = append(errs, fmt.Sprintf("条件路由「%s」引用了不存在的节点", cnd.From))
		}
		if !registry.HasRouter(cnd.Router) {
			errs = append(errs, fmt.Sprintf("条件路由「%s」的 router「%s」不在注册表", cnd.From, cnd.Router))
		}
		for label, target := range cnd.Map {
			if !ids[target] {
				errs = append(errs, fmt.Sprintf("条件路由「%s」分支 %s 指向不存在的节点「%s」", cnd.From, label, target))
			}
		}
	}
	// 数字红线：护栏在场 + report 不得直连 respond/__end__
	var missing []string
	for g := range RequiredGuards {
		if !ids[g] {
			missing = append(missing, g)
		}
	}
	if len(missing) > 0 {
		sort.Strings(missing)
		errs = append(errs, fmt.Sprintf("数字红线护栏缺失：%v —— 这些节点不可从画布删除", missing))
	}
	for _, e := range dsl.Edges {
		if e.From == "report" && (e.To == "respond" || e.To == "__end__") {
			errs = append(errs, "数字红线违规：report 不得直连 respond —— 必须经过 verify_numbers 比对")
		}
	}
	return errs
}

func contains(list []string, v string) bool {
	for _, s := range list {
		if s == v {
			return true
		}
	}
	return false
}

// ---- 注册表（registry.py 同构）----

// NodeFunc 节点执行函数：读改 state，返回给前端的进度说明。
type NodeFunc func(ctx context.Context, st *State, params map[string]any) error

// RouterFunc 条件路由：返回 map 里命中的分支标签。
type RouterFunc func(st *State) string

// Registry 能力层注册表：type → 节点实现 / router 名 → 路由函数。
type Registry struct {
	Nodes   map[string]NodeFunc
	Routers map[string]RouterFunc
}

// RequiredGuards 数字红线护栏（画布删不掉）。
var RequiredGuards = map[string]bool{"verify_numbers": true, "respond": true}

// HasNode / HasRouter 白名单查询。
func (r *Registry) HasNode(t string) bool {
	if _, ok := r.Nodes[t]; ok {
		return true
	}
	return t == "http" // 通用工厂
}

func (r *Registry) HasRouter(name string) bool {
	_, ok := r.Routers[name]
	return ok
}

// State 图执行状态（orchestrator state 的 Go 形态）。
type State struct {
	Query      string
	Route      string
	UserID     string
	Warehouse  string
	DataScope  []string
	Inventory  map[string]any
	Suggestion map[string]any
	Report     string
	Chart      map[string]any
	Answer     string
	Cards      []any
	Verified   bool
	ReworkCount int
	Trace      []string
	// HTTPDo 通用 http 节点的执行出口（由 Engine 注入，签名走网关）。
	HTTPDo func(ctx context.Context, method, url, body string) (string, error)
}
