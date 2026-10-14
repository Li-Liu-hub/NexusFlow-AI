#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""远端调拨闭环校验（stdlib-only，在服务器本机跑）

链路：签名 → 网关 8090 → backend 内部接口（鉴权链）→ lb://aismp-agent → api_db
覆盖：健康检查建议 → 建单 → 发送 → 账管确认 → 仓管确认 → 库存 B 减 A 增

用法：python3 _r11-remote-e2e.py
"""
import hashlib
import json
import secrets
import time
import urllib.error
import urllib.request

GW = "http://127.0.0.1:8090"
AK, SK = "aismp-console", "7c1f9a3e5d0b2846af31c7e9b0d45268"
A, B = "9BZA", "9B00"          # A 缺货（调入）、B 调出

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail and not ok else ""))


def req(method, path, body=None, timeout=60):
    headers = {"Content-Type": "application/json"}
    data = None
    if body is not None:
        text = json.dumps(body)
        data = text.encode("utf-8")
        headers["body"] = text
        sign_src = text + "." + SK
    else:
        sign_src = "null." + SK
    headers.update({
        "accessKey": AK,
        "nonce": secrets.token_hex(16),
        "timestamp": str(int(time.time())),
        "sign": hashlib.sha256(sign_src.encode("utf-8")).hexdigest(),
    })
    r = urllib.request.Request(GW + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            t = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(t) if t.strip().startswith(("{", "[")) else t)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:200]
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"


def available(wh, material):
    s, d = req("GET", f"/api/terminal/inventory/aggregate?warehouseCode={wh}")
    rows = (d or {}).get("data") or []
    return sum(int(x.get("availableQty") or 0) for x in rows if x.get("materialCode") == material)


def main():
    print("=" * 62)
    print("远端调拨闭环校验（经网关 → lb://aismp-agent）")
    print("=" * 62)

    print("\n[1] 库存健康检查（A 仓 = " + A + "）")
    s, d = req("GET", f"/api/terminal/health/suggest?warehouseCode={A}")
    health = (d or {}).get("data") or {}
    sg = health.get("suggestions") or []
    check("1.1 健康检查返回建议", s == 200 and sg, f"HTTP {s} {str(d)[:160]}")
    if not sg:
        return summary()
    first = sg[0]
    material, qty = first["materialCode"], first["suggestQty"]
    print(f"      建议：{first['reason']}")
    print(f"      采用：{material} × {qty}")

    before_b, before_a = available(B, material), available(A, material)
    print(f"\n[2] 调拨前：B={before_b}  A={before_a}")

    print("\n[3] 建单 → 发送工单")
    s, d = req("POST", "/api/transferOrder/create", {
        "operationType": "TRANSFER", "fromWarehouse": B, "toWarehouse": A,
        "creator": "remote_e2e", "source": "MANUAL",
        "items": [{"materialCode": material, "model": first.get("model"),
                   "materialName": first.get("materialName"), "quantity": qty}],
    })
    order = (d or {}).get("data") or {}
    order_no = order.get("orderNo")
    check("3.1 建单 PENDING", s == 200 and order_no and order.get("status") == "PENDING", f"HTTP {s} {str(d)[:150]}")
    if not order_no:
        return summary()
    s, d = req("POST", "/api/transferOrder/send", {"orderNo": order_no, "operator": "remote_e2e"})
    check("3.2 发送工单 SENT", s == 200 and ((d or {}).get("data") or {}).get("status") == "SENT", f"HTTP {s} {str(d)[:150]}")

    print("\n[4] 账管确认 → 仓管确认")
    s, d = req("POST", "/api/transferOrder/accountConfirm", {"orderNo": order_no, "operator": "dn_zg"})
    check("4.1 账管确认 ACCOUNT_CONFIRMED", s == 200 and ((d or {}).get("data") or {}).get("status") == "ACCOUNT_CONFIRMED", f"HTTP {s} {str(d)[:150]}")
    s, d = req("POST", "/api/transferOrder/keeperConfirm", {"orderNo": order_no, "operator": "dn_kg"})
    done = (d or {}).get("data") or {}
    check("4.2 仓管确认 DONE", s == 200 and done.get("status") == "DONE", f"HTTP {s} {str(d)[:150]}")

    print("\n[5] 库存核对")
    after_b, after_a = available(B, material), available(A, material)
    print(f"      B: {before_b} → {after_b}（应 -{qty}）")
    print(f"      A: {before_a} → {after_a}（应 +{qty}）")
    check("5.1 B 仓减少", after_b == before_b - qty, f"{before_b}→{after_b}")
    check("5.2 A 仓增加", after_a == before_a + qty, f"{before_a}→{after_a}")

    return summary(order_no)


def summary(order_no=None):
    ok = sum(1 for _, o in RESULTS if o)
    print("\n" + "=" * 62)
    print(f"通过 {ok}/{len(RESULTS)}" + (f"  工单号 {order_no}" if order_no else ""))
    print("=" * 62)
    return 0 if ok == len(RESULTS) else 1


if __name__ == "__main__":
    raise SystemExit(main())