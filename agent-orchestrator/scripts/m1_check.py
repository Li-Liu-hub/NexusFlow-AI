# M1 真打网关验收：无签名被拦(403)、带签名 200
# 前置: 网关(8090)+Nacos+MySQL+Redis+agent(8124) 全部在跑
# 用法: uv run python scripts/m1_check.py
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import httpx

from config import settings
from sdk.sign import build_auth_headers

URL = f"{settings.GATEWAY_BASE_URL}/oa/chat/sync"
BODY = json.dumps({"message": "你好，简短介绍你自己", "conversationId": "m1-check"})


def main() -> int:
    ok = True

    # 1) 无签名请求 → 期望被拦（403）
    try:
        r = httpx.post(URL, content=BODY, headers={"Content-Type": "application/json"}, timeout=10)
        print(f"[无签名] status={r.status_code}（期望 403）")
        if r.status_code != 403:
            ok = False
    except httpx.HTTPError as e:
        print(f"[无签名] 网关不可达: {e}")
        return 2

    # 2) 带签名请求 → 期望 200 + agent 回答
    try:
        r = httpx.post(
            URL,
            content=BODY,
            headers={"Content-Type": "application/json", **build_auth_headers(BODY)},
            timeout=60,
        )
        print(f"[带签名] status={r.status_code}（期望 200）")
        print(f"[带签名] body={r.text[:300]}")
        if r.status_code != 200:
            ok = False
    except httpx.HTTPError as e:
        print(f"[带签名] 请求失败: {e}")
        ok = False

    print("\n[PASS] M1 真打网关验收通过" if ok else "\n[FAIL] M1 验收未通过")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
