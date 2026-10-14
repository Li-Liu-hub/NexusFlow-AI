# M2-S3 验收：工具函数真打网关调 Java agent
# 验证：签名 200 + agent 真实回答 + 计量落库 +1（interface_info id=2 /oa/chat/sync）
# 前置: 全栈在跑（PG 现为本地用户态进程，无 VPN 依赖）
# 用法: uv run python scripts/s3_check.py
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import httpx

from config import settings
from tools.java_agent import JavaAgentError, java_rag_agent

MYSQL = r"D:/Code/MySQL/MySQL Server 8.0/bin/mysql.exe"


def get_total_num() -> int:
    """查 user_interface_info 中 /oa/chat/sync（interfaceInfoId=2, userId=1）的累计调用数。"""
    out = __import__("subprocess").run(
        [MYSQL, "-uroot", "-p1234", "api_db", "-N", "-e",
         "SELECT totalNum FROM user_interface_info WHERE interfaceInfoId=2 AND userId=1;"],
        capture_output=True, text=True, timeout=10,
    )
    return int(out.stdout.strip().splitlines()[-1])


def main() -> int:
    before = get_total_num()
    print(f"[计量-调用前] totalNum={before}")

    question = "你们支持哪些查询功能"
    print(f"[提问] {question}")
    try:
        answer = java_rag_agent(question, conversation_id="s3-check")
    except JavaAgentError as e:
        print(f"[FAIL] {e}")
        return 1

    print(f"[回答] {answer[:300]}")
    if not answer.strip():
        print("[FAIL] 回答为空")
        return 1

    after = get_total_num()
    print(f"[计量-调用后] totalNum={after}（期望 {before + 1}）")
    if after != before + 1:
        print("[FAIL] 计量未 +1")
        return 1

    print("\n[PASS] M2-S3 验收通过：工具真打网关 + 计量落库")
    return 0


if __name__ == "__main__":
    sys.exit(main())
