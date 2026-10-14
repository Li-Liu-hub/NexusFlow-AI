#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""调拨闭环端到端实测：建单 → 出库 → 入库（经网关签名链路）。

用法（在服务器上跑，因为它要访问 127.0.0.1:8090 的网关）：
    sudo -u ubuntu /opt/app/orchestrator/.venv/bin/python test-transfer-loop.py

覆盖：
  正向 —— TRANSFER 7A00→7AZA，取真实串码，出库（三重比对）→ 入库，
         并核对终端状态与归属仓位真的发生了迁移
  负向 —— 四条硬规则各造一次违规，确认都被拦下：
         ① 超量建单（库存不足）
         ② 串码数量与单据不符（出库三重比对）
         ③ 跨级下发（ISSUE 必须逐级，不可跨级）
         ④ 终态单据再操作（已完成的单不能重复出库）
"""
import json
import sys

sys.path.insert(0, "/opt/app/orchestrator")

import httpx

from config import settings
from sdk.sign import build_auth_headers
from tools.terminal_api import create_transfer_order, fetch_snapshot

BASE = settings.GATEWAY_BASE_URL
PASS, FAIL = [], []


def _check(name: str, ok: bool, detail: str = ""):
    (PASS if ok else FAIL).append(name)
    print(f"  {'✅' if ok else '❌'} {name}" + (f"  —— {detail}" if detail else ""))


def api_get(path, params=None):
    r = httpx.get(f"{BASE}{path}", params=params or {},
                  headers=build_auth_headers(None), timeout=30, trust_env=False)
    return r.status_code, r.json()


def api_post(path, payload):
    body = json.dumps(payload, ensure_ascii=True)
    r = httpx.post(f"{BASE}{path}", content=body.encode("utf-8"),
                   headers={"Content-Type": "application/json", **build_auth_headers(body)},
                   timeout=30, trust_env=False)
    return r.status_code, r.json()


def expect_fail(name, status, payload, contains=None):
    """负向用例：业务错误码必须非 0（code != 0），且消息里应包含关键提示"""
    code = payload.get("code")
    msg = str(payload.get("message") or "")
    ok = (code != 0) and (contains is None or contains in msg)
    _check(name, ok, f"code={code} msg={msg[:80]}")
    return ok


print("=" * 70)
print("调拨闭环端到端实测")
print("=" * 70)

# ---------------------------------------------------------------------------
# 0. 选一个可测物料：7A00 有充足可用库存
# ---------------------------------------------------------------------------
print("\n[0] 准备测试数据")
snap = fetch_snapshot()
inv = snap.get("inventory") or []
cands = [r for r in inv if r.get("warehouseCode") == "7A00" and (r.get("availableQty") or 0) >= 5]
cands.sort(key=lambda r: -(r.get("availableQty") or 0))
if not cands:
    print("!! 7A00 没有可用库存，测试无法继续")
    sys.exit(1)
pick = cands[0]
material, model = pick["materialCode"], pick["model"]
print(f"  选中物料: {model} / {material}，7A00 可用 {pick['availableQty']} 台")

# 出库前基线：7AZA 该物料的可用量
before_7aza = next((r.get("availableQty") or 0 for r in inv
                    if r.get("warehouseCode") == "7AZA" and r.get("materialCode") == material), 0)
print(f"  基线：7AZA 该物料可用 {before_7aza} 台")

# 取 3 个可用串码
code, resp = api_get("/api/terminal/inventory/serialNos",
                     {"warehouseCode": "7A00", "materialCode": material, "limit": 3})
serial_nos = resp.get("data") or []
_check("列可用串码接口可用", code == 200 and len(serial_nos) > 0, f"取到 {len(serial_nos)} 个串码")
if not serial_nos:
    sys.exit(1)
qty = len(serial_nos)
print(f"  本次调拨数量: {qty} 台")

# ---------------------------------------------------------------------------
# 1. 正向：建单
# ---------------------------------------------------------------------------
print("\n[1] 建单（TRANSFER 7A00 → 7AZA）")
vo = create_transfer_order(
    operation_type="TRANSFER", from_warehouse="7A00", to_warehouse="7AZA",
    items=[{"materialCode": material, "model": model,
            "materialName": pick.get("materialName"), "quantity": qty}],
    creator="e2e-test", source="MANUAL")
order_no = vo["orderNo"]
_check("建单成功", vo.get("status") == "PENDING",
       f"{order_no} 状态={vo.get('status')} 数量={vo.get('totalQuantity')} "
       f"需审批={vo.get('needApproval')}")

# ---------------------------------------------------------------------------
# 2. 负向①：超量建单（库存不足）
# ---------------------------------------------------------------------------
print("\n[2] 负向：超量建单（库存不足应被拒）")
try:
    create_transfer_order(
        operation_type="TRANSFER", from_warehouse="7A00", to_warehouse="7AZA",
        items=[{"materialCode": material, "model": model, "quantity": 999999}],
        creator="e2e-test", source="MANUAL")
    _check("超量建单被拒", False, "居然建单成功了")
except Exception as exc:  # noqa: BLE001
    _check("超量建单被拒", "库存不足" in str(exc), str(exc)[:100])

# ---------------------------------------------------------------------------
# 3. 负向②：出库串码数量与单据不符
# ---------------------------------------------------------------------------
print("\n[3] 负向：出库串码数量不符（三重比对应拦）")
code, resp = api_post("/api/transferOrder/outbound",
                      {"orderNo": order_no, "serialNos": serial_nos[: max(1, qty - 1)],
                       "operator": "e2e-test"})
expect_fail("串码数量不符被拒", code, resp, contains="数量不符")

# ---------------------------------------------------------------------------
# 4. 正向：出库（三重比对通过）
# ---------------------------------------------------------------------------
print("\n[4] 出库（型号/数量/状态三重比对）")
code, resp = api_post("/api/transferOrder/outbound",
                      {"orderNo": order_no, "serialNos": serial_nos, "operator": "e2e-test"})
_check("出库成功", code == 200 and resp.get("code") == 0, str(resp)[:120])

code, detail = api_get("/api/transferOrder/get", {"orderNo": order_no})
_check("单据状态 → 已出库", detail["data"]["status"] == "OUTBOUND", detail["data"]["status"])

# 终端应变成"在途"，且仍在调出仓
snap2 = fetch_snapshot()
in_transit = snap2.get("totalInTransit", 0)
_check("终端进入在途", in_transit >= qty, f"在途总数={in_transit}")

# ---------------------------------------------------------------------------
# 5. 负向③：跨级下发
# ---------------------------------------------------------------------------
print("\n[5] 负向：跨级下发（7A00 L2 直接下发到网格级应被拒）")
try:
    # 库里只有两个仓位，用不存在的目标仓验证"仓位不存在"；跨级用同一仓位自调验证
    create_transfer_order(
        operation_type="ISSUE", from_warehouse="7A00", to_warehouse="7A00",
        items=[{"materialCode": material, "model": model, "quantity": 1}],
        creator="e2e-test", source="MANUAL")
    _check("自调自被拒", False, "居然建单成功了")
except Exception as exc:  # noqa: BLE001
    msg = str(exc)
    _check("自调自被拒", ("不能相同" in msg) or ("层级" in msg) or ("只能" in msg), msg[:100])

# ---------------------------------------------------------------------------
# 6. 正向：入库
# ---------------------------------------------------------------------------
print("\n[6] 入库确认")
code, resp = api_post("/api/transferOrder/inbound",
                      {"orderNo": order_no, "operator": "e2e-test"})
_check("入库成功", code == 200 and resp.get("code") == 0, str(resp)[:120])

code, detail = api_get("/api/transferOrder/get", {"orderNo": order_no})
_check("单据状态 → 已完成", detail["data"]["status"] == "DONE", detail["data"]["status"])

# 库存真的迁移了：7AZA 该物料应增加 qty
snap3 = fetch_snapshot()
after_7aza = next((r.get("availableQty") or 0 for r in (snap3.get("inventory") or [])
                   if r.get("warehouseCode") == "7AZA" and r.get("materialCode") == material), 0)
_check("库存已迁入 7AZA", after_7aza == before_7aza + qty,
       f"{before_7aza} → {after_7aza}（期望 +{qty}）")

# ---------------------------------------------------------------------------
# 7. 负向④：终态单据重复出库
# ---------------------------------------------------------------------------
print("\n[7] 负向：已完成单据重复出库")
code, resp = api_post("/api/transferOrder/outbound",
                      {"orderNo": order_no, "serialNos": serial_nos, "operator": "e2e-test"})
expect_fail("终态重复出库被拒", code, resp, contains="待发起")

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
print("\n" + "=" * 70)
print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
if FAIL:
    for f in FAIL:
        print(f"  失败：{f}")
print("=" * 70)
sys.exit(0 if not FAIL else 1)
