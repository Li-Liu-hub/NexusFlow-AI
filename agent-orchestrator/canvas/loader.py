# DSL 校验器 + 通用装配器：JSON → StateGraph（写死一次的规则层）
#
# 校验器（点发布时跑，全部通过才允许 publish）：
#   1) 结构完整：entry 存在、nodes 非空、edge/conditional 引用的节点都存在
#   2) 白名单：type 必须能解析（具名注册或通用工厂）；router 必须在注册表
#   3) 数字红线：verify_numbers / respond 节点必须存在，且 report 不得直连 respond
#      （护栏是护栏不是流程，画布删不掉 —— 第十四节 C 类）
# 装配器：两个循环（注册节点 + 连边），无递归、无动态生成代码（严禁 eval/exec）。
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from langgraph.graph import StateGraph, START, END

from node_params import set_overrides
from state import OrchestratorState
from canvas.registry import resolve_node, resolve_router, REQUIRED_GUARDS, NODE_PROMPT_KEY


def validate_dsl(dsl: dict) -> list[str]:
    """返回错误列表；空列表 = 可发布。"""
    errs: list[str] = []
    nodes = dsl.get("nodes") or []
    if not nodes:
        return ["nodes 为空：画布至少需要一个节点"]
    ids = [n.get("id") for n in nodes]
    id_set = set(ids)
    if len(id_set) != len(ids):
        errs.append("存在重复的节点 id")

    # 1) entry 存在
    entry = dsl.get("entry")
    if entry not in id_set:
        errs.append(f"entry「{entry}」不是已定义的节点")

    # 2) type 白名单
    for n in nodes:
        if resolve_node(n) is None:
            errs.append(f"节点「{n.get('id')}」的 type「{n.get('type')}」不在注册表（具名/工厂均未命中）")

    # 3) 边引用完整
    for e in dsl.get("edges") or []:
        for side in ("from", "to"):
            v = e.get(side)
            if v != "__end__" and v not in id_set:
                errs.append(f"边 {e.get('from')}→{e.get('to')} 引用了不存在的节点「{v}」")
    for c in dsl.get("conditional") or []:
        if c.get("from") not in id_set:
            errs.append(f"条件路由「{c.get('from')}」引用了不存在的节点")
        if resolve_router(c.get("router", "")) is None:
            errs.append(f"条件路由「{c.get('from')}」的 router「{c.get('router')}」不在注册表")
        for label, target in (c.get("map") or {}).items():
            if target not in id_set:
                errs.append(f"条件路由「{c.get('from')}」分支 {label} 指向不存在的节点「{target}」")

    # 4) 数字红线：护栏在场 + report 不许直连 respond
    missing = REQUIRED_GUARDS - id_set
    if missing:
        errs.append(f"数字红线护栏缺失：{sorted(missing)} —— 这些节点不可从画布删除")
    for e in dsl.get("edges") or []:
        if e.get("from") == "report" and e.get("to") in ("respond", "__end__"):
            errs.append("数字红线违规：report 不得直连 respond —— 必须经过 verify_numbers 比对")
    return errs


def build_graph_from_dsl(dsl: dict):
    """把一份已通过校验的 DSL 装配成可执行图（每请求现场调用，毫秒级）。

    同时收集节点 params.prompt（按能力键）写入提示词覆盖层 ——
    画布上编辑的提示词在下一次装配后即对运行时生效（语义边界见 node_params.py）。
    """
    prompt_overrides: dict[str, str] = {}
    for n in dsl.get("nodes") or []:
        key = NODE_PROMPT_KEY.get(n.get("type", ""))
        prompt = (n.get("params") or {}).get("prompt")
        if key and prompt:
            prompt_overrides[key] = str(prompt)
    set_overrides(prompt_overrides)

    g = StateGraph(OrchestratorState)
    for n in dsl["nodes"]:
        fn = resolve_node(n)
        if fn is None:  # 校验器前置，这里只是双保险
            raise ValueError(f"未知节点类型：{n.get('type')}")
        g.add_node(n["id"], fn)

    g.add_edge(START, dsl["entry"])
    for e in dsl.get("edges") or []:
        g.add_edge(e["from"], END if e["to"] == "__end__" else e["to"])
    for c in dsl.get("conditional") or []:
        g.add_conditional_edges(c["from"], resolve_router(c["router"]), c["map"])
    return g.compile()
