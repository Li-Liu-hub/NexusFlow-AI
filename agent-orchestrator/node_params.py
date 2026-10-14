# 节点参数覆盖层：画布 params → 运行时提示词覆盖
#
# 语义（本期边界，诚实声明）：
#   - 覆盖按「能力键」生效（route / chart_plan / report_summary / order_parse / critic），
#     绑定提示词语义而非节点实例 id —— 画布上节点改名不影响提示词归属；
#   - 覆盖值为「最新一次装配」的 DSL 所携带的 params.prompt ——
#     并发多版本时提示词取全局最新态（拓扑粘版本，提示词全局）；
#     文案级差异可接受，数字级内容永远不经过 LLM（数字红线不变）；
#   - 装配器（canvas/loader）在每次 build 时调用 set_overrides 整体替换。
#
# 线程安全：单键替换为 GIL 原子操作；set_overrides 用 clear+update，
# 极小窗口内读到部分新值的影响是"半新半旧的提示词文案"，无数字风险。

_OVERRIDES: dict[str, str] = {}


def set_overrides(mapping: dict[str, str] | None) -> None:
    """整体替换覆盖表（loader 每次装配时调用；None = 清空回默认）。"""
    _OVERRIDES.clear()
    if mapping:
        _OVERRIDES.update({k: v for k, v in mapping.items() if v})


def prompt_for(key: str, default: str) -> str:
    """节点函数取提示词：画布覆盖优先，缺省回现网默认。"""
    return _OVERRIDES.get(key) or default


def current_overrides() -> dict[str, str]:
    return dict(_OVERRIDES)
