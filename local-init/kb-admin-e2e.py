"""批 D 验收：知识库管理端到端（2026-10-06）。

覆盖三条真实链路，不跳过任何一层：
  1) 管理面 API（经网关 8090 → 内核 8126）：建库 → 上传文档（multipart）→ 文档列表 → 重建索引
  2) 检索命中（数字员工 KB 工具的真实下游）：AK/SK 签名 → 网关 → /oa/chat/sync，
     提问只有新上传文档能回答的问题，断言回答引用该文档内容
  3) 数字员工层：POST /agent/terminal_manager/chat（Bearer），断言回答包含文档关键信息

用法（本机全栈已启动）：
    python kb-admin-e2e.py            # 保留验收数据（管理台可见）
    python kb-admin-e2e.py --cleanup  # 跑完清理（连库带文档删掉）

失败返回非 0 退出码。管理台默认账号 admin/123456。
"""

import hashlib
import json
import secrets
import sys
import time
import uuid

import httpx

GW = "http://127.0.0.1:8090"      # 网关
KERNEL = "http://127.0.0.1:8126"  # 数字员工内核
AK, SK = "aismp-console", "7c1f9a3e5d0b2846af31c7e9b0d45268"       # 与内核 gateway 配置一致

DOC_NAME = "调拨单自动作废规则.txt"
DOC_CONTENT = """终端调拨单自动作废规则

调拨单创建后，若在每月 28 日 23 点前仍未完成出库，系统会自动将调拨单作废；
作废后的单据不可恢复，需要重新发起调拨申请。
自动作废前 3 天，系统会向发起人推送提醒消息，请及时处理积压的调拨单。
本规则适用于东宁市各分公司的库存调拨业务。
"""

# 关键信息只存在于上面这份文档里（问答断言用；宽松匹配数字组合，容忍模型措辞）
QUESTION = "调拨单一直没出库，会不会自动作废？"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(("  PASS " if ok else "  FAIL ") + name + ("  | " + detail if detail else ""))


def ascii_json(obj):
    """网关验签要求 header 只能是 ASCII：中文转成 \\uXXXX 转义（对端解析后内容不变）。"""
    return json.dumps(obj, ensure_ascii=True)


def signed_headers(body):
    """AK/SK 签名：sign = sha256_hex(body + '.' + secretKey)，与 sdk/sign.py 对齐。"""
    return {
        "accessKey": AK,
        "nonce": secrets.token_hex(16),
        "timestamp": str(int(time.time())),
        "sign": hashlib.sha256((body + "." + SK).encode("utf-8")).hexdigest(),
        "body": body,
        "Content-Type": "application/json",
    }


def main():
    cleanup = "--cleanup" in sys.argv
    client = httpx.Client(timeout=180)
    ts = time.strftime("%H%M%S")

    # ---------------------------------------------------------------
    print("[0] 登录（网关 → backend 签发 token）")
    # ---------------------------------------------------------------
    r = client.post(GW + "/api/auth/login",
                    json={"userCode": "admin", "password": "123456"})
    token = (r.json() or {}).get("token") if r.status_code == 200 else None
    check("admin 登录", bool(token), f"http={r.status_code}")
    if not token:
        return finish()
    auth = {"Authorization": f"Bearer {token}"}

    # ---------------------------------------------------------------
    print("[1] 建库 → 上传 → 列表 → 重建索引（管理面全链路）")
    # ---------------------------------------------------------------
    r = client.post(GW + "/api/admin/kb", headers=auth,
                    json={"name": f"验收知识库-{ts}", "description": "批 D 端到端验收（可删除）"})
    kb = r.json()
    kb_id, kb_uuid = kb.get("id"), kb.get("knowledgeBaseId")
    check("创建知识库", r.status_code == 200 and bool(kb_id), f"id={kb_id}")

    r = client.post(GW + f"/api/admin/kb/{kb_id}/documents", headers=auth,
                    files={"file": (DOC_NAME, DOC_CONTENT.encode("utf-8"), "text/plain")},
                    data={"category": "operation"})
    up = r.json()
    check("上传文档并向量化", up.get("status") == "ready" and (up.get("chunkCount") or 0) > 0,
          f"status={up.get('status')} chunkCount={up.get('chunkCount')} model={up.get('embeddingModel')}")

    r = client.get(GW + f"/api/admin/kb/{kb_id}/documents", headers=auth)
    docs = r.json() if r.status_code == 200 else []
    doc = next((d for d in docs if d.get("sourceName") == DOC_NAME), None)
    check("文档列表含新文档且 ready", bool(doc) and doc.get("status") == "ready",
          f"docs={len(docs)} chunk={doc.get('chunkCount') if doc else '-'}")

    r = client.post(GW + f"/api/admin/kb/{kb_id}/reindex", headers=auth)
    re_idx = r.json()
    check("重建索引走落盘源文件", re_idx.get("ok") == 1 and re_idx.get("total") == 1,
          json.dumps(re_idx, ensure_ascii=False)[:160])

    print(f"      KB id={kb_id} uuid={kb_uuid}")

    # ---------------------------------------------------------------
    print("[2] 检索命中（KB 工具真实下游：AK/SK → 网关 → /oa/chat/sync）")
    # ---------------------------------------------------------------
    body = ascii_json({"message": QUESTION, "conversationId": str(uuid.uuid4())})
    r = client.post(GW + "/oa/chat/sync", headers=signed_headers(body), content=body.encode("utf-8"))
    ans = r.text if r.status_code == 200 else ""
    # 严格断言：只有新文档提到「28 日 23 点」这个关键数字（用手册无法编出）；
    # 不能用「自动作废」这种词判命中 —— 否定回答（不会自动作废）里也会出现它
    hit = "28" in ans and "23" in ans
    check("RAG 检索命中新文档", hit, f"http={r.status_code} answer={ans[:120]}")

    # ---------------------------------------------------------------
    print("[3] 数字员工层（terminal_manager 挂 KB 工具 → 触发检索）")
    # ---------------------------------------------------------------
    r = client.post(KERNEL + "/agent/terminal_manager/chat", headers=auth,
                    json={"message": QUESTION, "conversationId": f"kb-e2e-{ts}"})
    ch = r.json()
    ans2 = ch.get("answer") or ""
    hit2 = "28" in ans2 and "23" in ans2
    check("数字员工回答引用新文档", hit2,
          f"answer={(ans2 or ch.get('error') or '')[:120]}")

    # ---------------------------------------------------------------
    if cleanup:
        print("[4] 清理验收数据")
        r = client.delete(GW + f"/api/admin/kb/{kb_id}/documents/{doc.get('documentId')}", headers=auth)
        check("删除文档", (r.json() or {}).get("deleted") == 1)
        r = client.delete(GW + f"/api/admin/kb/{kb_id}", headers=auth)
        check("删除知识库", (r.json() or {}).get("deleted") == 1)
    else:
        print("[4] 保留验收数据（管理台 5174 可查看；需要清理用 --cleanup）")

    return finish()


def finish():
    fails = [n for n, ok in RESULTS if not ok]
    print("=" * 60)
    print(f"批 D E2E：{len(RESULTS) - len(fails)}/{len(RESULTS)} 通过")
    if fails:
        print("失败项：" + "、".join(fails))
        return 1
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())