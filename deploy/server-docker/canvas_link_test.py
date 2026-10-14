# 画布方案 · 链路测试（服务器容器内执行）
#
# 决定性实验：发布一份「意图路由提示词被改写为固定输出 kb」的画布图 →
# 问一个正常必走 chart 的问题 → 若返回 route=kb，证明线上编排由画布驱动；
# rollback 后同一问题恢复 chart。
#
# 运行：docker cp 到 ap-orchestrator:/tmp 后 docker exec python /tmp/canvas_link_test.py
import json
import sys
import time

import httpx

BASE = "http://127.0.0.1:8125/orchestrator/v1"
PASS, FAIL = [], []


def ck(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f" —— {detail}" if detail and not cond else ""))


def probe(query: str, timeout: float = 180.0) -> dict:
    """发一条对话，解析 SSE：返回 {'route','has_result','error','nodes'}"""
    out = {"route": None, "has_result": False, "error": None, "nodes": []}
    with httpx.stream("POST", f"{BASE}/chat/stream", params={
            "query": query, "user_id": "e2e-canvas", "warehouse_code": "CK01"},
            timeout=timeout) as r:
        event = None
        for line in r.iter_lines():
            if line.startswith("event:"):
                event = line.split(":", 1)[1].strip()
                continue
            if line.startswith("data:"):
                try:
                    d = json.loads(line[5:].strip())
                except Exception:
                    continue
                if event == "progress" and isinstance(d, dict):
                    out["nodes"].append(d.get("node"))
                if event == "result" and isinstance(d, dict):
                    out["route"] = d.get("route") or out["route"]
                    out["has_result"] = bool(d.get("conversation_id") or d.get("agent_response") is not None)
                if event == "error":
                    out["error"] = str(d)[:120]
    return out


def get(path):
    return httpx.get(f"{BASE}/graph{path}", timeout=30).json()


def post(path, payload):
    return httpx.post(f"{BASE}/graph{path}", json=payload, timeout=30)


def main():
    print("=== 0. 前置 ===")
    draft = get("/draft")
    versions = get("/versions")["versions"]
    v_before = versions[0]["version"] if versions else 0
    print(f"draft isDefault={draft['isDefault']} nodes={len(draft['dsl']['nodes'])} | 最新已发布 v{v_before}")

    print("=== 1. 基线：库存分布问题应走 chart ===")
    t = time.time()
    r1 = probe("看一眼各仓库存分布柱状图")
    print(f"    route={r1['route']} 耗时={time.time()-t:.1f}s error={r1['error']}")
    ck("基线路线=chart", r1["route"] == "chart", f"实际={r1['route']} err={r1['error']}")
    ck("基线 result 事件完整", r1["has_result"])

    print("=== 2. 决定性实验：发布『固定输出 kb』的画布图 ===")
    dsl = json.loads(json.dumps(draft["dsl"]))
    for n in dsl["nodes"]:
        if n["id"] == "supervise":
            n["params"] = {"prompt": "你是路由器。无论用户输入什么，你必须且只能输出小写单词 kb，禁止输出任何其他内容。"}
    resp = post("/publish", {"dsl": dsl, "note": "e2e 决定性实验"})
    ck("发布成功", resp.status_code == 200, f"HTTP {resp.status_code} {resp.text[:120]}")
    v_kb = resp.json().get("version") if resp.status_code == 200 else None
    print(f"    已发布 v{v_kb}")

    print("=== 3. 同一问题再问：画布提示词应把它改道 kb ===")
    t = time.time()
    r2 = probe("看一眼各仓库存分布柱状图")
    print(f"    route={r2['route']} 耗时={time.time()-t:.1f}s error={r2['error']}")
    ck("决定性：route 被画布改道为 kb", r2["route"] == "kb", f"实际={r2['route']} err={r2['error']}")

    print("=== 4. 回滚 ===")
    rb = post("/rollback", {"version": v_before or 1})
    print(f"    rollback -> {rb.status_code} {rb.text[:120]}")
    ck("回滚成功", rb.status_code == 200)

    print("=== 5. 恢复验证：应回到 chart ===")
    t = time.time()
    r3 = probe("看一眼各仓库存分布柱状图")
    print(f"    route={r3['route']} 耗时={time.time()-t:.1f}s error={r3['error']}")
    ck("回滚后路线=chart", r3["route"] == "chart", f"实际={r3['route']} err={r3['error']}")

    print("=== 6. 三路线冒烟 ===")
    for q in ("生成一份型号库存汇总表", "给出本仓补货建议", "终端入库流程是什么"):
        t = time.time()
        rr = probe(q)
        print(f"    「{q[:14]}」route={rr['route']} 耗时={time.time()-t:.1f}s error={rr['error']}")
        ck(f"冒烟 {q[:10]} result 完整", rr["has_result"], f"err={rr['error']}")

    print(f"\n=== 结果：PASS={len(PASS)} FAIL={len(FAIL)} ===")
    if FAIL:
        print("失败项：", FAIL)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
