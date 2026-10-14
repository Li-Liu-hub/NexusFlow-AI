# 画布方案 · 链路测试 v2（服务器容器内执行）
#
# 与 v1 的区别（v1 的教训：依赖 LLM 路由字段做判据 → 被提示词实验污染后误判）：
#   1. 判据改为「实际执行的节点序列」（progress 事件），不依赖 LLM 路由稳定性；
#   2. 决定性实验改为**拓扑级**：发布 entry 指向 chart_plan 的图（supervise 不在主路径），
#      任意问题必走图表链 —— 图定义直接决定执行路径，零 LLM 依赖；
#   3. 实验前先发布一份干净基线（DEFAULT_DSL），所有回滚都以它为锚；
#   4. 仓位用真实编码 9B00（编造的 CK01 在数据里不存在）。
#
# 运行：docker cp 到 ap-orchestrator:/tmp 后
#       docker exec -d ap-orchestrator sh -c '/app/.venv/bin/python -u /tmp/canvas_link_test2.py > /tmp/link_test2.out 2>&1'
import json
import sys
import time

import httpx

BASE = "http://127.0.0.1:8125/orchestrator/v1"
WH = "9B00"
PASS, FAIL, NOTES = [], [], []


def ck(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f" —— {detail}" if detail and not cond else ""))


def note(msg):
    NOTES.append(msg)
    print(f"    note: {msg}")


def probe(query: str, timeout: float = 240.0) -> dict:
    """发对话，解析 SSE：nodes=实际执行的节点序列；route=终态路由字段"""
    out = {"route": None, "nodes": [], "has_result": False, "error": None}
    try:
        with httpx.stream("POST", f"{BASE}/chat/stream", params={
                "query": query, "user_id": "e2e-canvas", "warehouse_code": WH},
                timeout=timeout) as r:
            event = None
            for line in r.iter_lines():
                if line.startswith("event:"):
                    event = line.split(":", 1)[1].strip()
                    continue
                if not line.startswith("data:"):
                    continue
                try:
                    d = json.loads(line[5:].strip())
                except Exception:
                    continue
                if event == "progress" and isinstance(d, dict):
                    out["nodes"].append(d.get("node"))
                elif event == "result" and isinstance(d, dict):
                    out["route"] = out["route"] or d.get("route")
                    out["has_result"] = True
                elif event == "error":
                    out["error"] = str(d)[:120]
    except Exception as exc:  # noqa: BLE001 —— 超时等不炸整轮
        out["error"] = f"{type(exc).__name__}: {exc}"[:120]
    return out


def g(path):
    return httpx.get(f"{BASE}/graph{path}", timeout=30).json()


def post(path, payload):
    return httpx.post(f"{BASE}/graph{path}", json=payload, timeout=30)


def main():
    print("=== 0. 干净基线：发布 DEFAULT_DSL 作为回滚锚点 ===")
    draft = g("/draft")
    base = post("/publish", {"dsl": draft["dsl"], "note": "e2e v2 基线（干净图）"})
    v_base = base.json().get("version")
    ck("基线发布成功", base.status_code == 200, f"HTTP {base.status_code} {base.text[:100]}")
    print(f"    基线版本 v{v_base}（DEFAULT_DSL，无提示词覆盖）")

    print("=== 1. 基线行为记录（不强判：LLM 路由受模型波动影响） ===")
    t = time.time()
    r1 = probe(f"{WH} 各仓库存分布柱状图")
    note(f"基线问题执行节点: {r1['nodes']} route={r1['route']} 耗时={time.time()-t:.1f}s err={r1['error']}")
    ck("基线 result 完整", r1["has_result"], f"err={r1['error']}")

    print("=== 2. 决定性实验（拓扑级）：entry 改指 chart_plan ===")
    dsl = json.loads(json.dumps(draft["dsl"]))
    dsl["entry"] = "chart_plan"          # supervise 不在主路径 —— 路由由图定义决定
    resp = post("/publish", {"dsl": dsl, "note": "e2e 拓扑级决定性实验"})
    ck("拓扑图发布成功", resp.status_code == 200, f"HTTP {resp.status_code} {resp.text[:120]}")
    v_top = resp.json().get("version")
    print(f"    已发布 v{v_top}（entry=chart_plan）")

    t = time.time()
    r2 = probe("你好，随便聊聊")           # 正常必走 kb 的问法
    note(f"拓扑图下执行节点: {r2['nodes']} route={r2['route']} 耗时={time.time()-t:.1f}s err={r2['error']}")
    ck("决定性：执行路径由图定义决定（含 chart 链、不执行 supervise）",
       ("chart_plan" in r2["nodes"] or "chart_build" in r2["nodes"]) and "supervise" not in r2["nodes"],
       f"nodes={r2['nodes']}")

    print("=== 3. 回滚到干净基线 ===")
    rb = post("/rollback", {"version": v_base})
    ck("回滚成功", rb.status_code == 200, f"HTTP {rb.status_code} {rb.text[:120]}")

    print("=== 4. 恢复验证：同一问题不再走图表链 ===")
    t = time.time()
    r3 = probe("你好，随便聊聊")
    note(f"恢复后执行节点: {r3['nodes']} route={r3['route']} 耗时={time.time()-t:.1f}s err={r3['error']}")
    ck("恢复：supervise 回到主路径", "supervise" in r3["nodes"], f"nodes={r3['nodes']}")

    print("=== 5. 三路线冒烟（真实仓位 9B00） ===")
    for q in (f"{WH} 型号库存汇总", f"{WH} 补货建议", "终端入库流程是什么"):
        t = time.time()
        rr = probe(q)
        note(f"「{q[:16]}」nodes={rr['nodes']} route={rr['route']} 耗时={time.time()-t:.1f}s err={rr['error']}")
        ck(f"冒烟 {q[:8]} result 完整", rr["has_result"], f"err={rr['error']}")

    print(f"\n=== 结果：PASS={len(PASS)} FAIL={len(FAIL)} ===")
    if FAIL:
        print("失败项：", FAIL)
    print("=== 注记（非断言，供判断） ===")
    for n in NOTES:
        print(" -", n)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
