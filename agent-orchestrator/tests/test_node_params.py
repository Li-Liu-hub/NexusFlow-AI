# 提示词覆盖层测试：画布 params.prompt → prompt_for 生效/回退
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from node_params import set_overrides, prompt_for, current_overrides


def test_override_and_fallback():
    set_overrides({"route": "自定义路由提示词"})
    assert prompt_for("route", "默认路由") == "自定义路由提示词"
    assert prompt_for("critic", "默认质检") == "默认质检"  # 未覆盖的键回默认
    set_overrides(None)
    assert prompt_for("route", "默认路由") == "默认路由"


def test_empty_prompt_ignored():
    set_overrides({"route": "", "critic": "有效覆盖"})
    assert prompt_for("route", "默认路由") == "默认路由"  # 空串视为未覆盖
    assert prompt_for("critic", "默认质检") == "有效覆盖"
    set_overrides(None)
    assert current_overrides() == {}
