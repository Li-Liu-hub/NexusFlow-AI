# 画布编排包：把「画布 JSON（DSL）」变成可执行的 LangGraph 图
#
# 三层分工（设计定稿见 docs/Dify编排迁移-工程量评估.md 第十五节）：
#   registry.py  能力层（代码）：type 名 → 节点工厂 / 路由函数 —— 开发者写
#   loader.py    规则层（代码）：校验 DSL + 两个循环装配 StateGraph —— 写死一次
#   store.py     数据层（PG）：orch_graph 表，draft/published 版本 —— 画布用户写
#
# 安全约定（第十三/十六节）：图定义只通过「发布接口」写入，
# 运行时后端自己查库装配；请求参数里永远不携带图定义。
