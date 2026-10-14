"""库存告警 → 数字员工 → 调拨工单 → 双角色确认 → 结果回传：全链路 E2E（2026-10-07）。

覆盖用户描述的完整业务闭环（2026-10-08 适配新调拨链路）：
  ① 定时/手动扫描库存 → 告警写进 A 仓账管的通知表
  ② A 账管问数字员工要补货建议（健康检查建议里含"从 B 仓调入"）
  ③ A 账管提交调拨申请（APPLIED）→ 直接落在 B 仓仓管通知表（无账管确认环节）
  ④ B 仓管 拒绝申请（带理由）→ 回传 A 账管；
     另一单走同意路径：B 仓管导入 SN 出库（OUTBOUND）→ A 仓管确认入库（DONE）

跑法（本机全栈已起）：python stock-alert-closed-loop-e2e.py
失败返回非 0。
"""

import json
import sys
import time
import uuid

import httpx

GW = "http://127.0.0.1:8090"
A, B = "9BZA", "9B00"   # A 缺货（调入）、B 调出
A_ACC, B_ACC, B_KP, A_KP = "xn_zg", "dn_zg", "dn_kg", "xn_kg"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(("  PASS " if ok else "  FAIL ") + name + ("  | " + detail if detail else ""))


def main():
    client = httpx.Client(timeout=240)
    ts = time.strftime("%H%M%S")

    print("[0] 四角色登录（门户 Bearer 通道）")
    toks = {}
    for u in (A_ACC, B_ACC, B_KP, A_KP):
        r = client.post(GW + "/api/auth/login", json={"userCode": u, "password": "123456"})
        toks[u] = (r.json() or {}).get("token")
    h_a = {"Authorization": f"Bearer {toks[A_ACC]}"}
    h_bacc = {"Authorization": f"Bearer {toks[B_ACC]}"}
    h_bkp = {"Authorization": f"Bearer {toks[B_KP]}"}
    h_akp = {"Authorization": f"Bearer {toks[A_KP]}"}
    check("四角色登录", all(toks.values()),
          f"A账管={bool(toks[A_ACC])} B账管={bool(toks[B_ACC])} B仓管={bool(toks[B_KP])} A仓管={bool(toks[A_KP])}")
    if not all(toks.values()):
        return finish()

    def notices(headers, wh, role, user):
        r = client.get(GW + "/api/transferOrder/notices", headers=headers,
                       params={"warehouseCode": wh, "role": role, "userCode": user, "limit": 30})
        return (r.json() or {}).get("data") or []

    # ---------------------------------------------------------------
    print("[1] 库存告警巡检 → A 仓账管通知表")
    # ---------------------------------------------------------------
    # 2026-10-07 调整：巡检闭环（scan → 判定 → notify）上移到编排层 ——
    # agent 只提供原子能力（扫描出发现清单 / 写通知），定时与投递由编排层 alert 图负责；
    # 门户「扫描本仓」与这里的触发方式一致，都走 /api/orchestrator/v1/alert/scan。
    r = client.post(GW + "/api/orchestrator/v1/alert/scan", headers=h_a,
                    params={"warehouse_code": A})
    scan = r.json() or {}   # 编排层返回裸 JSON 摘要（无 BaseResponse 外壳）
    check("编排层触发巡检（scan→判定→notify）",
          r.status_code == 200 and scan.get("scanned") == 1 and not scan.get("scanError"),
          f"http={r.status_code} {scan}")

    alerts = [n for n in notices(h_a, A, "ACCOUNT_MANAGER", A_ACC)
              if n.get("noticeType") == "STOCK_ALERT"]
    check("A 仓账管收到库存告警", bool(alerts),
          f"告警 {len(alerts)} 条 | {alerts[0]['title'] if alerts else '无'}")

    # ---------------------------------------------------------------
    print("[2] A 账管问数字员工要补货建议（编排层 preview：与门户健康卡同口径）")
    # ---------------------------------------------------------------
    # 2026-10-07 口径统一后，门户健康卡的补货建议来自编排层确定性端点
    # /orchestrator/v1/replenish/preview（同 plan_replenishment 算法）；这里与其保持一致。
    r = client.post(GW + "/api/orchestrator/v1/replenish/preview", headers=h_a,
                    params={"warehouse_code": A}, json={})
    pv = r.json() or {}
    inbound = [t for t in (pv.get("transfers") or []) if t.get("toWarehouse") == A]
    check("补货建议给出「调入本仓」的调拨行", r.status_code == 200 and bool(inbound),
          f"transfers={len(pv.get('transfers') or [])} 调入本仓 {len(inbound)}")
    if not inbound:
        return finish()
    sug = inbound[0]
    check("建议指向调出仓 B 且含数量",
          sug.get("fromWarehouse") == B and int(sug.get("quantity") or 0) > 0,
          f"{sug.get('model')} {sug.get('fromWarehouse')}→{sug.get('toWarehouse')} {sug.get('quantity')} 台")

    before_b = available(client, h_a, B, sug["materialCode"])
    before_a = available(client, h_a, A, sug["materialCode"])
    print(f"      调拨前：B({B})={before_b} 台，A({A})={before_a} 台")

    # ---------------------------------------------------------------
    print("[3] A 账管「提交调拨申请」→ B 仓管通知（2026-10-08 新链路：无账管确认环节）")
    # ---------------------------------------------------------------
    def apply(qty):
        r = client.post(GW + "/api/transferOrder/create", headers=h_a, json={
            "operationType": "TRANSFER", "fromWarehouse": B, "toWarehouse": A,
            "creator": A_ACC, "source": "MANUAL",
            "items": [{"materialCode": sug["materialCode"], "model": sug["model"],
                       "materialName": sug["materialName"], "quantity": qty}],
        })
        return (r.json() or {}).get("data") or {}

    vo1 = apply(int(sug.get("quantity") or 1))
    order_no = vo1.get("orderNo")
    check("提交申请（APPLIED）", bool(order_no) and vo1.get("status") == "APPLIED",
          f"orderNo={order_no} status={vo1.get('status')}")

    b_notices = [n for n in notices(h_bkp, B, "WAREHOUSE_KEEPER", B_KP)
                 if n.get("orderNo") == order_no]
    check("B 仓管收到「待确认出库」通知", any(n.get("noticeType") == "ORDER_APPLIED" for n in b_notices),
          f"B 仓管通知 {len(b_notices)} 条")

    # ---------------------------------------------------------------
    print("[4] B 仓管【拒绝申请】→ 理由必须回传 A 账管")
    # ---------------------------------------------------------------
    reason = f"本仓该型号需保留自用（E2E {ts}）"
    r = client.post(GW + "/api/transferOrder/reject", headers=h_bkp,
                    json={"orderNo": order_no, "operator": B_KP, "reason": reason})
    rej = (r.json() or {}).get("data")
    check("B 仓管拒绝申请成功", r.status_code == 200 and isinstance(rej, dict)
          and rej.get("status") == "REJECTED", f"http={r.status_code} body={r.text[:120]}")

    r = client.get(GW + "/api/transferOrder/get", headers=h_a, params={"orderNo": order_no})
    vo = (r.json() or {}).get("data") or {}
    check("单据状态 REJECTED 且带理由", vo.get("status") == "REJECTED" and reason in str(vo.get("rejectReason")),
          f"status={vo.get('status')} reason={vo.get('rejectReason')}")

    a_rej = [n for n in notices(h_a, A, "ACCOUNT_MANAGER", A_ACC)
             if n.get("orderNo") == order_no and n.get("noticeType") == "REJECTED"]
    check("拒绝结果回传 A 账管（含理由）", bool(a_rej) and reason in str(a_rej[0].get("content")),
          a_rej[0]['content'][:80] if a_rej else "未收到拒绝通知")

    # ---------------------------------------------------------------
    print("[5] 第二单走【同意】路径：B 仓管导 SN 出库 → A 仓管入库 → 库存 B 减 A 增")
    # ---------------------------------------------------------------
    order2 = apply(10).get("orderNo")
    check("第二单提交申请", bool(order2), f"orderNo={order2}")

    r = client.get(GW + "/api/terminal/inventory/serialNos", headers=h_bkp,
                   params={"warehouseCode": B, "materialCode": sug["materialCode"], "limit": 10})
    sns = (r.json() or {}).get("data") or []
    check("B 仓管取得可用 SN（10 个）", len(sns) >= 10, f"拿到 {len(sns)} 个")

    r = client.post(GW + "/api/transferOrder/outbound", headers=h_bkp,
                    json={"orderNo": order2, "serialNos": sns[:10]})
    out = (r.json() or {}).get("data") or {}
    check("B 仓管确认出库（OUTBOUND 在途）", out.get("status") == "OUTBOUND", f"status={out.get('status')}")

    a_kp_notices = [n for n in notices(h_akp, A, "WAREHOUSE_KEEPER", A_KP)
                    if n.get("orderNo") == order2]
    check("A 仓管收到「确认入库」通知", any(n.get("noticeType") == "OUTBOUND" for n in a_kp_notices),
          f"A 仓管通知 {len(a_kp_notices)} 条")

    r = client.post(GW + "/api/transferOrder/inbound", headers=h_akp,
                    json={"orderNo": order2, "operator": A_KP})
    done = (r.json() or {}).get("data") or {}
    check("A 仓管确认入库（DONE）", done.get("status") == "DONE", f"status={done.get('status')}")

    after_b = available(client, h_a, B, sug["materialCode"])
    after_a = available(client, h_a, A, sug["materialCode"])
    print(f"      调拨后：B({B})={after_b} 台，A({A})={after_a} 台（原 B={before_b} A={before_a}）")
    check("B 仓库存减少 10 台", after_b == before_b - 10, f"{before_b} → {after_b}")
    check("A 仓库存增加 10 台", after_a == before_a + 10, f"{before_a} → {after_a}")

    a_done = [n for n in notices(h_a, A, "ACCOUNT_MANAGER", A_ACC)
              if n.get("orderNo") == order2 and n.get("noticeType") == "DONE"]
    check("完成结果回传 A 账管", bool(a_done),
          a_done[0]['content'][:80] if a_done else "未收到完成通知")

    return finish()


def available(client, headers, wh, material):
    r = client.get(GW + "/api/terminal/inventory/aggregate", headers=headers, params={"warehouseCode": wh})
    rows = (r.json() or {}).get("data") or []
    return sum(int(x.get("availableQty") or 0) for x in rows if x.get("materialCode") == material)


def finish():
    fails = [n for n, ok in RESULTS if not ok]
    print("=" * 66)
    print(f"库存告警全链路 E2E：{len(RESULTS) - len(fails)}/{len(RESULTS)} 通过")
    if fails:
        print("失败项：" + "、".join(fails))
        return 1
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())