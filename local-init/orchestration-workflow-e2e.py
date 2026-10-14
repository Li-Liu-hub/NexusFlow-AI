"""编排层三路线 + 告警闭环 E2E（2026-10-07）。

覆盖与用户对齐后的编排能力（门户「智能路由」入口的真实链路）：
  ① 生成图表路线    问「看看各仓库存分布」→ route=chart + 图表卡（数字全部来自取数接口）
  ② 补货建议路线    问「给我一份补货建议」→ route=replenish + 建议卡 + suggestion_id
     两段式第二段    带 suggestion_id 再请求 → route=order → 调拨单真实落库（查单号回证）
  ③ 知识问答路线    问「调拨有哪几种操作」→ route=kb + 非空回答（数字员工内核接管）
  ④ 告警闭环        编排层 scan→判定→notify 一轮 → 该仓账管通知表出现 STOCK_ALERT

链路：门户 Bearer → 网关（鉴权/限流/计量 + StripPrefix）→ 编排层 :8125 → 各原子能力
跑法（本机全栈已起）：python orchestration-workflow-e2e.py；失败返回非 0。
注意：② 会在库里真实创建调拨单（演示数据集，与既有两个闭环 E2E 同口径）。
"""

import json
import os
import sys

import httpx

# 默认本机网关；公网验证：GW=http://49.234.49.192（nginx 80 → 网关）
GW = os.environ.get("GW", "http://127.0.0.1:8090")
ACC, WH = "xn_zg", "9BZA"   # 账管账号与其仓库（缺货仓，供两段式建单与告警闭环用）

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(("  PASS " if ok else "  FAIL ") + name + ("  | " + detail if detail else ""))


def orch_chat(client, headers, query, suggestion_id="", warehouse_code="",
              conversation_id="", user_id="e2e_orch"):
    """调编排层 SSE（经网关门户通道），返回 (result, steps, events)。

    warehouse_code：数据可见范围（本仓 + 下级仓）；不传 = 不限制（服务间视角）。
    conversation_id：会话 id（沿用则续聊，不传则新建）。
    user_id：会话归属人（历史列表按它隔离）。
    """
    params = {"query": query, "user_id": user_id}
    if suggestion_id:
        params["suggestion_id"] = suggestion_id
    if warehouse_code:
        params["warehouse_code"] = warehouse_code
    if conversation_id:
        params["conversation_id"] = conversation_id
    result, steps, events = None, [], []
    with client.stream("POST", GW + "/api/orchestrator/v1/chat/stream",
                       headers=headers, params=params, timeout=420) as resp:
        if resp.status_code != 200:
            return None, steps, [f"HTTP {resp.status_code}"]
        event = None
        for line in resp.iter_lines():
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data = line[5:].strip()
                events.append(event)
                if event == "result":
                    try:
                        result = json.loads(data)
                    except ValueError:
                        result = None
                elif event == "agent_step":
                    try:
                        steps.append(json.loads(data).get("text", ""))
                    except ValueError:
                        pass
    return result, steps, events


