"""批 E 验收：MCP 管理端到端（2026-10-06）。

链路（全部真实入口，不跳过任何一层）：
  1) 注册 MCP 服务（平台自身 aismp-agent /sse）→ 探测远端工具清单
  2) 用探测结果一键生成工具定义（a_llm_tool，tool_type=MCP，config_json 引用 serverCode+toolName）
  3) 建测试数字员工 → 挂载 MCP 工具 → 对话提问 → 断言回答经由 MCP 调用命中知识库（28 日 23 点）
  4) 删除保护校验：被工具引用的 MCP 服务不可删
  5) 清理测试数字员工（保留 MCP 服务与生成的工具，管理台可见）

用法（本机全栈已启动）：
    python mcp-admin-e2e.py

失败返回非 0 退出码。
"""

import json
import sys
import time

import httpx

GW = "http://127.0.0.1:8090"      # 网关
KERNEL = "http://127.0.0.1:8126"  # 数字员工内核

SERVER_CODE = "aismp-agent-mcp"
TEST_AGENT = "mcp_e2e_agent"
TOOL_NAME = "searchKnowledgeBase"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(("  PASS " if ok else "  FAIL ") + name + ("  | " + detail if detail else ""))


def main():
    client = httpx.Client(timeout=240)
    ts = time.strftime("%H%M%S")

    print("[0] 登录")
    r = client.post(GW + "/api/auth/login", json={"userCode": "admin", "password": "123456"})
    token = (r.json() or {}).get("token") if r.status_code == 200 else None
    check("admin 登录", bool(token), f"http={r.status_code}")
    if not token:
        return finish()
    auth = {"Authorization": f"Bearer {token}"}

    # ---------------------------------------------------------------
    print("[1] 注册 MCP 服务 + 探测工具")
    # ---------------------------------------------------------------
    r = client.get(GW + "/api/admin/mcp/list", headers=auth)
    existing = next((s for s in (r.json() or []) if s.get("serverCode") == SERVER_CODE), None)
    if existing:
        server_id = existing["id"]
        print(f"      （已注册，复用 id={server_id}）")
    else:
        r = client.post(GW + "/api/admin/mcp", headers=auth, json={
            "serverCode": SERVER_CODE,
            "serverName": "平台自身 MCP server",
            "transport": "SSE",
            "baseUrl": "http://127.0.0.1:8124",
        })
        server_id = (r.json() or {}).get("id")
        check("注册 MCP 服务", bool(server_id), f"http={r.status_code} body={r.text[:120]}")
        if not server_id:
            return finish()

    r = client.post(GW + f"/api/admin/mcp/{server_id}/probe", headers=auth)
    probe = r.json() or {}
    tools = probe.get("tools") or []
    names = [t.get("name") for t in tools]
    check("探测远端工具清单", probe.get("ok") is True and len(tools) >= 3,
          f"ok={probe.get('ok')} tools={names} server={probe.get('serverName')}")
    remote_tool = next((t for t in tools if t.get("name") == TOOL_NAME), None)
    check(f"清单含 {TOOL_NAME}", bool(remote_tool),
          f"inputSchema={(remote_tool or {}).get('inputSchema', '')[:80]}")

    # ---------------------------------------------------------------
    print("[2] 一键生成工具定义（MCP 类型）")
    # ---------------------------------------------------------------
    r = client.get(GW + "/api/admin/tool/list", headers=auth)
    tool_id = next((t.get("id") for t in (r.json() or [])
                    if t.get("toolName") == TOOL_NAME and t.get("toolType") == "MCP"), None)
    if tool_id:
        print(f"      （工具已存在，复用 id={tool_id}）")
        check("生成工具定义（复用已有）", True)
    else:
        r = client.post(GW + "/api/admin/tool?dryRun=true", headers=auth, json={
            "toolName": TOOL_NAME,
            "toolDesc": remote_tool.get("description") or "MCP 知识库检索",
            "toolType": "MCP",
            "inputSchema": remote_tool.get("inputSchema"),
            "configJson": json.dumps({"serverCode": SERVER_CODE, "toolName": TOOL_NAME}),
            "resultLimit": 10,
            "status": "active",
            "remark": f"由 MCP 服务 {SERVER_CODE} 探测生成",
        })
        body = r.json() or {}
        tool_id = body.get("id")
        check("生成工具定义", bool(body.get("created")) and bool(tool_id),
              f"created={body.get('created')} msg={str(body.get('message') or body.get('check'))[:120]}")
        if not tool_id:
            return finish()

    # ---------------------------------------------------------------
    print("[3] 测试数字员工：挂 MCP 工具 → 对话调用")
    # ---------------------------------------------------------------
    r = client.get(GW + f"/api/admin/agent/{TEST_AGENT}", headers=auth)
    if r.status_code != 200 or not r.text or r.text == "null":
        r = client.post(GW + "/api/admin/agent", headers=auth, json={
            "agentCode": TEST_AGENT,
            "agentName": "MCP 验收助手",
            "description": "批 E 验收用（测完删除）",
            "systemPrompt": "你是测试助手。回答业务规则类问题前，必须先调用 searchKnowledgeBase 工具"
                            "检索知识库，并严格依据检索结果回答；检索不到就如实说明。",
            "modelName": "mimo-v2.6-flash",
            "temperature": 0.3,
            "maxTurns": 6,
            "status": "active",
        })
        check("创建测试数字员工", bool((r.json() or {}).get("id")), f"http={r.status_code}")

    r = client.post(GW + f"/api/admin/agent/{TEST_AGENT}/tool/{tool_id}", headers=auth)
    rat = (r.json() or {}).get("attached")
    check("挂载 MCP 工具", rat is True, f"version={(r.json() or {}).get('version')}")

    question = "调拨单自动作废规则：多久没出库会自动作废？"
    r = client.post(KERNEL + f"/agent/{TEST_AGENT}/chat", headers=auth,
                    json={"message": question, "conversationId": f"mcp-e2e-{ts}"})
    ch = r.json() or {}
    ans = ch.get("answer") or ""
    hit = "28" in ans and "23" in ans
    check("对话经 MCP 命中知识库", hit,
          f"answer={(ans or ch.get('error') or '')[:150]}")

    # ---------------------------------------------------------------
    print("[4] 删除保护：被工具引用的 MCP 服务不可删")
    # ---------------------------------------------------------------
    r = client.delete(GW + f"/api/admin/mcp/{server_id}", headers=auth)
    body = r.json() or {}
    check("删除保护生效", "error" in body and "引用" in str(body.get("error")),
          str(body)[:140])

    # ---------------------------------------------------------------
    print("[5] 清理测试数字员工（保留 MCP 服务与工具供管理台演示）")
    # ---------------------------------------------------------------
    r = client.delete(GW + f"/api/admin/agent/{TEST_AGENT}", headers=auth)
    check("删除测试数字员工", (r.json() or {}).get("deleted") == 1, r.text[:100])

    return finish()


def finish():
    fails = [n for n, ok in RESULTS if not ok]
    print("=" * 60)
    print(f"批 E E2E：{len(RESULTS) - len(fails)}/{len(RESULTS)} 通过")
    if fails:
        print("失败项：" + "、".join(fails))
        return 1
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())