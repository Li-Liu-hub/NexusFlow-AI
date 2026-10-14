#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""远端「最近对话 CRUD」E2E（门户 Bearer 通道，stdlib-only；2026-10-08）

覆盖：
    portal_user 登录 → 列表可见种子会话 → PUT 重命名（经网关+编排层归属校验）
    → 他人（xn_zg）改名/删除均 404（越权挡住）→ 本人删除 200 → 详情 404、消息一并清掉
前提：
    编排层已部署 rename/delete 端点；87 号 SQL 已登记 PUT/DELETE（缺一被网关 403）。
    种子会话直接 INSERT 进 PG（orch_conversation），跑完 finally 清理。

用法：
    在服务器上直连网关：      python3 _r17-remote-conversation-crud-e2e.py
    从外部经 nginx 80：       GW=http://49.234.49.192 python3 _r17-remote-conversation-crud-e2e.py
"""
import io
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

GW = os.environ.get("GW", "http://127.0.0.1:8090")
CID = "crud-e2e-20261008"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail and not ok else ""))


def req(method, path, token=None, body=None, timeout=60):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode("utf-8") if body is not None else None
    r = urllib.request.Request(GW + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            t = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(t) if t.strip().startswith(("{", "[", "\"")) else t)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw[:200]
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"


def login(user, pwd="123456"):
    s, d = req("POST", "/api/auth/login", body={"userCode": user, "password": pwd})
    if s != 200 or not isinstance(d, dict) or not d.get("token"):
        raise SystemExit(f"登录失败 {user}: HTTP {s} {str(d)[:200]}")
    return d


def pg_env():
    """从编排层 .env 读 PG 连接（该文件 root-only，走 sudo cat）。"""
    out = subprocess.run(["sudo", "cat", "/opt/app/orchestrator/.env"],
                         capture_output=True, text=True)
    env = {}
    for line in out.stdout.splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def psql(env, sql):
    db, user = env.get("PG_DATABASE", "terminal_kb"), env.get("PG_USER", "postgres")
    out = subprocess.run(["sudo", "-u", "postgres", "psql", "-U", "postgres", "-d", db,
                          "-At", "-c", sql], capture_output=True, text=True,
                         env={**os.environ, "PGPASSWORD": env.get("PG_PASSWORD", "")})
    if out.returncode != 0:
        raise RuntimeError(f"psql 失败: {out.stderr.strip()[:200]}")
    return out.stdout.strip()


def cleanup(env):
    psql(env, f"DELETE FROM orch_message WHERE conversation_id='{CID}'; "
              f"DELETE FROM orch_conversation WHERE id='{CID}';")


def main():
    env = pg_env()
    cleanup(env)
    a, b = login("portal_user"), login("xn_zg")

    print("== 种子与列表 ==")
    psql(env, f"INSERT INTO orch_conversation (id, user_id, warehouse_code, title) "
              f"VALUES ('{CID}', 'portal_user', '9BZA', 'E2E 原始标题');")
    try:
        s, rows = req("GET", "/api/orchestrator/v1/conversation/list?limit=50", a["token"])
        ids = [r.get("conversation_id") for r in rows] if isinstance(rows, list) else []
        check("列表可见本人会话", s == 200 and CID in ids, f"HTTP {s}")

        print("== 重命名（PUT）==")
        s, d = req("PUT", f"/api/orchestrator/v1/conversation/{CID}",
                   a["token"], {"title": "E2E 改后的标题"})
        check("本人重命名 200", s == 200 and isinstance(d, dict) and d.get("ok"), f"HTTP {s} {d}")
        s, d = req("GET", f"/api/orchestrator/v1/conversation/{CID}", a["token"])
        check("详情标题已更新", s == 200 and d.get("title") == "E2E 改后的标题", f"HTTP {s} {str(d)[:120]}")

        print("== 越权（他人 token）==")
        s, d = req("PUT", f"/api/orchestrator/v1/conversation/{CID}",
                   b["token"], {"title": "越权改名"})
        check("他人改名 404", s == 404, f"HTTP {s} {d}")
        s, d = req("DELETE", f"/api/orchestrator/v1/conversation/{CID}", b["token"])
        check("他人删除 404", s == 404, f"HTTP {s} {d}")
        s, d = req("GET", f"/api/orchestrator/v1/conversation/{CID}", a["token"])
        check("越权后标题未被改动", s == 200 and d.get("title") == "E2E 改后的标题", f"HTTP {s}")

        print("== 删除（DELETE）==")
        s, d = req("DELETE", f"/api/orchestrator/v1/conversation/{CID}", a["token"])
        check("本人删除 200", s == 200 and isinstance(d, dict) and d.get("ok"), f"HTTP {s} {d}")
        s, d = req("GET", f"/api/orchestrator/v1/conversation/{CID}", a["token"])
        check("删除后详情 404", s == 404, f"HTTP {s}")
        cnt = psql(env, f"SELECT COUNT(*) FROM orch_message WHERE conversation_id='{CID}';")
        check("消息一并清掉", cnt == "0", f"残留 {cnt} 条")
    finally:
        cleanup(env)

    failed = [n for n, ok in RESULTS if not ok]
    print(f"\n结果：{len(RESULTS) - len(failed)}/{len(RESULTS)} 通过" + (f"；失败：{failed}" if failed else ""))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    main()
