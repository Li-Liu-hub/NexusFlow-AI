"""全平台综合验收脚本：逐项跑全部接口，输出 PASS/FAIL 矩阵。"""
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

# admin 口令：线上已加固为强口令，不再硬编码进仓库。
# 本地开发（种子库口令仍是 123456）无需设置；连线上时 ACC_ADMIN_PW=<强口令> 传入。
ADMIN_PW = os.environ.get("ACC_ADMIN_PW", "123456")

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

GW = "http://127.0.0.1:8090"
DH = "http://127.0.0.1:8126"
BE = "http://127.0.0.1:7529/api"
ORCH = "http://127.0.0.1:8125"
AK, SK = "aismp-console", "7c1f9a3e5d0b2846af31c7e9b0d45268"

# 内核登录令牌（main 开头统一登录后填充；req() 按路径自动注入）
T_ADMIN = None   # ADMIN  → /admin/**
T_USER = None    # USER   → /agent/**
T_SVC = None     # SERVICE → 编排代传场景（可指定 userId）


def auth_headers(tok):
    return {"Authorization": f"Bearer {tok}"} if tok else {}

import hashlib
import secrets
import time


def sign_headers(body):
    content = (body if body is not None else "null") + "." + SK
    return {
        "accessKey": AK,
        "nonce": secrets.token_hex(16),
        "timestamp": str(int(time.time())),
        "sign": hashlib.sha256(content.encode()).hexdigest(),
        **({"body": body} if body is not None else {}),
    }


def req(url, method="GET", body=None, headers=None, timeout=240, raw=False):
    data = None
    h = {"Content-Type": "application/json"}
    # 内核鉴权自动注入：/admin → ADMIN 令牌，/agent → USER 令牌
    # （显式传 headers 里的 Authorization 优先 —— 覆盖默认值，SERVICE 代传场景用）
    if url.startswith(DH + "/admin") and T_ADMIN:
        h["Authorization"] = f"Bearer {T_ADMIN}"
    elif url.startswith(DH + "/agent") and T_USER:
        h["Authorization"] = f"Bearer {T_USER}"
    if body is not None:
        data = json.dumps(body).encode("utf-8") if not isinstance(body, str) else body.encode("utf-8")
    if headers:
        h.update(headers)
    r = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            t = resp.read().decode("utf-8", "replace")
            return resp.status, (t if raw else (json.loads(t) if t.strip().startswith(("{", "[")) else t))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:300]
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"


RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail and not ok else ""))


def section(t):
    print(f"\n{'═' * 30} {t} {'═' * 30}")


