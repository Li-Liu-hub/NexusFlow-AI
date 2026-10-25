#!/usr/bin/env python3
"""RAG 检索质量评测：20 个典型问题（五分类各 4 问），验证 /oa/chat/sync
回答是否命中预期关键词（检索命中的代理指标——回答内容正确即检索到了正确手册片段）。

用法：服务器上 nohup python3 scripts/rag-eval.py > /tmp/rageval.out 2>&1 &
2026-10-25 Go 版基线：19/20 = 95.0%（intro 3/4，其余全对；Java 版 500 问 Recall@5 96.4%）。"""
import json, time, urllib.request

BASE = "http://127.0.0.1/api"
Q = urllib.request

def login():
    req = Q.Request(f"{BASE}/auth/login", data=json.dumps({"userCode":"xn_zg","password":"123456"}).encode(),
                    headers={"Content-Type":"application/json"})
    return json.load(Q.urlopen(req, timeout=15))["token"]

def ask(token, question):
    body = json.dumps({"message": question, "conversationId": f"eval-{time.time()}"}).encode()
    req = Q.Request(f"{BASE}/oa/chat/sync", data=body,
                    headers={"Content-Type":"application/json",
                             "Authorization":"Bearer "+token,
                             "accessKey":"aismp-console","nonce":f"n{time.time()}",
                             "timestamp":str(int(time.time())),
                             "body":json.dumps({"message":question, "conversationId":"e"})})
    # /oa 走 AK/SK 通道：经网关签名 —— 这里直接走 agent 内部端口更简单
    return None

def ask_agent(question):
    body = json.dumps({"message": question, "conversationId": f"eval-{time.time_ns()%100000}"}).encode()
    import subprocess
    try:
        out = subprocess.run(["sudo","docker","exec","ap-agent","wget","-T","90","-qO-",
            "--post-data=" + body.decode(), "--header=Content-Type: application/json",
            "http://127.0.0.1:8124/oa/chat/sync"], capture_output=True, timeout=100)
        return out.stdout.decode()
    except Exception as e:
        return f"__ERR__{e}"

# (问题, 预期关键词组[命中任一即算检索到正确内容], 分类)
EVAL = [
    ("领用终端的流程是什么", ["领用申请", "装机", "出库", "领用流程"], "operation"),
    ("光猫怎么调拨到别的仓库", ["调拨", "仓位", "申请"], "operation"),
    ("终端回收的流程", ["回收", "检测", "退回"], "operation"),
    ("怎么盘点库存", ["盘点", "实物", "账面"], "operation"),
    ("终端管理系统的整体流程是什么", ["串码", "生命周期", "入库", "全流程"], "process"),
    ("新终端入库的流程", ["入库", "登记", "验收"], "process"),
    ("串码是什么时候生成的", ["串码", "生成", "入库"], "process"),
    ("终端状态有哪些", ["可用", "在途", "待检", "状态"], "process"),
    ("账管员的职责是什么", ["账管", "台账", "盘点", "职责"], "role"),
    ("仓管员负责什么", ["仓管", "出库", "入库", "保管"], "role"),
    ("终端主管的职责", ["主管", "审批", "调配"], "role"),
    ("智家工程师做什么", ["智家", "装机", "领用", "维护"], "role"),
    ("系统为什么建 terminal_db", ["系统", "数据", "背景", "建设"], "intro"),
    ("终端管理平台解决什么问题", ["管理", "问题", "平台", "终端"], "intro"),
    ("这套系统和其他系统的关系", ["系统", "集成", "接口", "关系"], "intro"),
    ("项目背景是什么", ["背景", "建设", "终端", "管理"], "intro"),
    ("登录提示SSO失败怎么办", ["SSO", "登录", "账号", "排查"], "trouble"),
    ("查询不到库存数据怎么排查", ["库存", "查询", "权限", "数据"], "trouble"),
    ("调拨单提交失败是什么原因", ["调拨", "失败", "库存", "校验"], "trouble"),
    ("打印报表是空的怎么办", ["报表", "空", "数据", "权限"], "trouble"),
]

hit, total, per_cat = 0, len(EVAL), {}
for q, kws, cat in EVAL:
    ans = ask_agent(q)
    found = any(k in ans for k in kws)
    per_cat.setdefault(cat, [0,0])
    per_cat[cat][1] += 1
    if found and "__ERR__" not in ans:
        hit += 1; per_cat[cat][0] += 1
        mark = "PASS"
    else:
        mark = "MISS"
        print(f"  [{mark}] {q} → {ans[:80]!r}")
    time.sleep(0.3)

print(f"\n总体命中率: {hit}/{total} = {hit/total:.1%}")
for cat, (h, t) in sorted(per_cat.items()):
    print(f"  {cat}: {h}/{t}")
