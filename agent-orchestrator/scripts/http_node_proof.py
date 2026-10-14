# HTTP 节点规范验证：DSL 里的 http 节点 → 逻辑服务名解析 → 签名注入 → 真实 HTTP 调用
#
# 链路：DSL(params.service+path) → 加载器闭包构造节点函数 → invoke 时 httpx 调「网关」
#       → mock 网关回显收到的 path/签名头/body —— 证明参数注入与签名自动携带可行
#
# 现实对照：
#   - demo 里 SERVICE_BASE 的 value 是本地 mock；真实项目 = 网关地址（配置项）
#   - 「先用 Nacos 找服务」发生在网关内部（lb://aismp-agent），编排层不直连 Nacos
#     —— 绕网关 = 断 AK/SK 鉴权 + 断调用计量（M4 数据源），见 tools/terminal_api.py 头注释
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from sdk.sign import build_auth_headers          # 现有签名模块，一行未改
from typing import TypedDict
from langgraph.graph import StateGraph, START, END
import httpx

# ---------------------------------------------------------------------------
# 1. mock 网关：只干一件事 —— 回显它收到了什么（证明签名头/路径/参数都到位）
# ---------------------------------------------------------------------------
received = []

class MockGateway(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8")
        received.append({"path": self.path, "accessKey": self.headers.get("accessKey", ""),
                         "sign": self.headers.get("sign", ""),
                         "timestamp": self.headers.get("timestamp", ""),
                         "body": body})
        resp = json.dumps({"code": 200, "data": {"rows": [{"terminal": "T001", "qty": 3}],
                          "echo": {"path": self.path,
                                   "signed": bool(self.headers.get("sign")) and bool(self.headers.get("accessKey"))}},
                           "msg": "ok"}, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, *a):  # 静默默认日志
        pass

srv = ThreadingHTTPServer(("127.0.0.1", 18999), MockGateway)
threading.Thread(target=srv.serve_forever, daemon=True).start()
time.sleep(0.2)

# ---------------------------------------------------------------------------
# 2. 全局唯一地址来源：GATEWAY_URL（真实项目 = config 里的网关地址，编排层仅此一个）
#    服务归属由网关路由表决定（path 前缀 → 服务），画布与 DSL 完全不感知地址
# ---------------------------------------------------------------------------
GATEWAY_URL = "http://127.0.0.1:18999"

# ---------------------------------------------------------------------------
# 3. http 节点工厂：加载器读到 type=http 的节点时调用
#    params 全部来自 DSL（画布上配的），加载器不做任何业务判断
# ---------------------------------------------------------------------------
def make_http_node(params: dict):
    url = GATEWAY_URL + params["path"]              # 唯一地址来源 + path 即路由依据
    body_tpl = params.get("body", {})               # 静态参数；动态参数走变量映射（后续迭代）

    def node(state: dict) -> dict:
        body = json.dumps({**body_tpl, "query": state.get("query", "")})   # ensure_ascii 同现有工具
        headers = build_auth_headers(body)          # 现有签名：AK/SK + 时间戳 + HMAC
        r = httpx.post(url, content=body.encode("utf-8"),
                       headers=headers, timeout=10)
        r.raise_for_status()
        return {"fetch_result": r.json()["data"]}

    return node


# ---------------------------------------------------------------------------
# 4. DSL：画布上的 http 节点 —— 只写 path + 参数。无 IP、无签名、无服务名：
#    服务归属由网关路由表（path 前缀 → 服务）决定，改路由 = 改网关配置，画布零改动
# ---------------------------------------------------------------------------
DSL = {
    "entry": "fetch",
    "nodes": [
        {"id": "fetch", "type": "http",
         "params": {"method": "POST",
                    "path": "/api/terminal/inventory/snapshot",
                    "body": {"warehouse": "CK01"}}},
    ],
    "edges": [],
}

NODE_TYPES = {"http": make_http_node}      # 类型注册表：key → 工厂

# ---------------------------------------------------------------------------
# 5. 通用加载器（与 canvas_proof.py 同款，节点侧换成工厂调用）
# ---------------------------------------------------------------------------
def build_graph_from_dsl(dsl: dict):
    g = StateGraph(TypedDict("S", {"query": str, "fetch_result": dict}))
    for n in dsl["nodes"]:
        g.add_node(n["id"], NODE_TYPES[n["type"]](n.get("params", {})))
    g.add_edge(START, dsl["entry"])
    for e in dsl["edges"]:
        g.add_edge(e["from"], END if e["to"] == "__end__" else e["to"])
    if len(dsl["nodes"]) == 1:
        g.add_edge(dsl["entry"], END)
    return g.compile()


if __name__ == "__main__":
    app = build_graph_from_dsl(DSL)
    result = app.invoke({"query": "查一下 CK01 的库存"})
    data = result["fetch_result"]
    print("节点返回:", json.dumps(data["rows"], ensure_ascii=False))
    print("网关侧签名头已带:", data["echo"]["signed"], "| 路径:", data["echo"]["path"])
    print("网关实际收到:", {"accessKey": received[0]["accessKey"][:12] + "…",
                          "sign": received[0]["sign"][:12] + "…",
                          "timestamp": received[0]["timestamp"]})
    srv.shutdown()
