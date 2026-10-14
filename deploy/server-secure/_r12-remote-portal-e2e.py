#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""远端门户调拨闭环 E2E（门户 Bearer 通道，stdlib-only；2026-10-08 新链路）

新链路（与用户对齐）：
    A 仓账管：健康检查拿建议 → 提交调拨申请（APPLIED，无发送/账管确认环节）
    B 仓仓管：导入 SN 码确认出库（OUTBOUND，串码置「在途」仍挂 B 仓）
    A 仓仓管：确认入库（DONE，库存 B 减 A 增）
说明：/api/terminal/**、/api/transferOrder/** 走门户 Bearer 通道（AK/SK 签名会 401）。
      远端（49.234.49.192）为脱敏数据集（东宁系）：A=9BZA（新区/缺货）、B=9B00（市公司/调出）。

用法：
    在服务器上直连网关：      python3 _r12-remote-portal-e2e.py
    从外部经 nginx 80：       GW=http://49.234.49.192 python3 _r12-remote-portal-e2e.py
"""
import io
import json
import os
import sys
import urllib.error
import urllib.request

GW = os.environ.get("GW", "http://127.0.0.1:8090")
A, B = "9BZA", "9B00"          # A 缺货（调入，账管申请/仓管入库）、B 调出（仓管出库）

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail and not ok else ""))


def req(method, path, token=None, body=None, timeout=180):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(GW + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            t = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(t) if t.strip().startswith(("{", "[")) else t)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:200]
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"


def login(user, pwd="123456"):
    s, d = req("POST", "/api/auth/login", body={"userCode": user, "password": pwd})
    if s != 200 or not isinstance(d, dict) or not d.get("token"):
        raise SystemExit(f"登录失败 {user}: HTTP {s} {str(d)[:200]}")
    return d


def available(wh, material, token):
    s, d = req("GET", f"/api/terminal/inventory/aggregate?warehouseCode={wh}", token)
    rows = (d or {}).get("data") or []
    return sum(int(x.get("availableQty") or 0) for x in rows if x.get("materialCode") == material)


def main():
    print("=" * 66)
    print(f"远端门户调拨闭环 E2E（新链路）   GW={GW}   A={A}(调入) B={B}(调出)")
    print("=" * 66)

    print("\n[0] 三角色登录（门户通道，登录即登记 Redis 令牌映射）")
    a_acc = login("xn_zg")   # A 仓账管（申请）
    b_kp = login("dn_kg")    # B 仓仓管（出库）
    a_kp = login("xn_kg")    # A 仓仓管（入库）
    check("0.1 登录返回仓库与角色标签",
          a_acc.get("roleLabel") == "账管" and a_acc.get("warehouseCode") == A,
          str(a_acc)[:140])

    print("\n[1] A 仓（缺货）账管：库存健康检查")
    s, d = req("GET", f"/api/terminal/health/suggest?warehouseCode={A}", a_acc["token"])
    health = (d or {}).get("data") or {}
    sg = health.get("suggestions") or []
    check("1.1 健康检查返回健康度与建议", s == 200 and health.get("healthStatus") and sg,
          f"HTTP {s} {str(d)[:160]}")
    if not sg:
        return summary()
    first = sg[0]
    material, qty = first["materialCode"], first["suggestQty"]
    print(f"      建议：{first['reason']}")
    print(f"      采用：{material} × {qty}（{first.get('fromWarehouse')} → {A}）")
    check("1.2 建议调出仓为 B", first.get("fromWarehouse") == B, str(first)[:140])

    before_b, before_a = available(B, material, b_kp["token"]), available(A, material, a_acc["token"])  # B 仓用 B 仓管令牌读（2026-10-08 收口后子仓令牌不可跨读上级仓）
    print(f"\n[2] 调拨前库存：B({B})={before_b} 台，A({A})={before_a} 台")

    print("\n[3] A 仓账管：提交调拨申请")
    s, d = req("POST", "/api/transferOrder/create", a_acc["token"], {
        "operationType": "TRANSFER", "fromWarehouse": B, "toWarehouse": A,
        "creator": "xn_zg", "source": "MANUAL",
        "items": [{"materialCode": material, "model": first.get("model"),
                   "materialName": first.get("materialName"), "quantity": qty}],
    })
    order = (d or {}).get("data") or {}
    order_no = order.get("orderNo")
    check("3.1 申请受理（APPLIED）", s == 200 and order_no and order.get("status") == "APPLIED",
          f"HTTP {s} {str(d)[:160]}")
    if not order_no:
        return summary()
    s, d = req("GET", "/api/transferOrder/notices?limit=20", b_kp["token"])
    check("3.2 B 仓管收到「待确认出库」通知",
          s == 200 and any(n.get("noticeType") == "ORDER_APPLIED" and n.get("orderNo") == order_no
                           for n in ((d or {}).get("data") or [])),
          f"{len(((d or {}).get('data')) or [])} 条")

    print("\n[4] B 仓仓管：导入 SN 并确认出库")
    s, d = req("GET", f"/api/terminal/inventory/serialNos?warehouseCode={B}"
               f"&materialCode={material}&limit={qty}", b_kp["token"])
    sns = (d or {}).get("data") or []
    check("4.1 取得本仓可用 SN", len(sns) >= qty, f"拿到 {len(sns)} 个")
    if len(sns) < qty:
        return summary(order_no)
    s, d = req("POST", "/api/transferOrder/outbound", b_kp["token"],
               {"orderNo": order_no, "serialNos": sns[:qty]})
    vo = (d or {}).get("data") or {}
    check("4.2 确认出库（OUTBOUND 在途）", s == 200 and vo.get("status") == "OUTBOUND",
          f"HTTP {s} {str(d)[:160]}")
    check("4.3 出库后 B 仓可用 -%d（转在途）" % qty,
          available(B, material, b_kp["token"]) == before_b - qty, "库存核对")
    s, d = req("GET", "/api/transferOrder/notices?limit=20", a_kp["token"])
    check("4.4 A 仓管收到「确认入库」通知",
          s == 200 and any(n.get("noticeType") == "OUTBOUND" and n.get("orderNo") == order_no
                           for n in ((d or {}).get("data") or [])),
          f"{len(((d or {}).get('data')) or [])} 条")

    print("\n[5] A 仓仓管：确认入库 → 库存核对（B 减 A 增）")
    s, d = req("POST", "/api/transferOrder/inbound", a_kp["token"],
               {"orderNo": order_no, "operator": "xn_kg"})
    done = (d or {}).get("data") or {}
    check("5.1 入库完成（DONE）", s == 200 and done.get("status") == "DONE", f"HTTP {s} {str(d)[:160]}")
    check("5.2 回填实际调拨数量", int(done.get("movedQuantity") or 0) == qty,
          f"movedQuantity={done.get('movedQuantity')} 期望 {qty}")
    after_b, after_a = available(B, material, b_kp["token"]), available(A, material, a_acc["token"])
    print(f"      B: {before_b} → {after_b}（应 -{qty}）")
    print(f"      A: {before_a} → {after_a}（应 +{qty}）")
    check("5.3 B 仓减少准确", after_b == before_b - qty, f"{before_b}→{after_b}")
    check("5.4 A 仓增加准确", after_a == before_a + qty, f"{before_a}→{after_a}")

    print("\n[6] 配套校验")
    s, d = req("GET", f"/api/transferOrder/notices?warehouseCode={A}&role=ACCOUNT_MANAGER&userCode=xn_zg",
               a_acc["token"])
    notices = (d or {}).get("data") or []
    check("6.1 申请方收到「已完成」通知",
          s == 200 and any(n.get("noticeType") == "DONE" for n in notices), f"{len(notices)} 条")
    s, d = req("GET", f"/api/transferOrder/get?orderNo={order_no}", a_acc["token"])
    vo = (d or {}).get("data") or {}
    check("6.2 详情含状态标签与时间线（申请/出库/入库）",
          vo.get("statusLabel") == "已完成" and vo.get("outboundAt") and vo.get("inboundAt"),
          str(vo)[:160])
    return summary(order_no)


def summary(order_no=None):
    ok = sum(1 for _, o in RESULTS if o)
    print("\n" + "=" * 66)
    print(f"通过 {ok}/{len(RESULTS)}" + (f"  工单号 {order_no}" if order_no else ""))
    for n, o in RESULTS:
        if not o:
            print(f"  ✘ {n}")
    print("=" * 66)
    return 0 if ok == len(RESULTS) else 1


if __name__ == "__main__":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    raise SystemExit(main())