# -*- coding: utf-8 -*-
"""知识库「切片参数」E2E（2026-10-07，轻量版：切片可配 + 嵌入模型只读）

覆盖四条链路（全部经网关，管理面 Bearer）：
  1) GET  /admin/kb/settings   入库参数（嵌入模型只读 + 切片默认值/区间）
  2) POST /admin/kb            建库带 chunkSize；非法值被拒（50 / 99999）
  3) 上传文档                  按库切片参数入库，chunkCount 反映切片大小
  4) PUT + reindex             改切片参数 → 重建索引按新参数重切；
                              不含 chunkSize 的更新不改变原值
旁证：调整切片参数后检索仍能命中文档关键信息（/oa/chat/sync）。

用法：python kb-chunk-e2e.py
失败返回非 0 退出码。验收数据保留（管理台可见）。
"""
import hashlib
import io
import json
import os
import secrets
import sys
import time
import uuid

import httpx

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

# 网关地址：默认本机；远端验证用 GW=http://49.234.49.192（经 nginx 80 端口）
GW = os.environ.get("GW", "http://127.0.0.1:8090")
AK, SK = "aismp-console", "7c1f9a3e5d0b2846af31c7e9b0d45268"

DOC_NAME = "利旧复投与折旧规则（切片验收）.txt"
DOC_CONTENT = """终端利旧复投与折旧规则

一、回收折旧率
终端回收后按库龄分档计提折旧：库龄 0 到 6 个月的折旧率为 3.5 个点，
7 到 12 个月为 8 个点，13 个月以上为 15 个点。折旧结果只用于台账展示，
不改变终端串码的实际状态。回收当月不计折旧，从次月开始计提。

二、利旧复投检查
折旧后仍具备使用价值的终端，可进入利旧池等待复投。利旧终端复投前
必须完成三项检查：外观检查、通电自检、串码核验。三项检查全部通过后，
由仓管在系统中把该终端状态改为可用，并登记复投去向。

三、超期未复投处理
利旧池中的终端如果连续 180 天未被复投，系统会在每月 5 日自动生成
退库建议清单，提交终端主管确认。主管确认后转为报废流程处理，
报废流程需再次核验串码并留存影像资料。

四、与其他规则的边界
本规则仅适用于东宁市各分公司的自有产权终端；租赁终端不适用本规则，
按租赁合同单独处理。折旧率如遇集团口径调整，以最新通知为准。
复投检查不通过的终端，退回利旧池并标记原因，等待下次复投窗口。
"""

QUESTION = "利旧池里的终端多久没复投会自动生成退库建议？"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail and not ok else ""))


def signed_headers(body):
    return {
        "accessKey": AK,
        "nonce": secrets.token_hex(16),
        "timestamp": str(int(time.time())),
        "sign": hashlib.sha256((body + "." + SK).encode("utf-8")).hexdigest(),
        # body 已是 JSON 字符串：不能再 json.dumps 一次（会二次编码导致签名不匹配 → 403）
        "body": body,
        "Content-Type": "application/json",
    }


