# 变量映射测试：{{node_id.field}} 占位符解析（第三期）
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from canvas.registry import resolve_template

STATE = {
    "query": "查库存",
    "fetch_result": {"rows": [{"terminal": "T001", "qty": 3}], "total": 5},
    "order_info": {"id": "TR-001"},
}


def test_full_placeholder_preserves_type():
    assert resolve_template("{{fetch_result.rows}}", STATE) == STATE["fetch_result"]["rows"]
    assert resolve_template("{{order_info}}", STATE) == STATE["order_info"]


def test_nested_path_and_list_index():
    assert resolve_template("{{fetch_result.rows.0.terminal}}", STATE) == "T001"
    assert resolve_template("{{fetch_result.total}}", STATE) == 5


def test_inline_substitution():
    assert resolve_template("库存 {{fetch_result.total}} 件", STATE) == "库存 5 件"
    assert resolve_template("单号 {{order_info.id}}-X", STATE) == "单号 TR-001-X"


def test_missing_becomes_empty_or_none():
    assert resolve_template("{{fetch_result.rows.9.terminal}}", STATE) is None  # 整串取不到 → None
    assert resolve_template("库存 {{nope.here}} 件", STATE) == "库存  件"        # 内嵌取不到 → 空串


def test_container_recursion():
    body = {"warehouse": "{{order_info.id}}", "items": ["{{fetch_result.rows.0.terminal}}"], "n": 3}
    assert resolve_template(body, STATE) == {"warehouse": "TR-001", "items": ["T001"], "n": 3}
