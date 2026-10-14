# 画布方案可行性证明：DSL → 通用加载器重建真实图，与原 build_graph() 拓扑逐边对比
#
# 只读验证：不 invoke（不触发 LLM/HTTP）、不改任何现有代码。
# 回答的问题：「画布发布一份 JSON，后端一个通用函数重建图」——能不能一字不差地重建现网拓扑？
#
# DSL 的角色 = 发布时固化进 orch_graph.dsl_json 的数据（画布树）；
# 注册表的角色 = 能力层（节点实现全部来自现有 graph.py，一行未改）；
# 加载器的角色 = 写死一次的通用代码（两个循环，无递归、无动态生成代码）。
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from langgraph.graph import StateGraph, START, END

import graph as G

# ---------------------------------------------------------------------------
# 1. 画布树（= 用户在画布上调整后点发布 → 固化进 orch_graph.dsl_json 的数据）
# ---------------------------------------------------------------------------
DSL = {
    "entry": "supervise",
    "nodes": [
        {"id": "supervise", "type": "intent_router"},
        {"id": "kb_node", "type": "kb"},
        {"id": "digital_human", "type": "digital_human"},
        {"id": "chart_plan", "type": "chart_plan"},
        {"id": "chart_build", "type": "chart_build"},
        {"id": "fetch", "type": "fetch"},
        {"id": "algorithm", "type": "algorithm"},
        {"id": "report", "type": "report"},
        {"id": "verify_numbers", "type": "verify_numbers"},
        {"id": "order", "type": "order"},
        {"id": "critic", "type": "critic"},
        {"id": "on_rework", "type": "on_rework"},
        {"id": "on_degrade", "type": "on_degrade"},
        {"id": "respond", "type": "respond"},
    ],
    "edges": [
        {"from": "chart_plan", "to": "chart_build"},
        {"from": "chart_build", "to": "respond"},
        {"from": "fetch", "to": "algorithm"},
        {"from": "algorithm", "to": "report"},
        {"from": "report", "to": "verify_numbers"},
        {"from": "verify_numbers", "to": "critic"},
        {"from": "on_degrade", "to": "respond"},
        {"from": "kb_node", "to": "respond"},
        {"from": "digital_human", "to": "respond"},
        {"from": "order", "to": "respond"},
        {"from": "respond", "to": "__end__"},
    ],
    "conditional": [
        {"from": "supervise", "router": "route_after_supervise",
         "map": {"kb": "kb_node", "digital_human": "digital_human",
                 "chart": "chart_plan", "replenish": "fetch", "order": "order"}},
        {"from": "critic", "router": "route_after_critic",
         "map": {"rework": "on_rework", "respond": "on_degrade"}},
        {"from": "on_rework", "router": "route_after_rework",
         "map": {"report": "report", "fetch": "fetch"}},
    ],
}

# ---------------------------------------------------------------------------
# 2. 注册表（能力层）：节点实现全部来自现有 graph.py —— 一行未改
#    画布上每个节点配的 type 就是这里的 key；换实现 = 换注册表条目
# ---------------------------------------------------------------------------
NODE_REGISTRY = {
    "intent_router": G.supervise,
    "kb": G.kb_node,
    "digital_human": G.digital_human_node,
    "chart_plan": G.chart_plan,
    "chart_build": G.chart_build,
    "fetch": G.fetch,
    "algorithm": G.algorithm,
    "report": G.report,
    "verify_numbers": G.verify_numbers,
    "order": G.order,
    "critic": G.critic,
    "on_rework": G.on_rework,
    "on_degrade": G.on_degrade,
    "respond": G.respond,
}
ROUTER_REGISTRY = {
    "route_after_supervise": G.route_after_supervise,
    "route_after_critic": G.route_after_critic,
    "route_after_rework": G.route_after_rework,
}

# ---------------------------------------------------------------------------
# 3. 通用加载器（写死一次；画布发布的数据驱动它）—— 两个循环，无递归、无动态生成代码
# ---------------------------------------------------------------------------
def build_graph_from_dsl(dsl: dict):
    g = StateGraph(G.OrchestratorState)
    for n in dsl["nodes"]:
        g.add_node(n["id"], NODE_REGISTRY[n["type"]])
    g.add_edge(START, dsl["entry"])
    for e in dsl["edges"]:
        g.add_edge(e["from"], END if e["to"] == "__end__" else e["to"])
    for c in dsl["conditional"]:
        g.add_conditional_edges(c["from"], ROUTER_REGISTRY[c["router"]], c["map"])
    return g.compile()


# ---------------------------------------------------------------------------
# 4. 拓扑对比：原 build_graph() vs DSL 重建图（节点集合 + 全部边含条件分支标签）
# ---------------------------------------------------------------------------
def topo(compiled):
    gg = compiled.get_graph()
    nodes = sorted(gg.nodes)
    edges = sorted(
        (e.source, e.target, bool(getattr(e, "conditional", False)),
         getattr(e, "label", "") or "")
        for e in gg.edges
    )
    return nodes, edges


if __name__ == "__main__":
    orig = G.build_graph()
    rebuilt = build_graph_from_dsl(DSL)

    on, oe = topo(orig)
    rn, re_ = topo(rebuilt)

    print(f"原 build_graph()  : {len(on)} 节点 / {len(oe)} 边")
    print(f"DSL 重建图        : {len(rn)} 节点 / {len(re_)} 边")
    print("节点集合一致:", on == rn)
    print("边集合一致  :", oe == re_)
    if oe != re_:
        print("仅原图有:", sorted(set(oe) - set(re_)))
        print("仅重建有:", sorted(set(re_) - set(oe)))

    ok = on == rn and oe == re_
    print()
    print("结论:", "拓扑完全一致 —— 画布发布的 DSL 可以一字不差地重建现网图"
          if ok else "存在差异，见上（差异大概率来自条件边 label 的表示，属可修细节）")
