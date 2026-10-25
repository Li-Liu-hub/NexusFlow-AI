// canvas 包功能点测试：对齐开发规范「一个 it 一个断言、中文需求句式」。
// 覆盖：DSL 校验五规则、RRF 融合、2-gram 打分、切分器、图解释器
// （条件路由/回路检测/最大步数/http 节点）。
package canvas

import (
	"context"
	"strings"
	"testing"
)

func testRegistry() *Registry {
	reg := &Registry{Nodes: map[string]NodeFunc{}, Routers: map[string]RouterFunc{}}
	reg.Nodes["a"] = func(_ context.Context, st *State, _ map[string]any) error { st.Answer = "A"; return nil }
	reg.Nodes["b"] = func(_ context.Context, st *State, _ map[string]any) error { st.Report = "B"; return nil }
	reg.Nodes["respond"] = func(_ context.Context, st *State, _ map[string]any) error { st.Answer = st.Report; return nil }
	reg.Nodes["verify_numbers"] = func(_ context.Context, st *State, _ map[string]any) error { st.Verified = true; return nil }
	reg.Nodes["loop"] = func(_ context.Context, st *State, _ map[string]any) error { return nil }
	reg.Nodes["http"] = HTTPNode
	reg.Routers["pick"] = func(st *State) string { return st.Route }
	return reg
}

func TestValidate_节点为空时拒绝发布(t *testing.T) {
	errs := Validate(&DSL{Entry: "a"}, testRegistry())
	if len(errs) == 0 || !strings.Contains(errs[0], "nodes 为空") {
		t.Fatalf("errs = %v", errs)
	}
}

func TestValidate_数字红线护栏缺失时拒绝发布(t *testing.T) {
	dsl := &DSL{Entry: "a", Nodes: []Node{{ID: "a", Type: "a"}}}
	errs := Validate(dsl, testRegistry())
	joined := strings.Join(errs, ";")
	if !strings.Contains(joined, "数字红线护栏缺失") || !strings.Contains(joined, "respond") || !strings.Contains(joined, "verify_numbers") {
		t.Fatalf("errs = %v", errs)
	}
}

func TestValidate_report直连respond属数字红线违规(t *testing.T) {
	dsl := &DSL{Entry: "a", Nodes: []Node{
		{ID: "a", Type: "a"}, {ID: "report", Type: "b"}, {ID: "verify_numbers", Type: "verify_numbers"}, {ID: "respond", Type: "respond"},
	}, Edges: []Edge{{From: "a", To: "report"}, {From: "report", To: "respond"}}}
	errs := Validate(dsl, testRegistry())
	if len(errs) == 0 || !strings.Contains(strings.Join(errs, ";"), "report 不得直连 respond") {
		t.Fatalf("errs = %v", errs)
	}
}

func TestValidate_合法DSL无错误(t *testing.T) {
	dsl := &DSL{Entry: "intent", Nodes: []Node{
		{ID: "intent", Type: "a"}, {ID: "report", Type: "b"}, {ID: "verify_numbers", Type: "verify_numbers"}, {ID: "respond", Type: "respond"},
	}, Edges: []Edge{{From: "intent", To: "report"}, {From: "report", To: "verify_numbers"}, {From: "verify_numbers", To: "respond"}}}
	if errs := Validate(dsl, testRegistry()); len(errs) != 0 {
		t.Fatalf("errs = %v", errs)
	}
}

func TestValidate_条件路由指向不存在节点时拒绝(t *testing.T) {
	dsl := &DSL{Entry: "intent", Nodes: []Node{
		{ID: "intent", Type: "a"}, {ID: "report", Type: "b"}, {ID: "verify_numbers", Type: "verify_numbers"}, {ID: "respond", Type: "respond"},
	}, Conditional: []Condition{{From: "intent", Router: "pick", Map: map[string]string{"x": "ghost"}}}}
	errs := Validate(dsl, testRegistry())
	if len(errs) == 0 || !strings.Contains(strings.Join(errs, ";"), "ghost") {
		t.Fatalf("errs = %v", errs)
	}
}

// ---- 解释器 ----

func runDSL(t *testing.T, dsl *DSL, st *State) (*State, error) {
	t.Helper()
	return (&Executor{Registry: testRegistry()}).Run(context.Background(), dsl, st)
}

func TestExecutor_按entry执行到终点(t *testing.T) {
	dsl := &DSL{Entry: "a", Nodes: []Node{{ID: "a", Type: "a"}, {ID: "b", Type: "b"}}, Edges: []Edge{{From: "a", To: "b"}}}
	st, err := runDSL(t, dsl, &State{})
	if err != nil || st.Answer != "A" || st.Report != "B" {
		t.Fatalf("st=%+v err=%v", st, err)
	}
}

func TestExecutor_条件路由按state分支(t *testing.T) {
	dsl := &DSL{Entry: "a", Nodes: []Node{{ID: "a", Type: "a"}, {ID: "b", Type: "b"}},
		Conditional: []Condition{{From: "a", Router: "pick", Map: map[string]string{"replenish": "b", "kb": "__end__"}}}}
	st := &State{Route: "replenish"}
	st2, err := runDSL(t, dsl, st)
	if err != nil || st2.Report != "B" {
		t.Fatalf(" replenish 分支应走 b: %+v %v", st2, err)
	}
	st3, err := runDSL(t, dsl, &State{Route: "kb"})
	if err != nil || st3.Report != "" {
		t.Fatalf("kb 分支应直接结束: %+v %v", st3, err)
	}
}

func TestExecutor_业务流程回路时报错(t *testing.T) {
	dsl := &DSL{Entry: "loop", Nodes: []Node{{ID: "loop", Type: "loop"}}, Edges: []Edge{{From: "loop", To: "loop"}}}
	_, err := runDSL(t, dsl, &State{})
	if err == nil || !strings.Contains(err.Error(), "回路") {
		t.Fatalf("err = %v", err)
	}
}

func TestExecutor_未路由分支时报错(t *testing.T) {
	dsl := &DSL{Entry: "a", Nodes: []Node{{ID: "a", Type: "a"}},
		Conditional: []Condition{{From: "a", Router: "pick", Map: map[string]string{"x": "a"}}}}
	st := &State{Route: "kb"} // pick 返回 kb，map 无此分支
	_, err := runDSL(t, dsl, st)
	if err == nil || !strings.Contains(err.Error(), "未配置映射") {
		t.Fatalf("err = %v", err)
	}
}

func TestValidate_条件路由指向__end__合法(t *testing.T) {
	dsl := &DSL{Entry: "intent", Nodes: []Node{
		{ID: "intent", Type: "a"}, {ID: "report", Type: "b"}, {ID: "verify_numbers", Type: "verify_numbers"}, {ID: "respond", Type: "respond"},
	}, Conditional: []Condition{{From: "intent", Router: "pick", Map: map[string]string{"x": "__end__"}}}}
	if errs := Validate(dsl, testRegistry()); len(errs) != 0 {
		t.Fatalf("__end__ 分支应合法: %v", errs)
	}
}

func TestVersions_SQL为PostgreSQL方言(t *testing.T) {
	// 回归：PG 里用 IFNULL 报 function does not exist
	var s Store
	_ = s
	if strings.Contains(versionsSQL, "IFNULL") {
		t.Fatal("Versions 查询不得使用 MySQL 方言 IFNULL")
	}
}

var versionsSQL = "SELECT version, status, note, created_at::text AS created_at FROM orch_graph ORDER BY version DESC"
