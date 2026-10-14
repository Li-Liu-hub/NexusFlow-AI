# M7-S2 补测：token 通道 + 中文 tools/call 过网关（8090）
# 预期：中文直接通——token 路径无签名无 body 头，没有 ASCII 限制（对照：签名通道中文必 403）
# 用法: uv run python scripts/m7_s2_token_zh_test.py
import json
import sys
import threading
import time
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

GATEWAY = "http://127.0.0.1:8090"
# token 文件放项目根（原来写死在本机旧目录 D:/Code/Java/...，换机器必找不到；
# 找不到时回退到下面这个开发期 token）
TOKEN_FILE = Path(__file__).resolve().parent.parent.parent / "m7_token.txt"
token = TOKEN_FILE.read_text(encoding="utf-8").strip() if TOKEN_FILE.exists() \
    else "1bf8f95771aad224d24d2a3db26683e0"
HEADERS = {"Authorization": f"Bearer {token}", "Accept": "text/event-stream"}

endpoint_holder = {}


def listen_sse(url: str, stop: threading.Event):
    """后台线程挂住 SSE 连接（MCP streamable 语义：POST 响应经此通道回推）"""
    with httpx.Client(timeout=60, trust_env=False) as client:  # trust_env=False：绕系统代理
        with client.stream("GET", url, headers=HEADERS) as resp:
            print(f"[SSE] HTTP {resp.status_code}")
            event, data_lines = None, []
            for line in resp.iter_lines():
                if stop.is_set():
                    break
                if line.startswith("event:"):
                    event = line.split(":", 1)[1].strip()
                elif line.startswith("data:"):
                    data_lines.append(line.split(":", 1)[1].strip())
                elif not line and event:
                    payload = "\n".join(data_lines)
                    if event == "endpoint":
                        endpoint_holder["url"] = payload
                        print(f"[SSE] endpoint = {payload}")
                    else:
                        print(f"[SSE] {event}: {payload[:160]}")
                    event, data_lines = None, []


def post_rpc(client, message_url: str, payload: dict):
    resp = client.post(message_url, json=payload,
                       headers={"Authorization": f"Bearer {token}"})
    print(f"[POST] {payload.get('method')} -> HTTP {resp.status_code} {resp.text[:80]}")
    return resp


def main() -> int:
    with httpx.Client(timeout=30, trust_env=False) as client:
        # 无 token → 403（回归确认过滤器还在）
        r0 = client.get(f"{GATEWAY}/sse", headers={"Accept": "text/event-stream"})
        print(f"[回归] 无 token: HTTP {r0.status_code}（期望 403）")
        if r0.status_code != 403:
            print("!! 无 token 竟然放行，过滤器异常")
            return 1

    stop = threading.Event()
    t = threading.Thread(target=listen_sse, args=(f"{GATEWAY}/sse", stop), daemon=True)
    t.start()
    for _ in range(50):
        if "url" in endpoint_holder:
            break
        time.sleep(0.2)
    if "url" not in endpoint_holder:
        print("!! 未收到 endpoint 事件")
        return 1

    message_url = GATEWAY + endpoint_holder["url"]
    with httpx.Client(timeout=30, trust_env=False) as client:
        post_rpc(client, message_url, {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                       "params": {"protocolVersion": "2024-11-05",
                                                  "capabilities": {},
                                                  "clientInfo": {"name": "m7-s2-test", "version": "1.0"}}})
        post_rpc(client, message_url, {"jsonrpc": "2.0", "method": "notifications/initialized"})
        post_rpc(client, message_url, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})

        # 中文 tools/call：签名通道里这一定会 403/卡死，token 通道预期直接通
        post_rpc(client, message_url,
                 {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                  "params": {"name": "oaAgent",
                             "arguments": {"query": "电费稽核的流程是什么"}}})
    time.sleep(15)  # 等 SSE 回推 agent 回答（全链路 ~10s 量级）
    stop.set()
    return 0


if __name__ == "__main__":
    sys.exit(main())
