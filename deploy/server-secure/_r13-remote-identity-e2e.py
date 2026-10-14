#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""远端身份链路越权用例 E2E（2026-10-07 [数据权限信任根] 修复验收，stdlib-only）

背景：修复前 /api/orchestrator/** 从 query 读 user_id/warehouse_code，浏览器可随意改写；
修复后网关在鉴权通过后**先剥离**客户端自带的 X-User-* 再注入权威身份（CustomGlobalFilter#injectPortalIdentity），
编排层以注入头为准（server.py#gateway_identity）。

链路：登录 → 本人列表 → 篡改 query → 伪造请求头 → 跨用户读详情 → 未登录
用法：
    在服务器上直连网关：      python3 _r13-remote-identity-e2e.py
    从外部经 nginx 80：       GW=http://49.234.49.192 python3 _r13-remote-identity-e2e.py
"""
import json
import os
import sys
import urllib.error
import urllib.request

GW = os.environ.get("GW", "http://127.0.0.1:8090")
A_USER, B_USER = "xn_zg", "dn_zg"      # A=9BZA 账管（有历史会话）；B=9B00 账管（无历史会话）
PWD = "123456"

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


def conv_ids(payload):
    if not isinstance(payload, list):
        return set()
    return {r.get("conversation_id") for r in payload
            if isinstance(r, dict) and r.get("conversation_id")}


def main():
    print("=" * 66)
    print(f"远端身份链路越权用例 E2E   GW={GW}")
    print("=" * 66)

    a = login(A_USER)
    b = login(B_USER)
    check("0.1 登录返回身份（userCode / warehouseCode）",
          a.get("userCode") == A_USER and bool(a.get("warehouseCode")), str(a)[:140])

    s_own, own = req("GET", "/api/orchestrator/v1/conversation/list?limit=20", token=a["token"])
    own_ids = conv_ids(own)
    check("0.2 A 本人会话列表可读", s_own == 200 and isinstance(own, list),
          f"HTTP {s_own} {str(own)[:120]}")

    # 1) 篡改 query user_id —— 修复前：能看别人的会话列表；修复后：query 被忽略
    s_sp, sp = req("GET", f"/api/orchestrator/v1/conversation/list?user_id={B_USER}&limit=20",
                   token=a["token"])
    check("1.1 篡改 query user_id 不放大可见范围（仍为本人列表）",
          s_sp == 200 and conv_ids(sp) == own_ids,
          f"HTTP {s_sp} own={sorted(own_ids)} spoof={sorted(conv_ids(sp))}")

    # 2) 伪造注入头（模拟"修复前服务端信任自报身份"的攻击面）—— 网关必须剥离
    s_hd, hd = req("GET", "/api/orchestrator/v1/conversation/list?limit=20", token=a["token"],
                   extra_headers={"X-User-Id": B_USER, "X-User-Warehouse": "9B00"})
    check("2.1 伪造 X-User-Id 请求头被网关剥离（仍为本人列表）",
          s_hd == 200 and conv_ids(hd) == own_ids,
          f"HTTP {s_hd} got={sorted(conv_ids(hd))}")

    # 3) 会话详情归属校验：A 的会话，B 来读 → 404
    if own_ids:
        cid = sorted(own_ids)[0]
        s_a, d_a = req("GET", f"/api/orchestrator/v1/conversation/{cid}", token=a["token"])
        check("3.1 本人读自己的会话详情 → 200", s_a == 200 and isinstance(d_a, dict),
              f"HTTP {s_a} {str(d_a)[:120]}")
        s_b, d_b = req("GET", f"/api/orchestrator/v1/conversation/{cid}", token=b["token"])
        check("3.2 他人读该会话 → 404（不泄露存在性）", s_b == 404,
              f"HTTP {s_b} {str(d_b)[:120]}")
    else:
        check("3.x 详情用例（需要至少 1 条 xn_zg 历史会话）", False, "A 无历史会话")

    # 4) 未登录 → 401
    s_no, _ = req("GET", "/api/orchestrator/v1/conversation/list")
    check("4.1 未带令牌 → 401", s_no == 401, f"HTTP {s_no}")

    # 5) 两个账号的列表互相独立（按人隔离）
    s_b2, own_b = req("GET", "/api/orchestrator/v1/conversation/list?limit=20", token=b["token"])
    check("5.1 两个账号的列表互相独立",
          s_b2 == 200 and conv_ids(own_b) != own_ids,
          f"A={sorted(own_ids)} B={sorted(conv_ids(own_b))}")

    print("=" * 66)
    passed = sum(1 for _, ok in RESULTS if ok)
    print(f"结果：{passed}/{len(RESULTS)} 通过")
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())