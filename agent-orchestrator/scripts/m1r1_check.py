# M1-R1 nonce 修复回归：格式校验放宽 + 防重放 key 带 ak 前缀 + 高熵 nonce
# 覆盖：无签名403 / 新版32hex nonce 签名200 / 同nonce重放403 / Redis key 带 ak 前缀
# 前置: 网关(8090)+Nacos+Redis+interface(8123) 在跑（走 /api/name/get 旁路，零 VPN 依赖）
# 用法: uv run python scripts/m1r1_check.py
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import httpx

from config import settings
from sdk.sign import build_auth_headers, gen_timestamp, get_sign

URL = f"{settings.GATEWAY_BASE_URL}/api/name/get?name=m1r1"


def main() -> int:
    ok = True

    # 1) 无签名 → 403
    r = httpx.get(URL, timeout=10)
    print(f"[1 无签名]        status={r.status_code}（期望 403）")
    ok &= r.status_code == 403

    # 2) 新版签名（32位hex nonce）→ 200，证明格式校验放宽后正常请求不受影响
    r = httpx.get(URL, headers=build_auth_headers(None), timeout=10)
    print(f"[2 新版签名]      status={r.status_code}（期望 200）body={r.text[:50]}")
    ok &= r.status_code == 200

    # 3) 重放：同一个 nonce 连发两次 → 第二次 403（sign 不含 nonce，只需固定 nonce）
    replay_headers = {
        "accessKey": settings.GATEWAY_ACCESS_KEY,
        "nonce": f"replay-{__import__('uuid').uuid4().hex}",  # 每次运行唯一，避免脚本重跑撞 5min TTL
        "timestamp": gen_timestamp(),
        "sign": get_sign(None, settings.GATEWAY_SECRET_KEY),
    }
    r1 = httpx.get(URL, headers=replay_headers, timeout=10)
    r2 = httpx.get(URL, headers=replay_headers, timeout=10)
    print(f"[3 重放-首次]     status={r1.status_code}（期望 200）")
    print(f"[3 重放-复用]     status={r2.status_code}（期望 403）")
    ok &= r1.status_code == 200 and r2.status_code == 403

    # 4) Redis key 形态：应为 nonce:{accessKey}:{nonce}（防重放粒度=同用户）
    import subprocess
    redis_cli = r"D:/Code/Redis/redis-cli.exe"
    out = subprocess.run(
        [redis_cli, "-h", "127.0.0.1", "-p", "6379", "keys", "nonce:*"],
        capture_output=True, text=True, timeout=10,
    ).stdout.strip().splitlines()
    mine = [k for k in out if k.startswith(f"nonce:{settings.GATEWAY_ACCESS_KEY}:")]
    print(f"[4 Redis key]     样例={mine[-1] if mine else '（未找到，检查 redis-cli 路径）'}")
    ok &= bool(mine)

    print("\n[PASS] M1-R1 回归通过" if ok else "\n[FAIL] M1-R1 回归未通过")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
