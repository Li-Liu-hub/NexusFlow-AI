#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""调拨工单闭环 E2E（2026-10-08 链路重构版）

新链路（与用户对齐）：
    A 仓账管：提交调拨申请（APPLIED，无发送/账管确认环节）
    B 仓仓管：导入 SN 码确认出库（OUTBOUND，串码置「在途」仍挂 B 仓）
    A 仓仓管：确认入库（DONE，在途→A 仓可用）或驳回入库（REJECTED，货退回 B 仓）

覆盖：正常闭环 + 驳回闭环 + 越权探针 + SN 数量校验 + 库存核对 + 通知/详情。
用法：python transfer-flow-e2e.py
"""
import io
import os
import sys

import httpx

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

GW = os.environ.get("GW", "http://127.0.0.1:8090")
A = "9BZA"   # 调入仓（东宁市新区分公司，三级库）——A 账管申请、A 仓管入库
B = "9B00"   # 调出仓（东宁市分公司，二级库）——B 仓管出库

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail and not ok else ""))


def login(user, pwd="123456"):
    r = httpx.post(f"{GW}/api/auth/login", json={"userCode": user, "password": pwd}, timeout=30)
    r.raise_for_status()
    d = r.json()
    if not d.get("token"):
        raise SystemExit(f"登录失败 {user}: {d}")
    return d


def call(method, path, token, **kw):
    r = httpx.request(method, f"{GW}{path}",
                      headers={"Authorization": f"Bearer {token}"}, timeout=180, **kw)
    try:
        d = r.json()
    except ValueError:
        d = {"_raw": r.text[:200]}
    return r.status_code, d


def data_of(d):
    return (d or {}).get("data")


def biz_failed(s, d):
    """业务失败 = HTTP 非 200，或 code 非 0。"""
    return s != 200 or (isinstance(d, dict) and d.get("code") not in (0, None))


def available(wh, material, token):
    s, d = call("GET", f"/api/terminal/inventory/aggregate?warehouseCode={wh}", token)
    rows = data_of(d) or []
    return sum(int(x.get("availableQty") or 0) for x in rows if x.get("materialCode") == material)


def pick_material(token):
    """挑一个 B 仓有足够可用库存的物料：两轮闭环共需 2*qty 台，保留余量。"""
    s, d = call("GET", f"/api/terminal/inventory/aggregate?warehouseCode={B}", token)
    rows = [r for r in (data_of(d) or []) if int(r.get("availableQty") or 0) >= 2]
    rows.sort(key=lambda r: int(r.get("availableQty") or 0))
    if not rows:
        raise SystemExit("B 仓没有可用库存 ≥2 的物料，无法测")
    r = rows[0]
    qty = min(2, int(r.get("availableQty") or 0) // 2)
    return r, max(qty, 1)


def serial_nos(wh, material, limit, token):
    s, d = call("GET",
                f"/api/terminal/inventory/serialNos?warehouseCode={wh}&materialCode={material}&limit={limit}",
                token)
    return data_of(d) or []


def create_apply(a_acc, material, model, material_name, qty):
    body = {
        "operationType": "TRANSFER",
        "fromWarehouse": B, "toWarehouse": A,
        "creator": "xn_zg", "source": "MANUAL",
        "items": [{"materialCode": material, "model": model,
                   "materialName": material_name, "quantity": qty}],
    }
    return call("POST", "/api/transferOrder/create", a_acc["token"], json=body)


def main():
    print("=" * 70)
    print("调拨工单闭环 E2E · 新链路（A 账管申请 → B 仓管导 SN 出库 → A 仓管入库/驳回）")
    print("=" * 70)

    # ---- 0. 四角色登录 ----
    print("\n[0] 角色登录")
    a_acc = login("xn_zg")   # A 仓账管（申请）
    b_kp = login("dn_kg")    # B 仓仓管（出库）
    a_kp = login("xn_kg")    # A 仓仓管（入库/驳回）
    b_acc = login("dn_zg")   # B 仓账管（越权探针用）
    check("0.1 登录返回仓库与角色",
          a_acc.get("warehouseCode") == A and a_acc.get("role") == "ACCOUNT_MANAGER"
          and b_kp.get("warehouseCode") == B and b_kp.get("role") == "WAREHOUSE_KEEPER",
          f"{a_acc.get('warehouseCode')}/{a_acc.get('role')} {b_kp.get('warehouseCode')}/{b_kp.get('role')}")

    row, qty = pick_material(b_kp["token"])
    material, model = row["materialCode"], row.get("model")
    material_name = row.get("materialName")
    print(f"      物料：{model} {material_name}（{material}），本次调拨 {qty} 台")

    before_b = available(B, material, b_kp["token"])
    before_a = available(A, material, a_acc["token"])
    print(f"\n[1] 调拨前库存：B({B})={before_b} 台，A({A})={before_a} 台")

    # ---- 1. 正常闭环 ----
    print("\n[2] A 仓账管：提交调拨申请")
    s, d = create_apply(a_acc, material, model, material_name, qty)
    order = data_of(d) or {}
    order_no = order.get("orderNo")
    check("2.1 申请受理（APPLIED 待确认出库）",
          s == 200 and order_no and order.get("status") == "APPLIED",
          f"HTTP {s} {str(d)[:150]}")
    if not order_no:
        return summary()

    s, d = call("GET", f"/api/transferOrder/notices?limit=20", b_kp["token"])
    check("2.2 B 仓管收到「待确认出库」通知",
          s == 200 and any(n.get("noticeType") == "ORDER_APPLIED" and n.get("orderNo") == order_no
                           for n in (data_of(d) or [])),
          f"{len(data_of(d) or [])} 条")

    print("\n[3] 越权探针：错角色/错侧出库应被拒")
    s, d = call("POST", "/api/transferOrder/outbound", a_kp["token"],
                json={"orderNo": order_no, "serialNos": ["X"]})
    check("3.1 A 仓仓管出库被拒（出库限调出方仓管）", biz_failed(s, d), f"HTTP {s} {str(d)[:120]}")
    s, d = call("POST", "/api/transferOrder/outbound", b_acc["token"],
                json={"orderNo": order_no, "serialNos": ["X"]})
    check("3.2 B 仓账管出库被拒（角色不符）", biz_failed(s, d), f"HTTP {s} {str(d)[:120]}")

    print("\n[4] B 仓仓管：导入 SN 并确认出库")
    sns = serial_nos(B, material, qty, b_kp["token"])
    check("4.1 取得本仓可用 SN", len(sns) >= qty, f"拿到 {len(sns)} 个")
    if len(sns) < qty:
        return summary(order_no)

    # 4.2 SN 数量不符 → 应被拒（四重比对）；数量=1 时无法构造"少一个"，跳过探针
    if qty > 1:
        s, d = call("POST", "/api/transferOrder/outbound", b_kp["token"],
                    json={"orderNo": order_no, "serialNos": sns[:qty - 1]})
        check("4.2 SN 数量不符被拒", biz_failed(s, d), f"HTTP {s} {str(d)[:120]}")
    else:
        check("4.2 SN 数量不符被拒（数量=1，跳过构造）", True)

    s, d = call("POST", "/api/transferOrder/outbound", b_kp["token"],
                json={"orderNo": order_no, "serialNos": sns[:qty]})
    vo = data_of(d) or {}
    check("4.3 确认出库（OUTBOUND 在途，串码挂 B 仓）",
          s == 200 and vo.get("status") == "OUTBOUND", f"HTTP {s} {str(d)[:150]}")
    after_out_b = available(B, material, b_kp["token"])
    check("4.4 出库后 B 仓可用 -%d（转在途）" % qty, after_out_b == before_b - qty,
          f"{before_b}→{after_out_b}")

    s, d = call("GET", "/api/transferOrder/notices?limit=20", a_kp["token"])
    check("4.5 A 仓管收到「确认入库」通知",
          s == 200 and any(n.get("noticeType") == "OUTBOUND" and n.get("orderNo") == order_no
                           for n in (data_of(d) or [])),
          f"{len(data_of(d) or [])} 条")

    print("\n[5] A 仓仓管：确认入库")
    s, d = call("POST", "/api/transferOrder/inbound", a_kp["token"],
                json={"orderNo": order_no, "operator": "xn_kg"})
    vo = data_of(d) or {}
    check("5.1 入库完成（DONE）", s == 200 and vo.get("status") == "DONE", f"HTTP {s} {str(d)[:150]}")
    check("5.2 回填实际调拨数量", int(vo.get("movedQuantity") or 0) == qty,
          f"movedQuantity={vo.get('movedQuantity')} 期望 {qty}")
    after_b = available(B, material, b_kp["token"])
    after_a = available(A, material, a_acc["token"])
    print(f"      B: {before_b} → {after_b}（应 -{qty}）")
    print(f"      A: {before_a} → {after_a}（应 +{qty}）")
    check("5.3 B 仓减少准确", after_b == before_b - qty, f"{before_b}→{after_b}")
    check("5.4 A 仓增加准确", after_a == before_a + qty, f"{before_a}→{after_a}")

    s, d = call("GET", f"/api/transferOrder/notices?limit=20", a_acc["token"])
    check("5.5 申请方（A 账管）收到「已完成」通知",
          s == 200 and any(n.get("noticeType") == "DONE" and n.get("orderNo") == order_no
                           for n in (data_of(d) or [])),
          f"{len(data_of(d) or [])} 条")

    s, d = call("POST", "/api/transferOrder/inbound", a_kp["token"],
                json={"orderNo": order_no, "operator": "xn_kg"})
    check("5.6 已完成单重复入库被拒", biz_failed(s, d), f"HTTP {s} {str(d)[:120]}")

    s, d = call("GET", f"/api/transferOrder/get?orderNo={order_no}", a_acc["token"])
    vo = data_of(d) or {}
    check("5.7 详情含状态标签与时间线（申请/出库/入库）",
          vo.get("statusLabel") == "已完成" and vo.get("outboundAt") and vo.get("inboundAt"),
          str(vo)[:150])

    # ---- 2. 驳回闭环 ----
    print("\n[6] 驳回闭环：A 账管申请 → B 仓管出库 → A 仓管驳回入库（货退回 B）")
    s, d = create_apply(a_acc, material, model, material_name, qty)
    o2 = (data_of(d) or {}).get("orderNo")
    check("6.1 第二单申请（APPLIED）", s == 200 and o2, f"HTTP {s} {str(d)[:120]}")
    if not o2:
        return summary(order_no)

    sns2 = serial_nos(B, material, qty, b_kp["token"])
    s, d = call("POST", "/api/transferOrder/outbound", b_kp["token"],
                json={"orderNo": o2, "serialNos": sns2[:qty]})
    check("6.2 B 仓管出库（OUTBOUND）", s == 200 and (data_of(d) or {}).get("status") == "OUTBOUND",
          f"HTTP {s} {str(d)[:120]}")

    s, d = call("POST", "/api/transferOrder/reject", b_kp["token"],
                json={"orderNo": o2, "reason": "越权探针"})
    check("6.3 B 仓管对在途单驳回被拒（驳回限调入方仓管）", biz_failed(s, d), f"HTTP {s} {str(d)[:120]}")

    mid_b = available(B, material, b_kp["token"])
    s, d = call("POST", "/api/transferOrder/reject", a_kp["token"],
                json={"orderNo": o2, "reason": "测试驳回：入库核对不符"})
    vo = data_of(d) or {}
    check("6.4 A 仓管驳回入库（REJECTED）", s == 200 and vo.get("status") == "REJECTED",
          f"HTTP {s} {str(d)[:150]}")
    check("6.5 驳回理由回传", vo.get("rejectReason") == "测试驳回：入库核对不符",
          str(vo.get("rejectReason"))[:60])
    back_b = available(B, material, b_kp["token"])
    check("6.6 货退回 B 仓可用（在途归零）", back_b == mid_b + qty, f"{mid_b}→{back_b}")
    check("6.7 A 仓库存未被污染", available(A, material, a_acc["token"]) == after_a, "A 不变")

    return summary(order_no)


def summary(order_no=None):
    ok = sum(1 for _, o in RESULTS if o)
    print("\n" + "=" * 70)
    print(f"通过 {ok}/{len(RESULTS)}" + (f"  工单号 {order_no}" if order_no else ""))
    for n, o in RESULTS:
        if not o:
            print(f"  ✘ {n}")
    print("=" * 70)
    return 0 if ok == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())