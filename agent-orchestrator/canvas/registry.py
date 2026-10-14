# 能力层注册表：type 名 → 节点实现 / 路由函数
#
# 两类注册：
#   1) 具名节点：现网 14 个节点函数原样注册（graph.py 一行未改）——
#      画布 DSL 里 {"id": "fetch", "type": "fetch"} 即复用现有实现；
#   2) 通用类型工厂：type="http" 等，按 params 动态构造节点函数
#      （http 已实测：scripts/http_node_proof.py，签名自动注入走网关）。
#
# 换实现 = 改本文件的注册条目；画布与 DSL 结构零改动（能力层与流程层解耦）。
import json
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import graph as G
from config import settings
from sdk.sign import build_auth_headers


# ---------------------------------------------------------------------------
# 具名节点注册：id/type → graph.py 的节点函数（能力实现不在这里写，只在这里挂）
# ---------------------------------------------------------------------------
NODE_REGISTRY: dict[str, object] = {
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

# 条件路由注册：router 名 → 路由函数（分支逻辑在代码里，画布只配分支映射表）
ROUTER_REGISTRY: dict[str, object] = {
    "route_after_supervise": G.route_after_supervise,
    "route_after_critic": G.route_after_critic,
    "route_after_rework": G.route_after_rework,
}

# 数字红线护栏：这些节点必须在 DSL 中存在（校验器强制，画布删不掉）
REQUIRED_GUARDS = {"verify_numbers", "respond"}

# 提示词能力键映射：type 名 → node_params.prompt_for 的 key
# （画布 params.prompt 覆盖对应能力键的提示词；键名与 graph.py 五处调用点一致）
NODE_PROMPT_KEY: dict[str, str] = {
    "intent_router": "route",
    "chart_plan": "chart_plan",
    "report": "report_summary",
    "order": "order_parse",
    "critic": "critic",
}


# ---------------------------------------------------------------------------
# 变量映射（第三期）：params.body 支持 {{node_id.field.sub}} 占位符
#   - 从 state（上游节点输出）按点路径取值；数字段 = 数组下标
#   - 整串恰为一个占位符 → 保留原始类型（对象/数组直接透传）
#   - 混合字符串内嵌占位符 → 字符串替换，取不到置空串
#   - 容器递归解析；取不到值的一律置 None（显式可见，方便排查）
# ---------------------------------------------------------------------------
import re as _re

_TEMPLATE_RE = _re.compile(r"\{\{\s*([a-zA-Z0-9_.]+)\s*\}\}")


def _lookup(state: dict, path: str):
    cur = state
    for part in path.split("."):
        if isinstance(cur, dict):
            cur = cur.get(part)
        elif isinstance(cur, list):
            try:
                cur = cur[int(part)]
            except (ValueError, IndexError):
                return None
        else:
            return None
        if cur is None:
            return None
    return cur


def resolve_template(value, state: dict):
    if isinstance(value, str):
        m = _TEMPLATE_RE.fullmatch(value.strip())
        if m:
            return _lookup(state, m.group(1))

        def _sub(match):
            v = _lookup(state, match.group(1))
            return "" if v is None else str(v)

        return _TEMPLATE_RE.sub(_sub, value)
    if isinstance(value, list):
        return [resolve_template(v, state) for v in value]
    if isinstance(value, dict):
        return {k: resolve_template(v, state) for k, v in value.items()}
    return value


# ---------------------------------------------------------------------------
# 通用 http 类型工厂：params = {path, body?, method?, timeout?}
#   - 基地址唯一来源：settings.GATEWAY_BASE_URL（编排层唯一地址配置，画布不出现 IP）
#   - 服务归属由网关路由表决定（path 前缀 → 服务），Nacos 在网关身后
#   - 签名复用现有 sdk/sign.py（accessKey/nonce/timestamp/sign，body 塞 header）
#   - body 支持 {{node_id.field}} 变量映射（上游输出 → 请求体，第三期）
# ---------------------------------------------------------------------------
def make_http_node(params: dict):
    url = settings.GATEWAY_BASE_URL.rstrip("/") + params["path"]
    method = (params.get("method") or "GET").upper()
    body_tpl = params.get("body") or {}
    timeout = params.get("timeout", 10)

    def node(state: dict) -> dict:
        payload = None
        if method in ("POST", "PUT", "PATCH"):
            body = resolve_template(body_tpl, state)
            body["query"] = state.get("query", "")  # 向后兼容：query 始终透传
            payload = json.dumps(body)
        headers = build_auth_headers(payload)   # 现有签名：AK/SK + nonce + 时间戳 + HMAC
        resp = httpx.request(
            method, url,
            content=payload.encode("utf-8") if payload is not None else None,
            headers=headers, timeout=timeout)
        resp.raise_for_status()
        return {f"{state.get('_node_id', 'http')}_result": resp.json()}

    return node


# 通用 agent 类型工厂：把任务透传给内核里任一已注册数字员工（agent_code 可配）
#   params = {agent_code, prompt?}
#   - 复用 graph.py 的 digital_human_node（链路/降级/scope/计量完全一致），
#     仅覆盖 meta.agent_code 与（可选）查询模板 —— 不再写死 terminal_manager；
#   - 这是 Dify 式「Agent 节点」：内核里配好的人设/技能/工具在这里被编排进来。
def make_agent_node(params: dict):
    agent_code = params.get("agent_code") or "terminal_manager"
    prompt_tpl = (params.get("prompt") or "").strip()

    def node(state: dict) -> dict:
        cur = state
        if prompt_tpl and prompt_tpl != "{{query}}":
            q = resolve_template(prompt_tpl, state)
            if isinstance(q, str) and q.strip():
                cur = {**state, "query": q}
        return G.digital_human_node({**cur, "meta": {**(cur.get("meta") or {}), "agent_code": agent_code}})

    return node


# 通用类型工厂表：type 名 → 工厂（工厂签名 (params) -> 节点函数）
TYPE_FACTORIES: dict[str, object] = {
    "http": make_http_node,
    "agent": make_agent_node,
}


def resolve_node(node_def: dict):
    """按节点定义解析出可调用：具名优先，其次通用工厂。未知 type 返回 None。"""
    ntype = node_def.get("type")
    if ntype in NODE_REGISTRY:
        return NODE_REGISTRY[ntype]
    factory = TYPE_FACTORIES.get(ntype)
    if factory is not None:
        return factory(node_def.get("params") or {})
    return None


def resolve_router(name: str):
    return ROUTER_REGISTRY.get(name)
