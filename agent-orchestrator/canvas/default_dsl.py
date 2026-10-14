# 默认图定义：与现网 build_graph()（router+critic 全开）一字不差的等价 DSL
#
# 用途：
#   1) 首次启用画布时作为 v1 种子（publish 接口无草稿时的默认值）
#   2) 拓扑一致性测试的基准（tests/test_canvas_loader.py，与 canvas_proof.py 同源）
#
# 等价性已实证：canvas_proof.py 跑过「原 build_graph() vs 本 DSL 重建」逐边对比一致。
# 改动本文件的唯一正当理由：graph.py 的拓扑真的变了 —— 两个地方必须同步。

DEFAULT_DSL: dict = {
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
