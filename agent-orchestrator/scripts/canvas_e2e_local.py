# 画布发布链路本地端到端联调（零部署、零外部依赖）
#
# 用 FastAPI TestClient 驱动 server 的真实端点（PG = 本机 127.0.0.1），走完整闭环：
#   1. GET draft（首开 → 默认现网拓扑）
#   2. PUT draft（模拟画布编辑：supervise 节点加提示词覆盖 + fetch 改 http 形态）
#   3. POST publish（校验器 + 固化 v{N}）
#   4. GET versions
#   5. GRAPH_SOURCE=dsl 下 _compiled_graph() 装配成功 + 提示词覆盖确实生效（node_params）
#   6. POST rollback → 新版本
# 全程不 invoke（不打 LLM/网关）；GRAPH_SOURCE 结束后还原为 code。
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from fastapi.testclient import TestClient

import server
from canvas.default_dsl import DEFAULT_DSL
from canvas import store as canvas_store
from node_params import current_overrides, prompt_for

FAILS = []


def check(name: str, cond: bool, detail: str = ""):
    tag = "PASS" if cond else "FAIL"
    print(f"[{tag}] {name}" + (f" —— {detail}" if detail and not cond else ""))
    if not cond:
        FAILS.append(name)


def main():
    settings_ref = server.settings
    assert settings_ref.GRAPH_SOURCE == "code", "联调应从 code 态开始（默认）"
    canvas_store.clear_draft()  # 联调需要干净草稿位（历史版本不受影响）

    with TestClient(server.app) as client:
        # 1. 默认草稿
        r = client.get("/orchestrator/v1/graph/draft")
        body = r.json()
        check("GET draft 首开返回默认现网拓扑", r.status_code == 200 and body["isDefault"] is True
              and body["dsl"]["entry"] == DEFAULT_DSL["entry"])

        # 2. 编辑草稿：supervise 加提示词覆盖
        dsl = {**body["dsl"]}
        dsl["nodes"] = [
            {**n, "params": {**n.get("params", {}),
                             **({"prompt": "联调覆盖：请把用户输入归类为 replenish/chart/kb/order 之一。"}
                                if n["id"] == "supervise" else {})}}
            for n in dsl["nodes"]
        ]
        r = client.put("/orchestrator/v1/graph/draft", json={"dsl": dsl})
        check("PUT draft 保存成功且结构校验通过", r.status_code == 200 and r.json()["ok"] is True
              and r.json()["errors"] == [])

        # 3. 发布
        r = client.post("/orchestrator/v1/graph/publish", json={"dsl": dsl, "note": "e2e 联调"})
        pub = r.json()
        check("POST publish 固化版本", r.status_code == 200 and pub.get("version", 0) >= 1)
        version = pub["version"]

        # 4. 版本列表
        r = client.get("/orchestrator/v1/graph/versions")
        versions = r.json()["versions"]
        check("GET versions 含新版本", any(v["version"] == version for v in versions))

        # 5. dsl 模式装配 + 提示词覆盖生效
        settings_ref.GRAPH_SOURCE = "dsl"
        try:
            graph, bound_version = server._compiled_graph()
            check("GRAPH_SOURCE=dsl 装配成功", graph is not None)
            check("装配绑定最新版本", bound_version == version, f"实际 {bound_version}")
            ov = current_overrides()
            check("提示词覆盖层已注入", "route" in ov and "联调覆盖" in ov["route"])
            check("运行时取提示词走覆盖", prompt_for("route", "默认") == ov["route"])
        finally:
            settings_ref.GRAPH_SOURCE = "code"
            print("[INFO] GRAPH_SOURCE 已还原为 code（默认态，不切流量）")

        # 6. 回滚
        r = client.post("/orchestrator/v1/graph/rollback", json={"version": version})
        rb = r.json()
        check("POST rollback 复制为新版本", r.status_code == 200
              and rb["rolledBackFrom"] == version and rb["version"] > version)

    print()
    print("结论:", "全部通过 —— 发布链路端到端可用（本地，未部署）" if not FAILS else f"失败 {len(FAILS)} 项: {FAILS}")
    sys.exit(1 if FAILS else 0)


if __name__ == "__main__":
    main()
