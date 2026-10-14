# M3 验收（S1+S2 合一）：经网关签名调编排服务 SSE
# 断言 start/progress(≥3 顺序)/agent_step(≥1, JSON 带降级)/result(=最后一步去前缀)
# 前置: 全栈 + 编排服务(8125) 在跑；interface_info id=5(编排) + id=6(/oa/agent/chat/sse)
# 用法: uv run python scripts/m3_check.py
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import httpx

from config import settings
from sdk.sign import build_auth_headers

URL = f"{settings.GATEWAY_BASE_URL}/orchestrator/v1/chat/stream"
QUERY = "你们支持哪些查询功能"
_STEP_RE = re.compile(r"^Step (\d+): ?")


def main() -> int:
    events: list[tuple[str, str]] = []
    with httpx.stream(
        "POST", URL, params={"query": QUERY},
        headers=build_auth_headers(None),  # 无请求体 → 网关按 "null."+sk 验签
        timeout=120,
    ) as resp:
        print(f"[HTTP]      status={resp.status_code}（期望 200）content-type={resp.headers.get('content-type')}")
        if resp.status_code != 200:
            print(f"[FAIL] body={resp.read().text[:300]}")
            return 1

        event = None
        for line in resp.iter_lines():
            if line.startswith("event: "):
                event = line[len("event: "):]
            elif line.startswith("data: ") and event:
                events.append((event, line[len("data: "):]))
                print(f"[{event:<10}] {events[-1][1][:120]}")
                event = None

    ok = True

    def datas(name: str) -> list[dict | str]:
        return [json.loads(d) for e, d in events if e == name]

    starts = datas("start")
    progresses = [c for e, d in events if e == "progress" for c in [json.loads(d)]]
    steps = datas("agent_step")
    results = datas("result")

    # S1 断言：事件数量与节点顺序
    ok &= len(starts) == 1
    ok &= len(progresses) >= 3
    ok &= len(results) == 1 and bool(str(results[0]).strip())
    nodes = [c["node"] for c in progresses]
    ok &= nodes == ["rewrite", "executor", "respond"]
    print(f"\n[节点顺序] {nodes}")
    print(f"[步骤数]   agent_step={len(steps)}")

    # S2 断言：agent_step 是结构化 JSON（seq/text），且 result 与最后一步衔接
    ok &= len(steps) >= 1
    ok &= all(isinstance(s, dict) and "text" in s for s in steps)
    seqs = [s.get("seq") for s in steps]
    print(f"[步骤 seq] {seqs}（≥1 个非 None 即防腐层翻译生效）")
    ok &= any(s is not None for s in seqs)
    last = steps[-1]["text"] if steps else ""
    expected_result = _STEP_RE.sub("", last, count=1)
    ok &= bool(expected_result.strip()) and results[0] == expected_result
    print(f"[result 衔接] result == 最后一步去前缀 → {'一致' if results[0] == expected_result else '不一致!'}")

    print("\n[PASS] M3 SSE 验收通过（S1+S2）" if ok else "\n[FAIL] M3 验收未通过")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())


if __name__ == "__main__":
    sys.exit(main())