def main():
    client = httpx.Client(timeout=180)
    ts = time.strftime("%H%M%S")

    print("[0] 登录（网关 → backend 签发 token）")
    r = client.post(GW + "/api/auth/login", json={"userCode": "admin", "password": "123456"})
    token = (r.json() or {}).get("token") if r.status_code == 200 else None
    check("admin 登录", bool(token), f"http={r.status_code}")
    if not token:
        return finish()
    auth = {"Authorization": f"Bearer {token}"}

    print("\n[1] 入库参数（嵌入模型只读 + 切片默认值/区间）")
    r = client.get(GW + "/api/admin/kb/settings", headers=auth)
    st = r.json() if r.status_code == 200 else {}
    check("settings 返回嵌入模型与切片区间",
          r.status_code == 200 and st.get("embeddingModel") and st.get("defaultChunkSize") == 800
          and st.get("minChunkSize") == 100 and st.get("maxChunkSize") == 4000,
          f"http={r.status_code} {st}")

    print("\n[2] 建库：自定义切片参数 + 非法值拦截")
    r = client.post(GW + "/api/admin/kb", headers=auth,
                    json={"name": f"切片参数验收-{ts}", "description": "切片参数 E2E（可删除）",
                          "chunkSize": 300})
    kb = r.json()
    kb_id = kb.get("id")
    check("建库带 chunkSize=300", r.status_code == 200 and kb.get("chunkSize") == 300,
          f"http={r.status_code} {kb}")
    if not kb_id:
        return finish()

    r = client.get(GW + "/api/admin/kb/list", headers=auth)
    row = next((x for x in (r.json() or []) if x.get("id") == kb_id), None)
    check("列表回显 chunk_size=300", bool(row) and row.get("chunk_size") == 300,
          str(row)[:160])

    r = client.post(GW + "/api/admin/kb", headers=auth,
                    json={"name": f"非法切片-{ts}", "chunkSize": 50})
    e1 = (r.json() or {}).get("error", "")
    r2 = client.post(GW + "/api/admin/kb", headers=auth,
                     json={"name": f"非法切片2-{ts}", "chunkSize": 99999})
    e2 = (r2.json() or {}).get("error", "")
    check("非法切片值被拒（50 / 99999）", bool(e1) and bool(e2), f"{e1} | {e2}")

    print("\n[3] 上传文档：按库切片参数入库")
    r = client.post(GW + f"/api/admin/kb/{kb_id}/documents", headers=auth,
                    files={"file": (DOC_NAME, DOC_CONTENT.encode("utf-8"), "text/plain")},
                    data={"category": "operation"})
    up = r.json()
    n300 = up.get("chunkCount") or 0
    check("上传成功且按 300 字符切片（分片 >= 2）",
          up.get("status") == "ready" and n300 >= 2,
          f"status={up.get('status')} chunkCount={n300} model={up.get('embeddingModel')}")

    print("\n[4] 改切片参数 → 重建索引按新参数重切；缺省不改原值")
    r = client.put(GW + f"/api/admin/kb/{kb_id}", headers=auth,
                   json={"name": f"切片参数验收-{ts}", "chunkSize": 1200})
    up2 = r.json() if r.status_code == 200 else {}
    check("改 chunkSize=1200", r.status_code == 200 and up2.get("chunkSize") == 1200, str(up2)[:120])

    r = client.post(GW + f"/api/admin/kb/{kb_id}/reindex", headers=auth)
    ri = r.json() if r.status_code == 200 else {}
    n1200 = ((ri.get("results") or [{}])[0] or {}).get("chunkCount") or 0
    check("重建索引按 1200 重切（分片数减少且 >= 1）",
          ri.get("ok") == 1 and 1 <= n1200 < n300,
          f"ok={ri.get('ok')} chunkCount={n1200}（此前 {n300}）")

    r = client.put(GW + f"/api/admin/kb/{kb_id}", headers=auth,
                   json={"name": f"切片参数验收-{ts}（改名不改切片）"})
    r2 = client.get(GW + f"/api/admin/kb/list", headers=auth)
    row2 = next((x for x in (r2.json() or []) if x.get("id") == kb_id), None)
    check("不含 chunkSize 的更新不改变原值", bool(row2) and row2.get("chunk_size") == 1200,
          str(row2)[:160])

    print("\n[5] 检索旁证：切片调整后仍能命中文档（AK/SK → /oa/chat/sync）")
    body = json.dumps({"message": QUESTION, "conversationId": str(uuid.uuid4())}, ensure_ascii=True)
    r = client.post(GW + "/oa/chat/sync", headers=signed_headers(body), content=body.encode("utf-8"))
    ans = r.text if r.status_code == 200 else ""
    hit = "180" in ans
    check("回答含文档关键数字 180", hit, f"http={r.status_code} answer={ans[:120]}")

    print(f"\n      验收库 id={kb_id}（管理台可见，可手动删除）")
    return finish()


def finish():
    ok = sum(1 for _, o in RESULTS if o)
    print("\n" + "=" * 60)
    print(f"知识库切片参数 E2E：{ok}/{len(RESULTS)} 通过")
    return 0 if ok == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())