def main():
    client = httpx.Client(timeout=420)
    r = client.post(GW + "/api/auth/login", json={"userCode": ACC, "password": "123456"})
    token = (r.json() or {}).get("token")
    h = {"Authorization": "Bearer " + (token or "")}
    check("门户登录（Bearer 通道）", bool(token), f"http={r.status_code}")
    if not token:
        return finish()

    # ---------------------------------------------------------------
    print("[1] 路线① 生成图表")
    # ---------------------------------------------------------------
    result, steps, events = orch_chat(client, h, "看看各仓库存分布")
    check("SSE 帧齐全（start/progress/agent_step/result）",
          {"start", "result"} <= set(events) and "agent_step" in events,
          "events=" + ",".join(sorted(set(events))))
    check("过程留痕：路由与取数步骤可见",
          any("[路由]" in s for s in steps) and any("[图表" in s for s in steps),
          " | ".join(s[:26] for s in steps[:3]))

    chart = next((c for c in ((result or {}).get("cards") or []) if c.get("type") == "chart"), None)
    check("落到图表路线且产出图表卡",
          bool(result) and result.get("route") == "chart" and chart is not None,
          f"route={(result or {}).get('route')}")
    data = ((chart or {}).get("series") or [{}])[0].get("data") or []
    check("图表卡数字来自取数接口（labels/series 均为真值）",
          bool(chart) and len(chart.get("labels") or []) == len(data) and len(data) > 0
          and all(isinstance(v, int) for v in data),
          (chart or {}).get("note", "")[:70])

    # ---------------------------------------------------------------
    print("[2] 路线② 补货建议（两段式形成调拨单）")
    # ---------------------------------------------------------------
    result, steps, events = orch_chat(client, h, "给我一份补货建议")
    cards = (result or {}).get("cards") or []
    types = [c.get("type") for c in cards]
    check("落到补货建议路线且产出建议卡",
          bool(result) and result.get("route") == "replenish" and "replenish_reminder" in types,
          f"route={(result or {}).get('route')} cards={types}")
    sid = (result or {}).get("suggestion_id") or ""
    check("返回两段式人工确认凭据 suggestion_id", bool(sid), sid)

    if "transfer_list" in types:
        result2, _, _ = orch_chat(client, h, "按建议建单", suggestion_id=sid)
        card = next((c for c in ((result2 or {}).get("cards") or [])
                     if c.get("type") == "transfer_order"), None)
        rows = (card or {}).get("rows") or []
        order_no = rows[0].get("orderNo") if rows else None
        check("两段式建单：带凭据直达 order 落点并产出调拨单卡",
              bool(result2) and result2.get("route") == "order" and bool(order_no),
              f"route={(result2 or {}).get('route')} orderNo={order_no}")
        if order_no:
            d = client.get(GW + "/api/transferOrder/get", headers=h, params={"orderNo": order_no})
            vo = (d.json() or {}).get("data") or {}
            check("调拨单真实落库（PENDING，待发送）",
                  vo.get("orderNo") == order_no and vo.get("status") == "PENDING",
                  f"status={vo.get('status')} qty={vo.get('totalQuantity')}")
    else:
        check("本机数据当前无调拨缺口（未产出 transfer_list）", False,
              f"cards={types} —— 请检查演示数据是否仍满足『存在跨仓缺口』")

    # ---------------------------------------------------------------
    print("[3] 路线③ 知识问答（数字员工内核接管）")
    # ---------------------------------------------------------------
    result, steps, events = orch_chat(client, h, "调拨有哪几种操作")
    ans = (result or {}).get("agent_response") or ""
    check("落到知识问答路线并给出非空回答",
          bool(result) and result.get("route") == "kb" and len(ans) >= 10,
          f"route={(result or {}).get('route')} len={len(ans)} | {ans[:40]}")

    # ---------------------------------------------------------------
    print("[4] 告警闭环（scan → 判定 → notify）")
    # ---------------------------------------------------------------
    r = client.post(GW + "/api/orchestrator/v1/alert/scan", headers=h,
                    params={"warehouse_code": WH})
    scan = r.json() or {}
    check("编排层巡检（本仓，原子扫描 + 投递）",
          r.status_code == 200 and scan.get("scanned") == 1 and not scan.get("scanError"),
          f"http={r.status_code} {scan}")
    r = client.get(GW + "/api/transferOrder/notices", headers=h,
                   params={"warehouseCode": WH, "role": "ACCOUNT_MANAGER",
                           "userCode": ACC, "limit": 30})
    notices = (r.json() or {}).get("data") or []
    alerts = [n for n in notices if n.get("noticeType") == "STOCK_ALERT"]
    check("账管通知表出现库存告警（未读）",
          bool(alerts) and any(not n.get("isRead") for n in alerts),
          f"告警 {len(alerts)} 条")

    # ---------------------------------------------------------------
    print("[5] 数据可见范围（本仓 + 下级仓，2026-10-07）")
    # ---------------------------------------------------------------
    # 基层（9BZA 新区账管）：图表只能看到本仓 1 条
    result, _, _ = orch_chat(client, h, "看看各仓库存分布", warehouse_code=WH)
    chart = next((c for c in ((result or {}).get("cards") or []) if c.get("type") == "chart"), None)
    check("基层用户只能看到本仓（图表仅 1 个仓位）",
          bool(chart) and chart.get("labels") == ["东宁市新区分公司"],
          f"labels={(chart or {}).get('labels')}")

    # 上级（9B00 东宁账管）：能看到本仓与全部下级仓（2 条）
    r = client.post(GW + "/api/auth/login", json={"userCode": "dn_zg", "password": "123456"})
    up_token = (r.json() or {}).get("token")
    h_up = {"Authorization": "Bearer " + (up_token or "")}
    result, _, _ = orch_chat(client, h_up, "看看各仓库存分布", warehouse_code="9B00")
    chart_up = next((c for c in ((result or {}).get("cards") or []) if c.get("type") == "chart"), None)
    check("上级用户能看到本仓与下级仓（图表 2 个仓位）",
          bool(chart_up) and len(chart_up.get("labels") or []) == 2,
          f"labels={(chart_up or {}).get('labels')}")

    # ---------------------------------------------------------------
    print("[6] 历史会话（PG 会话表 + 消息表，2026-10-07）")
    # ---------------------------------------------------------------
    import uuid as _uuid
    conv_id = "orch-e2e-" + _uuid.uuid4().hex[:6]
    result, _, _ = orch_chat(client, h, "库龄分布是什么样的？", warehouse_code=WH,
                             conversation_id=conv_id, user_id=ACC)
    check("对话落库并回传会话 id",
          (result or {}).get("conversation_id") == conv_id,
          f"conv={(result or {}).get('conversation_id')}")

    rows = client.get(GW + "/api/orchestrator/v1/conversation/list", headers=h,
                      params={"user_id": ACC, "limit": 10}).json()
    hit = next((x for x in (rows or []) if x.get("conversation_id") == conv_id), None)
    check("会话在「最近对话」列表可见（按用户隔离）", bool(hit),
          f"共 {len(rows or [])} 条；标题={(hit or {}).get('title')}")

    detail = client.get(GW + "/api/orchestrator/v1/conversation/" + conv_id, headers=h).json()
    msgs = (detail or {}).get("messages") or []
    check("会话详情含 user+assistant 两条消息",
          len(msgs) >= 2 and msgs[0].get("role") == "user" and msgs[-1].get("role") == "assistant",
          f"{[(m.get('role'), (m.get('content') or '')[:16]) for m in msgs]}")

    return finish()


def finish():
    fails = [n for n, ok in RESULTS if not ok]
    print("=" * 66)
    print(f"编排层三路线 E2E：{len(RESULTS) - len(fails)}/{len(RESULTS)} 通过")
    if fails:
        print("失败项：" + "、".join(fails))
        return 1
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main())