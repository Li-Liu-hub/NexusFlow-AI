# M2 整体验收：三节点图真打全链路（LLM 重写 → 签名 → 网关 → Java agent → 回答）
# 前置: 全栈在跑（Nacos/RabbitMQ/PG/backend/gateway/interface/agent）
# 用法: uv run python scripts/m2_run.py "你们支持哪些查询功能"
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from graph import build_graph

MYSQL = r"D:/Code/MySQL/MySQL Server 8.0/bin/mysql.exe"


def get_total_num() -> int:
    out = subprocess.run(
        [MYSQL, "-uroot", "-p1234", "api_db", "-N", "-e",
         "SELECT totalNum FROM user_interface_info WHERE interfaceInfoId=2 AND userId=1;"],
        capture_output=True, text=True, timeout=10,
    )
    return int(out.stdout.strip().splitlines()[-1])


def main() -> int:
    query = sys.argv[1] if len(sys.argv) > 1 else "你们支持哪些查询功能"
    conversation_id = f"m2-{__import__('uuid').uuid4().hex[:8]}"  # Java 侧必传，每次编排跑独立会话
    before = get_total_num()
    print(f"[问题]     {query}")
    print(f"[计量-前]  totalNum={before}")

    result = build_graph().invoke(
        {"query": query, "revision_count": 0, "meta": {"conversation_id": conversation_id}}
    )

    print(f"[重写后]   {result['rewritten_query']}")
    print(f"[agent回答]\n{result['agent_response']}")
    print(f"[meta]     {result['meta']}")

    after = get_total_num()
    print(f"[计量-后]  totalNum={after}（期望 {before + 1}）")
    ok = after == before + 1 and bool(result["agent_response"].strip())
    print("\n[PASS] M2 三节点图全链路验收通过" if ok else "\n[FAIL] M2 验收未通过")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