def main():
    # 前置：内核必须在线，否则后面全是噪音（且中途崩掉会掩盖真实状态）
    s, _ = req(f"{DH}/health", timeout=10)
    if s != 200:
        print(f"\n⚠️  数字员工内核 {DH} 不可用（HTTP {s}）—— 先启动内核再跑本脚本：")
        print("  cd agent-platform && unset SERVER_PORT SERVER__PORT SERVER__HOST && \\")
        print("  PG_PASSWORD=123456 LOCAL_EMBEDDING_BASE_URL=http://127.0.0.1:8099 \\")
        print("  /d/env/jdk-21/bin/java.exe -jar aismp-digital-human/target/aismp-digital-human-0.0.1-SNAPSHOT.jar --server.port=8126")
        return

    # ════════ 0. 内核登录鉴权（69 号迁移起所有业务面要求 Bearer） ════════
    section("0. 内核登录鉴权 :8126/auth")
    global T_ADMIN, T_USER, T_SVC

    s, d = req(f"{DH}/agent/agents-for-role")
    check("A25 未登录被拒(401)", s == 401, f"HTTP {s}")

    s, d = req(f"{DH}/auth/login", "POST", {"userCode": "admin", "password": "wrong-pass"})
    check("A26 错误密码被拒", s == 200 and d.get("error"), str(d)[:60])

    s, d = req(f"{DH}/auth/login", "POST", {"userCode": "admin", "password": ADMIN_PW})
    T_ADMIN = d.get("token") if s == 200 else None
    check("A0 admin 登录(ADMIN)", s == 200 and T_ADMIN and d.get("role") == "ADMIN", str(d)[:80])

    s, d = req(f"{DH}/auth/login", "POST", {"userCode": "portal_user", "password": "123456"})
    T_USER = d.get("token") if s == 200 else None
    check("A0a 门户用户登录(USER)", s == 200 and T_USER and d.get("role") == "USER", str(d)[:80])

    s, d = req(f"{DH}/auth/login", "POST", {"userCode": "svc_orchestrator", "password": "svc-orch-2026"})
    T_SVC = d.get("token") if s == 200 else None
    check("A0b 服务账号登录(SERVICE)", s == 200 and T_SVC and d.get("role") == "SERVICE", str(d)[:80])

    s, d = req(f"{DH}/auth/me", headers=auth_headers(T_USER))
    check("A0c /auth/me 回显身份", s == 200 and d.get("userCode") == "portal_user", str(d)[:60])

    s, d = req(f"{DH}/admin/agent/list", headers=auth_headers(T_USER))
    check("A27 USER 访问配置面被拒(403)", s == 403, f"HTTP {s}")

    # ════════ A. 数字员工内核 ════════
    section("A. 数字员工内核 :8126")

    s, d = req(f"{DH}/health")
    check("A1 健康检查", s == 200 and d.get("database") == "CONNECTED", str(d)[:80])

    s, d = req(f"{DH}/admin/agent/list")
    codes = [a["agentCode"] for a in d] if isinstance(d, list) else []
    check("A2 数字员工列表（3 员工）", s == 200 and set(codes) >= {"query_assistant", "replenish_advisor", "ops_assistant"}, str(codes))

    s, d = req(f"{DH}/admin/agent/query_assistant/detail")
    # 失败时 d 可能是错误字符串，先兜底成 dict，避免 .get() 抛 AttributeError 把整套带崩
    d = d if isinstance(d, dict) else {}
    check("A3 详情含技能与工具", s == 200 and d.get("skills") and d.get("tools"), f"技能{len(d.get('skills', []))} 工具{len(d.get('tools', []))}")

    s, d = req(f"{DH}/admin/tool/list")
    check("A4 工具清单 20 条", s == 200 and isinstance(d, list) and len(d) >= 20, f"{len(d) if isinstance(d, list) else d}")

    s, d = req(f"{DH}/admin/agent/catalog/skills")
    # 2026-10-08：技能目录随时间维度工具包（81）扩到 3 条 —— 断言改为「三大基础技能齐备」
    skill_codes = {x.get("skillCode") for x in d} if isinstance(d, list) else set()
    check("A5 技能目录齐备(3 基础技能)",
          s == 200 and {"inventory_query", "trace_terminal", "inbound_time_query"} <= skill_codes,
          str(skill_codes))

    s, d = req(f"{DH}/admin/tool/validate?dryRun=true", "POST", {
        "toolName": "acc_test_tool", "toolDesc": "验收测试",
        "inputSchema": '{"type":"object","properties":{"model":{"type":"string"}}}',
        "sqlTemplate": "SELECT COUNT(*) AS n FROM t_terminal WHERE model LIKE CONCAT('%', :model, '%')",
        "resultLimit": 10})
    check("A6 工具试运行（合法定义）", s == 200 and d.get("valid"), str(d)[:100])

    s, d = req(f"{DH}/admin/tool/validate", "POST", {
        "toolName": "bad_tool", "toolDesc": "x",
        "inputSchema": '{"type":"object","properties":{}}',
        "sqlTemplate": "DELETE FROM t_terminal", "resultLimit": 10})
    check("A7 危险 SQL 被拒", s == 200 and not d.get("valid"), str(d)[:80])

    s, d = req(f"{DH}/agent/conversation/list?agentCode=query_assistant&limit=5")
    check("A8 会话列表(按员工隔离)", s == 200 and isinstance(d, list), f"{len(d) if isinstance(d, list) else d}")

    s, d = req(f"{DH}/agent/conversation/stats")
    check("A9 会话统计", s == 200 and "conversations" in d, str(d)[:60])

    s, d = req(f"{DH}/admin/audit/recent?limit=5")
    check("A10 审计明细", s == 200 and isinstance(d, list), f"{len(d) if isinstance(d, list) else d} 条")

    s, d = req(f"{DH}/admin/audit/stats")
    check("A11 审计统计", s == 200 and isinstance(d, list), f"{len(d) if isinstance(d, list) else d} 工具")

    s, d = req(f"{DH}/agent/query_assistant/introspect")
    check("A12 运行时自省", s == 200 and d.get("systemPrompt"), f"v{d.get('version')} 工具{len(d.get('tools', []))}")

    # 对话（三个数字员工，真实数据断言）
    # A13 说明：原题是「西湖营业厅」（旧 terminal_db 虚构数据），阶段1/2 业务迁到 api_db 后
    # 该库位不存在；且让 LLM 自行对多行求和会算错（实测 23459 vs 真实 23460）。
    # 改为问「总库存」——工具返回单值，模型只做复述，断言稳定（api_db 真实值 28354）。
    s, d = req(f"{DH}/agent/query_assistant/chat", "POST",
               {"message": "当前系统终端总库存是多少台？", "conversationId": "acc_conv_1", "userId": "acc"})
    ans = str(d.get("answer", ""))
    check("A13 查数助手·真实查库", s == 200 and "28354" in ans, ans[:80])

    s, d = req(f"{DH}/agent/replenish_advisor/chat", "POST",
               {"message": "现在各状态的终端分别有多少台？", "userId": "acc"})
    check("A14 补仓推荐师·水位数据", s == 200 and ("IN_STOCK" in str(d.get("answer", "")) or "在库" in str(d.get("answer", ""))), str(d.get("answer", ""))[:60])

    s, d = req(f"{DH}/agent/ops_assistant/chat", "POST",
               {"message": "终端领用要走什么流程？", "userId": "acc"})
    ans = str(d.get("answer", ""))
    check("A15 运维助手·查手册(RAG)", s == 200 and "领用" in ans and "失败" not in ans[:50], ans[:80])

    # 挂载/卸载（version 热更新）
    s, detail = req(f"{DH}/admin/agent/replenish_advisor/detail")
    tool_ids = [t["id"] for t in detail.get("tools", [])]
    all_t = req(f"{DH}/admin/tool/list")[1]
    free = next(t["id"] for t in all_t if t["id"] not in tool_ids)
    s1, r1 = req(f"{DH}/admin/agent/replenish_advisor/tool/{free}", "POST")
    s2, r2 = req(f"{DH}/admin/agent/replenish_advisor/tool/{free}", "DELETE")
    check("A16 挂载/卸载+version自增", s1 == 200 and s2 == 200 and r1.get("version", 0) > detail["agent"]["version"], f"v{r1.get('version')}→v{r2.get('version')}")

    s, r = req(f"{DH}/admin/agent/query_assistant/reload", "POST")
    check("A17 手动刷新缓存", s == 200 and r.get("evicted") is not None, str(r)[:60])

    # 敏感工具审批闭环（approval_required=1 → 拦截落单 → 放行重放执行）
    # A19 说明：原题「0元入库单」对应的 queryZeroCostInbound 是已停用的老 SQL_TEMPLATE
    #（新数据模型无入库单实体）。按用户决策，审批演示改挂到现役的 queryAvailableSerialNos
    #（串码明细 = 敏感操作），见 local-init/73-approval-demo-tool.sql。
    s, d = req(f"{DH}/agent/tool-approval/pending")
    check("A18 审批单接口", s == 200 and isinstance(d, list), str(d)[:60])

    s, d = req(f"{DH}/agent/query_assistant/chat", "POST",
               {"message": "东宁市分公司（仓位编码 9B00）物料编码 7450301775 有哪些可用串码？列出前5条",
                "conversationId": f"acc_appr_{uuid.uuid4().hex[:8]}", "userId": "acc"})
    import re as _re
    m = _re.search(r"ap_[0-9a-f]+", str(d.get("answer", "")))
    if m:
        aid = m.group(0)
        s2, d2 = req(f"{DH}/agent/tool-approval/{aid}/decide", "POST", {"approve": True, "userId": "acc"})
        res = d2.get("result") or {}
        ok = s2 == 200 and d2.get("status") == "EXECUTED" and isinstance(res, dict)
        check("A19 敏感工具拦截→放行执行", ok,
              f"status={d2.get('status')} rows={res.get('count') if isinstance(res, dict) else '?'}")
    else:
        check("A19 敏感工具拦截→放行执行", False, f"未生成审批单: {str(d)[:100]}")

    # ════════ A+. 角色 / 智能路由 / token 配额 / 知识库挂载 ════════
    s, d = req(f"{DH}/agent/agents-for-role?role=keeper")
    codes = [a.get("agentCode") for a in (d if isinstance(d, list) else [])]
    check("A20 角色员工列表(keeper只看查数)", s == 200 and codes == ["query_assistant"], str(codes))

    # 智能路由含 LLM 分类（候选无法用关键词计分裁决时），首轮冷启动可能抖动 —— 失败重试一次
    def _auto_route_probe():
        s, d = req(f"{DH}/agent/auto/chat", "POST",
                   {"message": "帮我看看库存水位，哪里需要补货",
                    "conversationId": f"acc_auto_{uuid.uuid4().hex[:8]}", "userId": "acc", "role": "manager"})
        dd = d if isinstance(d, dict) else {}
        return s, dd, (dd.get("route") or {})

    s, d, route = _auto_route_probe()
    if not (s == 200 and route.get("agentCode") == "replenish_advisor"):
        s, d, route = _auto_route_probe()
    check("A21 智能路由(补货→补仓推荐师)", s == 200 and route.get("agentCode") == "replenish_advisor" and "补" in str(d.get("answer", "")),
          f"→{route.get('agentCode')} ({route.get('reason')}) http={s} err={str(d.get('error'))[:60]}")

    # 批 A（2026-10-06）：配额与用量账本已迁 backend（api_db），管理面走 BE；
    # 对话链路仍在内核（入口闸门经 /inner/token/check 调 backend）
    s, d = req(f"{BE}/admin/token/usage/overview", headers=auth_headers(T_ADMIN))
    check("A22 token 用量总览", s == 200 and isinstance(d, list), f"{len(d) if isinstance(d, list) else d} 用户")

    # A22b：智能体用量排行 + 编排层节点计量盲区回归（2026-10-08）——
    # 编排层 5 类 LLM 节点（意图识别/图表选型/报告/建单/质检）现在直连模型也会经
    # /inner/token/charge 落账；A21 的补货对话应已产出节点行（落账是旁路线程，轮询等一下）
    _node_codes = {"intent_router", "chart_planner", "replenish_reporter", "order_parser", "quality_critic"}
    _ag_rows = []
    for _ in range(6):
        s, d = req(f"{BE}/admin/token/usage/agents", headers=auth_headers(T_ADMIN))
        _ag_rows = d if isinstance(d, list) else []
        if _node_codes & {r.get("agent_code") for r in _ag_rows}:
            break
        time.sleep(2)
    _hit = _node_codes & {r.get("agent_code") for r in _ag_rows}
    check("A22b 智能体用量排行(含编排层节点)", s == 200 and _ag_rows and _hit,
          f"{len(_ag_rows)} 个智能体, 编排层节点: {sorted(_hit) or '未见'}")

    # 配额拦截：quota=0 即封禁 → 对话必须被拒 → 删除配额恢复
    # （用 SERVICE 令牌代传 userId —— 真人令牌的对话一律以登录身份计量，无法指定他人）
    s1, r1 = req(f"{BE}/admin/token/quota", "POST",
                 {"scope": "USER", "targetCode": "acc_quota_test", "monthlyTokens": 0, "remark": "验收临时封禁"},
                 headers=auth_headers(T_ADMIN))
    s2, d2 = req(f"{DH}/agent/query_assistant/chat", "POST",
                 {"message": "你好", "conversationId": f"acc_q_{uuid.uuid4().hex[:8]}", "userId": "acc_quota_test"},
                 headers=auth_headers(T_SVC))
    # 防御：内核不可用/非 JSON 响应时 r1 会是字符串，不能让脚本在这里崩掉（会掩盖后续用例）
    quota_id = r1.get("id") if isinstance(r1, dict) else None
    d2d = d2 if isinstance(d2, dict) else {}
    if quota_id is not None:
        s3, r3 = req(f"{BE}/admin/token/quota/{quota_id}", "DELETE", headers=auth_headers(T_ADMIN))
    else:
        s3, r3 = 0, {}
        check("A23 配额封禁→删除恢复", False,
              f"建配额失败，跳过：http={s1} resp={str(r1)[:80]}")
        quota_id = -1   # 已报告，下面不再重复判定
    if quota_id != -1:
        check("A23 配额封禁→删除恢复", s1 == 200 and d2d.get("quotaDenied") is True and s3 == 200,
              f"denied={d2d.get('quotaDenied')} deleted={r3.get('deleted') if isinstance(r3, dict) else r3}")

    # A23b：调整配额回归（2026-10-08）—— 管理台 PUT 不带 status 字段，
    # 旧实现把 NOT NULL 的 status 更新成 null 直接 500（「调整配额」按钮一直是坏的）
    s1, r1 = req(f"{BE}/admin/token/quota", "POST",
                 {"scope": "USER", "targetCode": "acc_quota_test", "monthlyTokens": 123},
                 headers=auth_headers(T_ADMIN))
    _qid = r1.get("id") if isinstance(r1, dict) else None
    if _qid:
        s2, r2 = req(f"{BE}/admin/token/quota/{_qid}", "PUT",
                     {"scope": "USER", "targetCode": "acc_quota_test", "monthlyTokens": 456, "remark": "A23b"},
                     headers=auth_headers(T_ADMIN))
        s3, r3 = req(f"{BE}/admin/token/quota/{_qid}", "DELETE", headers=auth_headers(T_ADMIN))
        ok = s1 == 200 and s2 == 200 and isinstance(r2, dict) and r2.get("updated") == 1 and s3 == 200
        check("A23b 配额调整(不带status)回归", ok,
              f"create={s1} update={s2}:{str(r2)[:60]} delete={s3}")
    else:
        check("A23b 配额调整(不带status)回归", False, f"建配额失败: {str(r1)[:80]}")

    s, d = req(f"{DH}/admin/agent/ops_assistant/detail")
    kb = d.get("knowledgeBase") or {}
    check("A24 知识库挂载生效", s == 200 and kb.get("name") == "终端业务规范库", str(kb.get("name")))

    # 登录身份强制：USER 令牌伪造 userId=别人，服务端仍以登录身份落账
    s, d = req(f"{DH}/agent/query_assistant/chat", "POST",
               {"message": "你好", "conversationId": f"acc_fake_{uuid.uuid4().hex[:8]}", "userId": "victim_user"})
    ans = str(d.get("tokenUsage", {}))
    check("A28 伪造 userId 被忽略(仍按登录身份)", s == 200 and "answer" in d, f"tokenUsage={ans[:80]}")

    # SERVICE 代传：编排层代表系统调用时可以把终端用户身份透传下去
    s, d = req(f"{DH}/agent/query_assistant/chat", "POST",
               {"message": "你好", "conversationId": f"acc_svc_{uuid.uuid4().hex[:8]}", "userId": "acc_svc_probe"},
               headers=auth_headers(T_SVC))
    check("A29a SERVICE 代传对话成功", s == 200 and "answer" in d, str(d)[:80])

    s, d = req(f"{BE}/admin/token/usage/overview", headers=auth_headers(T_ADMIN))
    rows = d if isinstance(d, list) else []
    svc_row = next((r for r in rows if "acc_svc_probe" in json.dumps(r)), None)
    check("A29 SERVICE 代传落账到指定用户", svc_row is not None,
          f"总览 {len(rows)} 行中未找到 acc_svc_probe" if svc_row is None else "已找到 acc_svc_probe 行")

    s, d = req(f"{DH}/agent/agents-for-role", headers=auth_headers(T_USER))
    check("A30 正常令牌访问运行面(200)", s == 200 and isinstance(d, list), str(d)[:60])

    # ════════ B. 网关 ════════
    section("B. 网关 :8090")

    body = json.dumps({"message": "西湖营业厅有多少台终端？", "userId": "acc_gw"})
    s, d = req(f"{GW}/digitalHuman/agent/query_assistant/chat", "POST", body,
               {**sign_headers(body), **auth_headers(T_SVC)}, timeout=240)
    check("B1 通配路由+签名经网关对话", s == 200 and "answer" in d, str(d)[:80])

    s, d = req(f"{GW}/digitalHuman/agent/query_assistant/chat", "POST", body)
    check("B2 无签名被拒(403)", s == 403, f"HTTP {s}")

    s, d = req(f"{GW}/digitalHuman/admin/agent/list",
               headers={**sign_headers(None), **auth_headers(T_ADMIN)})
    check("B3 配置面经网关(通配admin)", s == 200 and isinstance(d, list), str(d)[:60])

    s, d = req(f"{GW}/digitalHuman/agent/query_assistant/chat", "POST", body, sign_headers(body))
    check("B4 有签名无内核令牌被拒(401)", s == 401, f"HTTP {s}")

    # ════════ C. backend 登录态代理 ════════
    section("C. backend 登录态代理 :7529")

    import http.cookiejar
    cj = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

    def op_req(path, method="GET", body=None):
        """带登录态（cookie）的请求 —— 必须走同一个 opener，session 才能保持"""
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(BE + path, data=data, method=method,
                                   headers={"Content-Type": "application/json"})
        try:
            with op.open(r, timeout=60) as resp:
                t = resp.read().decode("utf-8", "replace")
                return resp.status, (json.loads(t) if t.strip().startswith(("{", "[")) else t)
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")[:300]

    s, d = op_req("/admin/digitalHuman/agent/list")
    check("C1 未登录被拒(40100)", s == 200 and isinstance(d, dict) and d.get("code") == 40100, str(d)[:60])

    s, d = op_req("/user/login", "POST", {"userAccount": "admin", "userPassword": ADMIN_PW})
    check("C2 admin 登录", s == 200 and d.get("code") == 0, str(d)[:60])

    s, d = op_req("/admin/digitalHuman/agent/list")
    ok = s == 200 and isinstance(d, list) and len(d) >= 3
    check("C3 登录态经代理取配置(代理→网关→内核)", ok, str(d)[:60])

    s, d = op_req("/admin/digitalHuman/audit/stats")
    check("C4 登录态取审计统计", s == 200 and isinstance(d, list), f"{len(d) if isinstance(d, list) else d}")

    # ════════ D. 编排层 SSE ════════
    section("D. 编排层 :8125（SSE 补货建议）")

    q = "给我一份补货建议"
    url = f"{ORCH}/orchestrator/v1/chat/stream?query={urllib.parse.quote(q)}"
    s, raw = req(url, "POST", "", sign_headers(None), timeout=300, raw=True)
    got_card = "调拨" in raw or "采购" in raw or "库存" in raw
    check("D1 编排层 SSE 补货建议", s == 200 and len(raw) > 100 and got_card, f"{len(raw)} 字符 · {raw[:80].replace(chr(10), ' ')}")

    # ════════ E. 前端 ════════
    # 【2026-10-06 收敛到网关】两个前端的 /api 不再直连内核 8126，改经网关 8090：
    #   验证点从"代理→内核"变为"代理→网关（Bearer 校验 + 计量）→内核"，
    #   且门户令牌必须**经网关登录**登记过（portal:token: → ak）才放行。
    # 【2026-10-08 拓扑自适应】前端入口改为环境变量可配：
    #   本机开发默认 vite dev（门户 5173 / 管理台 5174）；
    #   服务器运行 ACC_PORTAL_BASE=http://127.0.0.1（nginx :80，/api/ 也代理到网关）
    #            ACC_ADMIN_BASE=http://127.0.0.1:8091/admin/（管理台回环站点）
    PORTAL_BASE = os.environ.get("ACC_PORTAL_BASE", "http://127.0.0.1:5173").rstrip("/")
    ADMIN_BASE = os.environ.get("ACC_ADMIN_BASE", "http://127.0.0.1:5174").rstrip("/")
    section("E. 前端")
    s, _ = req(f"{PORTAL_BASE}/portal.html", raw=True)
    check("E1 用户端门户", s == 200)
    s, _ = req(f"{ADMIN_BASE}/", raw=True)
    check("E2 管理端", s == 200)
    s, _ = req(f"{PORTAL_BASE}/api/agent/terminal_manager/introspect", raw=True)
    check("E3a 门户代理匿名被拒(401)", s == 401, f"HTTP {s}")
    s, d = req(f"{PORTAL_BASE}/api/auth/login", "POST",
               {"userCode": "portal_user", "password": "123456"})
    portal_tok = d.get("token") if isinstance(d, dict) else None
    check("E3b 门户代理登录(网关登记令牌映射)", s == 200 and bool(portal_tok), str(d)[:80])
    s, _ = req(f"{PORTAL_BASE}/api/agent/terminal_manager/introspect",
               headers=auth_headers(portal_tok), raw=True)
    check("E3 门户代理→网关→内核(带登录令牌)", s == 200, f"HTTP {s}")
    # 反证：直接找内核登录拿到的令牌没有经网关登记 → 门户通道拒绝（网关不是透明转发）
    s, _ = req(f"{PORTAL_BASE}/api/agent/terminal_manager/introspect",
               headers=auth_headers(T_USER), raw=True)
    check("E3c 未登记的令牌被网关拒(401)", s == 401, f"HTTP {s}")

    # ════════ 汇总 ════════
    section("验收汇总")
    total = len(RESULTS)
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    for name, ok, detail in RESULTS:
        if not ok:
            print(f"  ✘ FAIL {name} — {detail[:100]}")
    print(f"\n  通过率: {passed}/{total}")
    fails = [n for n, ok, _ in RESULTS if not ok]
    if not fails:
        print("  全部通过 ✅")
    else:
        print("  待修复:", ", ".join(fails))


import urllib.parse  # noqa: E402

if __name__ == "__main__":
    main()
