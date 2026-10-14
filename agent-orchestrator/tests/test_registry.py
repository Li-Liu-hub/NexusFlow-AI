# canvas/registry.py 的核心纯函数单测：模板解析（{{node_id.field.sub}}）+ 节点解析
#
# 为什么优先测这一段：它是「画布 DSL 与运行时 state」之间唯一的桥。
# 一旦解析口径漂移，症状是「画布上明明配了变量、跑起来却是 None」——
# 这类问题在链路测试里表现为「取数为空」，排查成本极高，必须用单测锁死。
#
# 覆盖口径（与 registry.py 顶部注释一一对应）：
#   1. 整串恰为一个占位符 → 保留原始类型（对象/数组直接透传，不做字符串化）
#   2. 混合字符串内嵌占位符 → 字符串替换，取不到置空串
#   3. 数字段 = 数组下标
#   4. 容器（list/dict）递归解析
#   5. 取不到的一律 None（显式可见，方便排查）
#   6. resolve_node：具名优先 → 通用工厂 → 未知返回 None
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from canvas import registry


# ── resolve_template：类型保留 ──

def test_full_placeholder_keeps_object_type():
    """整串就是一个占位符时，必须原样透传对象，而不是转成 str。"""
    state = {"fetch_result": {"rows": [1, 2, 3]}}
    got = registry.resolve_template("{{fetch_result}}", state)
    assert got == {"rows": [1, 2, 3]}
    assert isinstance(got, dict), "对象不能被字符串化"


def test_full_placeholder_keeps_list_type():
    state = {"data": [1, 2, 3]}
    got = registry.resolve_template("{{data}}", state)
    assert got == [1, 2, 3]
    assert isinstance(got, list)


def test_full_placeholder_keeps_numeric_type():
    """数字段不能被转成字符串（下游可能要直接参与计算）。"""
    state = {"report": {"total": 42}}
    got = registry.resolve_template("{{report.total}}", state)
    assert got == 42
    assert isinstance(got, int)


def test_full_placeholder_allows_surrounding_whitespace():
    state = {"a": {"b": "v"}}
    assert registry.resolve_template("  {{a.b}}  ", state) == "v"


# ── resolve_template：字符串内嵌 ──

def test_embedded_placeholder_is_substituted():
    state = {"report": {"name": "补货"}, "meta": {}}
    assert registry.resolve_template("结果：{{report.name}} 完成", state) == "结果：补货 完成"


def test_embedded_missing_becomes_empty_string():
    """混合串里取不到 → 空串（不是 'None' 字样，也不是原样保留占位符）。"""
    got = registry.resolve_template("[{{missing.field}}]", {})
    assert got == "[]"


def test_embedded_multiple_placeholders():
    state = {"a": 1, "b": 2}
    assert registry.resolve_template("{{a}}+{{b}}", state) == "1+2"


# ── 数组下标 ──

def test_list_index_access():
    state = {"rows": [{"code": "A"}, {"code": "B"}]}
    assert registry.resolve_template("{{rows.1.code}}", state) == "B"


def test_list_index_out_of_range_is_none():
    state = {"rows": [1]}
    assert registry.resolve_template("{{rows.9}}", state) is None


def test_list_index_non_numeric_is_none():
    """list 上用非数字段 → 取不到（不能抛异常）。"""
    state = {"rows": [1, 2]}
    assert registry.resolve_template("{{rows.first}}", state) is None


# ── 容器递归 ──

def test_dict_is_resolved_recursively():
    state = {"u": {"name": "张三"}}
    got = registry.resolve_template({"who": "{{u.name}}", "n": 1}, state)
    assert got == {"who": "张三", "n": 1}


def test_list_is_resolved_recursively():
    state = {"u": {"id": 7}}
    got = registry.resolve_template(["{{u.id}}", "x"], state)
    assert got == [7, "x"], "列表里的整串占位符同样保留类型（这里是 int）"


def test_nested_container():
    state = {"a": {"b": "deep"}}
    got = registry.resolve_template({"outer": [{"inner": "{{a.b}}"}]}, state)
    assert got == {"outer": [{"inner": "deep"}]}


# ── 非字符串原样返回 ──

def test_scalars_pass_through():
    assert registry.resolve_template(123, {}) == 123
    assert registry.resolve_template(True, {}) is True
    assert registry.resolve_template(None, {}) is None


# ── 取不到的值一律 None / 空串 ──

def test_missing_dict_key_is_none():
    assert registry.resolve_template("{{nope}}", {}) is None


def test_deep_missing_is_none():
    """中途某一级缺失 → None，而不是继续往下崩。"""
    assert registry.resolve_template("{{a.b.c}}", {"a": {}}) is None


def test_traverse_into_scalar_is_none():
    """对非容器继续取字段 → None。"""
    assert registry.resolve_template("{{a.b}}", {"a": "str"}) is None


# ── resolve_node：三级解析 ──

def test_resolve_node_named_wins():
    """具名节点优先于同名通用工厂。"""
    node = registry.resolve_node({"id": "fetch", "type": "fetch"})
    assert node is not None


def test_resolve_node_http_factory_builds_callable():
    """http 是通用工厂：返回的是新构造的可调用，不是注册表里的静态函数。"""
    node = registry.resolve_node({"id": "x", "type": "http",
                                  "params": {"path": "/api/foo", "method": "GET"}})
    assert callable(node)


def test_resolve_node_unknown_type_returns_none():
    """未知 type 返回 None —— 由 loader 负责报错，注册表只如实回答「我不认识」。"""
    assert registry.resolve_node({"id": "x", "type": "no_such_type"}) is None


def test_required_guards_are_declared():
    """数字红线护栏：这两个节点名必须在 REQUIRED_GUARDS 里，且注册表中真实存在。"""
    assert registry.REQUIRED_GUARDS == {"verify_numbers", "respond"}
    for name in registry.REQUIRED_GUARDS:
        assert name in registry.NODE_REGISTRY, f"护栏节点 {name} 未注册，校验器会误判"


def test_prompt_key_targets_are_registered():
    """提示词能力键的 type 必须都是已注册节点，否则画布配了提示词也不生效。"""
    for ntype in registry.NODE_PROMPT_KEY:
        assert ntype in registry.NODE_REGISTRY, f"{ntype} 有提示词键但没注册节点"


def test_router_registry_entries_are_callable():
    for name, fn in registry.ROUTER_REGISTRY.items():
        assert callable(fn), f"路由 {name} 不可调用"
