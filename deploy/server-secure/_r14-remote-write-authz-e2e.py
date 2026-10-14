#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""远端写入授权收口 E2E（2026-10-07 [写入授权收口]；2026-10-08 适配新调拨链路，stdlib-only）

修复背景（均经实测复现）：
  1) 身份头此前只在门户通道剥离 —— AK/SK 通道可带伪造 X-User-* 头冒充任意门户用户；
     且编排层无前缀路径（/orchestrator/**）在 AK/SK 通道登记放行
  2) 写链路（申请/出库/入库/驳回）与通知取件此前零授权：operator/creator/查询条件全自报

本脚本验证（走公网 80 或服务器直连网关）：
  0. 三角色登录（A 账管 / A 仓管 / B 账管）
  1. AK/SK + 伪造身份头调编排层 → 401（编排层已收为门户专用）
  2. 申请冒充：xn_zg 令牌带 creator=dn_zg → 落库 creator=xn_zg（以注入身份为准）
  3. 越权出库：xn_kg（调入仓仓管）对「待确认出库」单 outbound → 拒绝（出库限调出仓仓管）
  4. 越权驳回/入库：xn_zg（账管）对「待确认出库」单 reject / inbound → 拒绝
  5. 通知横读：dn_zg 读 xn_zg 的通知 → 只返回自己的（不含 receiver=xn_zg / 9BZA:ACCOUNT_MANAGER）

注意：会在库里创建一张探针工单（APPLIED 状态，货未动），脚本会打印 orderNo，跑完请清理：
  DELETE FROM transfer_order_item WHERE order_no='...'; DELETE FROM transfer_order WHERE order_no='...';

用法：
    GW=http://49.234.49.192 python3 _r14-remote-write-authz-e2e.py
"""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

GW = os.environ.get("GW", "http://127.0.0.1:8090")
PWD = "123456"
AK, SK = "aismp-console", "7c1f9a3e5d0b2846af31c7e9b0d45268"          # 服务方签名凭证（用于验证"非门户通道已关闭"）
A_ACC, B_ACC = "xn_zg", "dn_zg"      # A=9BZA 账管（发起方）、B=9B00 账管（调出方）
FROM_WH, TO_WH = "9B00", "9BZA"
PROBE_MATERIAL = "7471502303"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail and not ok else ""))


def req(method, path, token=None, body=None, extra_headers=None, timeout=60):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if extra_headers:
        headers.update(extra_headers)
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


def login(user, pwd=PWD):
    s, d = req("POST", "/api/auth/login", body={"userCode": user, "password": pwd})
    if s != 200 or not isinstance(d, dict) or not d.get("token"):
        raise SystemExit(f"登录失败 {user}: HTTP {s} {str(d)[:200]}")
    return d


def denied(status, payload) -> bool:
    """动作被拒（agent 无全局异常处理器：业务异常以 HTTP 5xx 返回；兼容 code!=0 的形态）"""
    if status != 200:
        return status >= 400
    return isinstance(payload, dict) and payload.get("code") not in (0, None)


def main():
    print("=" * 66)
    print(f"远端写入授权收口 E2E   GW={GW}")
    print("=" * 66)

    a = login(A_ACC)
    b = login(B_ACC)
    c = login("xn_kg")   # A 仓（调入仓）仓管：越权出库探针用
    check("0.1 三角色登录（A 账管 / B 账管 / A 仓管）",
          bool(a.get("token")) and bool(b.get("token")) and bool(c.get("token")),
          str(a)[:100])

    # 1) AK/SK + 伪造身份头 → 编排层已收为门户专用（401）
    sign_headers = {
        "accessKey": AK,
        "nonce": uuid.uuid4().hex,
        "timestamp": str(int(time.time())),
        "sign": hashlib.sha256(("null." + SK).encode()).hexdigest(),
        "X-User-Id": A_ACC, "X-User-Warehouse": TO_WH,
    }
    s, d = req("POST", "/orchestrator/v1/chat/stream?query=probe&conversation_id=orch-authz-probe",
               extra_headers=sign_headers)
    # 公网入口（nginx）本就未暴露 /orchestrator/**（405）；服务器侧直连网关时由网关拦（401）。
    # 两种都算"已封堵"；服务器侧 401 的取证见部署记录（forge-probe：401 + 无落库）。
    check("1.1 AK/SK + 伪造身份头调编排层 → 已封堵（nginx 405 / 网关 401）",
          s in (401, 405), f"HTTP {s} {str(d)[:120]}")

    # 2) 建单冒充：creator 以注入身份为准
    s, d = req("POST", "/api/transferOrder/create", token=a["token"], body={
        "operationType": "TRANSFER", "fromWarehouse": FROM_WH, "toWarehouse": TO_WH,
        "creator": B_ACC,           # 冒充值：应被注入身份覆盖
        "source": "MANUAL",
        "items": [{"materialCode": PROBE_MATERIAL, "model": "PROBE",
                   "materialName": "授权探针物料", "quantity": 1}],
    })
    order = (d or {}).get("data") or {}
    order_no = order.get("orderNo")
    check("2.1 建单冒充 creator 被忽略（落库为注入身份）",
          s == 200 and order.get("creator") == A_ACC,
          f"HTTP {s} creator={order.get('creator')}")

    if order_no:
        # 3) 越权出库：调入仓仓管（xn_kg/9BZA）对 APPLIED 单出库 → 拒绝（出库限调出仓仓管）
        s, d = req("POST", "/api/transferOrder/outbound", token=c["token"],
                   body={"orderNo": order_no, "serialNos": ["AUTHZ-PROBE"]})
        check("3.1 调入仓仓管出库被拒（越权阻断）", denied(s, d), f"HTTP {s} {str(d)[:120]}")

        # 4) 越权驳回/入库：账管（xn_zg）对 APPLIED 单驳回 / 入库 → 拒绝
        s, d = req("POST", "/api/transferOrder/reject", token=a["token"],
                   body={"orderNo": order_no, "operator": B_ACC, "reason": "authz-probe"})
        check("4.1 账管驳回被拒（驳回限调出仓仓管）", denied(s, d), f"HTTP {s} {str(d)[:120]}")
        s, d = req("POST", "/api/transferOrder/inbound", token=a["token"],
                   body={"orderNo": order_no, "operator": B_ACC})
        check("4.2 账管入库被拒（入库限调入仓仓管）", denied(s, d), f"HTTP {s} {str(d)[:120]}")
    else:
        check("2.2 探针工单创建（后续用例前置）", False, str(d)[:160])

    # 5) 通知横读：dn_zg 声称读 xn_zg 的通知 → 只能拿到自己的
    s, d = req("GET", f"/api/transferOrder/notices?warehouseCode={TO_WH}"
               f"&role=ACCOUNT_MANAGER&userCode={A_ACC}&limit=20", token=b["token"])
    rows = (d or {}).get("data") or []
    leaked = [n for n in rows
              if isinstance(n, dict) and n.get("receiver") in (A_ACC, f"{TO_WH}:ACCOUNT_MANAGER")]
    check("5.1 通知取件按注入身份过滤（无他人通知泄露）",
          s == 200 and not leaked, f"HTTP {s} 泄露 {len(leaked)} 条")

    print("=" * 66)
    if order_no:
        print(f"探针工单（请清理）：{order_no}")
    passed = sum(1 for _, ok in RESULTS if ok)
    print(f"结果：{passed}/{len(RESULTS)} 通过")
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())