# 画布加载器测试：拓扑一致性 + 校验器红线
#
# 核心断言（等价性，来自 canvas_proof.py 的实证并固化为回归测试）：
#   DEFAULT_DSL 经 build_graph_from_dsl() 重建，与现网 build_graph() 拓扑一字不差。
# 本测试不 invoke（不触发 LLM/HTTP），纯结构对比 —— 与 graph.py 的运行时行为解耦。
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from graph import build_graph
from canvas.default_dsl import DEFAULT_DSL
from canvas.loader import build_graph_from_dsl, validate_dsl


def _topo(compiled):
    gg = compiled.get_graph()
    nodes = sorted(gg.nodes)
    edges = sorted(
        (e.source, e.target, bool(getattr(e, "conditional", False)),
         getattr(e, "label", "") or "")
        for e in gg.edges
    )
    return nodes, edges


def test_default_dsl_rebuilds_production_topology():
    on, oe = _topo(build_graph())
    rn, re_ = _topo(build_graph_from_dsl(DEFAULT_DSL))
    assert on == rn, f"节点集合不一致：{set(on) ^ set(rn)}"
    assert oe == re_, f"边集合不一致：{set(oe) ^ set(re_)}"


def test_validator_rejects_unknown_type():
    dsl = {**DEFAULT_DSL,
           "nodes": DEFAULT_DSL["nodes"] + [{"id": "rogue", "type": "not_registered"}]}
    errs = validate_dsl(dsl)
    assert any("not_registered" in e for e in errs)


def test_validator_rejects_missing_guards():
    ids = {n["id"] for n in DEFAULT_DSL["nodes"]}
    for guard in ("verify_numbers", "respond"):
        stripped = {**DEFAULT_DSL,
                    "nodes": [n for n in DEFAULT_DSL["nodes"] if n["id"] != guard]}
        errs = validate_dsl(stripped)
        assert any(guard in e and "红线" in e for e in errs), f"删除 {guard} 未被拦截"


def test_validator_rejects_report_to_respond_shortcut():
    dsl = {**DEFAULT_DSL,
           "edges": DEFAULT_DSL["edges"] + [{"from": "report", "to": "respond"}]}
    errs = validate_dsl(dsl)
    assert any("report" in e and "respond" in e for e in errs)


def test_validator_rejects_bad_entry():
    dsl = {**DEFAULT_DSL, "entry": "no_such_node"}
    errs = validate_dsl(dsl)
    assert any("no_such_node" in e for e in errs)


def test_default_dsl_passes_validation():
    assert validate_dsl(DEFAULT_DSL) == []
