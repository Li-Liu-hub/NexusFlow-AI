#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""远端数据可见范围收口 E2E（2026-10-08 [terminal 范围校验 + list/page 过滤]；stdlib-only）

收口背景（§5.15 遗留，本次闭环）：
  /api/terminal/** 取数接口此前以 query 的 warehouseCode 为准（自报参数），任意登录用户
  改参数即可读别人仓库的库存/串码/入库数据；transferOrder/list/page 也不过滤，
  可翻到与自己无关的工单。本次收口：门户通道（网关注入 X-User-*）下 ——
    1) 显式传入的仓位必须 ⊆ 可见范围（本仓 + 下级仓，口径同编排层 visible_scope），否则 40101
    2) 不带仓位参数的取数默认收敛到本仓（aggregate 例外：按范围过滤行，兼容门户首页）
    3) transferOrder/list/page 只返回「调出仓或调入仓任一端命中可见范围」的工单
    4) 服务方通道（AK/SK 签名，编排层/内核工具）不受影响 —— 全量取数是编排层过滤的前置

本脚本验证（A=xn_zg/9BZA 账管，B=dn_zg/9B00 账管）：
  A 组（A 令牌）：aggregate 行全在可见范围 / 跨仓取数被拒（aggregate·snapshot·aging·
     serialNos·health·inboundTrend）/ 缺省仓收敛本仓（summary 缺省 == 显式 9BZA）
  B 组（B 令牌）：跨仓（9BZA）取数被拒 / list/page 只含本仓工单 / 仓位条件无法放大越界
  C 组（AK/SK）：跨仓 snapshot 仍 200（服务方通道不受影响）

用法：
    GW=http://49.234.49.192 python3 _r15-remote-data-scope-e2e.py
"""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

GW = os.environ.get("GW", "http://127.0.0.1:8090")
PWD = "123456"
AK, SK = "aismp-console", "7c1f9a3e5d0b2846af31c7e9b0d45268"     # 服务方签名凭证（验证 AK/SK 通道不受影响）
A_ACC, B_ACC = "xn_zg", "dn_zg"  # A=9BZA 账管（调入仓）、B=9B00 账管（调出仓）
A_WH, B_WH = "9BZA", "9B00"
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
        t = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(t)
        except Exception:
            return e.code, t[:200]
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"


def login(user, pwd=PWD):
    s, d = req("POST", "/api/auth/login", body={"userCode": user, "password": pwd})
    if s != 200 or not isinstance(d, dict) or not d.get("token"):
        raise SystemExit(f"登录失败 {user}: HTTP {s} {str(d)[:200]}")
    return d["token"]


def denied(status, payload) -> bool:
    """被拒：非 200，或业务码非 0（全局异常处理器把 NO_AUTH 落进响应体 code=40101）"""
    if status != 200:
        return status >= 400
    return isinstance(payload, dict) and payload.get("code") not in (0, None)


def rows_of(payload):
    return (payload or {}).get("data") or []


def subtree(root, token):
    """按 warehouse/list 现场算 root 的可见子树（与网关/编排层同口径：本仓 + 下级仓递归）。"""
    s, d = req("GET", "/api/terminal/warehouse/list", token=token)
    children = {}
    for w in rows_of(d):
        children.setdefault(w.get("parentCode") or "", []).append(w.get("warehouseCode") or "")
    scope, stack = set(), [root]
    while stack:
        c = stack.pop()
        if not c or c in scope:
            continue
        scope.add(c)
        stack.extend(children.get(c, []))
    return scope


def main():
    print("=" * 66)
    print(f"远端数据可见范围收口 E2E   GW={GW}")
    print("=" * 66)

    ta = login(A_ACC)
    tb = login(B_ACC)
    check("0.1 A/B 双账管登录", bool(ta) and bool(tb))
    scope_a = subtree(A_WH, ta)
    # 远端东宁系只有两仓且 9B00(L2) 是 9BZA(L3) 的上级 —— 子树方向断言
    check("0.2 9BZA 可见子树（含自身，不含上级 9B00）",
          A_WH in scope_a and B_WH not in scope_a, str(sorted(scope_a)))
    scope_b = subtree(B_WH, tb)
    check("0.3 9B00 可见子树（上级含下级 9BZA）",
          B_WH in scope_b and A_WH in scope_b, str(sorted(scope_b)))

    # ── A 组：xn_zg（9BZA）────────────────────────────────────────────
    s, d = req("GET", "/api/terminal/inventory/aggregate", token=ta)
    rows = rows_of(d)
    off = sorted({r.get("warehouseCode") for r in rows
                  if isinstance(r, dict) and r.get("warehouseCode") not in scope_a})
    check("A1 aggregate（缺省）行全在可见范围内",
          s == 200 and rows and not off, f"HTTP {s} 越界仓 {off} 行数 {len(rows)}")

    s, d = req("GET", f"/api/terminal/inventory/aggregate?warehouseCode={B_WH}", token=ta)
    check("A2 aggregate 显式跨仓（9B00）→ 拒", denied(s, d), f"HTTP {s} {str(d)[:120]}")

    s, d = req("GET", f"/api/terminal/inventory/snapshot?warehouseCode={B_WH}", token=ta)
    check("A3 snapshot 跨仓 → 拒", denied(s, d), f"HTTP {s} {str(d)[:120]}")

    s, d = req("GET", f"/api/terminal/inventory/aging?warehouseCode={B_WH}", token=ta)
    check("A4 aging 跨仓 → 拒", denied(s, d), f"HTTP {s} {str(d)[:120]}")

    s, d = req("GET", f"/api/terminal/inventory/serialNos?warehouseCode={B_WH}"
               f"&materialCode={PROBE_MATERIAL}", token=ta)
    check("A5 serialNos 跨仓（串码明细）→ 拒", denied(s, d), f"HTTP {s} {str(d)[:120]}")

    s, d = req("GET", f"/api/terminal/health/suggest?warehouseCode={B_WH}", token=ta)
    check("A6 health/suggest 跨仓 → 拒", denied(s, d), f"HTTP {s} {str(d)[:120]}")

    s, d = req("GET", f"/api/terminal/inventory/inboundTrend?warehouseCode={B_WH}", token=ta)
    check("A7 inboundTrend 跨仓 → 拒", denied(s, d), f"HTTP {s} {str(d)[:120]}")

    # 缺省仓收敛本仓：不带参数 == 显式 9BZA（汇总口径，字段 totalCnt）
    s1, d1 = req("GET", "/api/terminal/inventory/inboundSummary", token=ta)
    s2, d2 = req("GET", f"/api/terminal/inventory/inboundSummary?warehouseCode={A_WH}", token=ta)
    t1 = ((d1 or {}).get("data") or {}).get("totalCnt")
    t2 = ((d2 or {}).get("data") or {}).get("totalCnt")
    check("A8 inboundSummary 缺省收敛本仓（== 显式 9BZA）",
          s1 == 200 and s2 == 200 and t1 is not None and t1 == t2,
          f"缺省 {t1} vs 显式 {t2}")

    # ── B 组：dn_zg（9B00 上级）+ 工单列表 ───────────────────────────
    # 远端只有东宁两仓且 9BZA ⊂ 9B00 子树，「跨树拒绝」方向已由 A 组覆盖；
    # 这里正向断言：上级（9B00）读下级（9BZA）放行，且返回行 ⊆ 可见子树。
    s, d = req("GET", f"/api/terminal/inventory/aggregate?warehouseCode={A_WH}", token=tb)
    b_rows = rows_of(d)
    b_off = sorted({r.get("warehouseCode") for r in b_rows
                    if isinstance(r, dict) and r.get("warehouseCode") not in scope_b})
    check("B1 上级（9B00）读下级（9BZA）放行且行在可见范围内",
          s == 200 and b_rows and not b_off, f"HTTP {s} 越界 {b_off}")

    s, d = req("GET", "/api/transferOrder/list/page?current=1&pageSize=50", token=tb)
    orders = ((d or {}).get("data") or {}).get("records") or []
    bad = [o.get("orderNo") for o in orders
           if isinstance(o, dict)
           and o.get("fromWarehouse") not in scope_b
           and o.get("toWarehouse") not in scope_b]
    check("B2 list/page（B 令牌）工单均与本仓相关（无他仓工单）",
          s == 200 and not bad, f"HTTP {s} 越界 {bad[:5]} 共 {len(orders)} 单")

    # 仓位自报条件只能在可见范围内收窄：from=9BZA 的结果必须确实全部 from=9BZA
    # 且两侧仓都在 B 的可见子树内（不能借条件翻到子树之外）
    s, d = req("GET", f"/api/transferOrder/list/page?current=1&pageSize=50&fromWarehouse={A_WH}",
               token=tb)
    got = (((d or {}).get("data") or {}).get("records")) or []
    esc = [o.get("orderNo") for o in got if isinstance(o, dict) and (
            o.get("fromWarehouse") != A_WH
            or o.get("fromWarehouse") not in scope_b
            or o.get("toWarehouse") not in scope_b)]
    check("B3 list/page 自报仓位条件只能收窄、不能越界",
          s == 200 and got and not esc, f"HTTP {s} 异常 {esc[:5]} 共 {len(got)} 单")

    # ── C 组：AK/SK 通道不受影响（编排层/内核工具依赖全量取数）────────
    sign_headers = {
        "accessKey": AK,
        "nonce": uuid.uuid4().hex,
        "timestamp": str(int(time.time())),
        "sign": hashlib.sha256(("null." + SK).encode()).hexdigest(),
    }
    s, d = req("GET", f"/api/terminal/inventory/snapshot?warehouseCode={B_WH}",
               extra_headers=sign_headers)
    ok = s == 200 and isinstance(d, dict) and d.get("code") == 0
    check("C1 AK/SK 跨仓 snapshot 仍放行（服务方通道语义不变）",
          ok, f"HTTP {s} {str(d)[:120]}")

    print("=" * 66)
    passed = sum(1 for _, ok in RESULTS if ok)
    print(f"结果：{passed}/{len(RESULTS)} 通过")
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
