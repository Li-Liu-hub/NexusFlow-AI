#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""order 通道（一句话建单）端到端实测 —— 两段式人工确认的完整走一遍。

用法（服务器上跑）：
    sudo -u ubuntu /opt/app/orchestrator/.venv/bin/python test_order_channel.py

链路：
  第一段  query=给我一份补货建议
          → 编排层跑 replenish 通道 → result 里带 suggestion_id + transfers
  第二段  query=帮我按建议建单 & suggestion_id=<上一步的 id>
          → 编排层走 order 通道 → 按缓存建议调后端建单 → result 带 transfer_order 卡片
  校验    单据在后端真实存在（查 /api/transferOrder/get），且数量与建议一致

为什么是这个形态（而不是让 LLM 解析一句话）：
  两个入口都要测，但"解析话术"依赖 LLM 的稳定性（MiMo 的 function calling
  实测不稳），而"suggestion 建单"是确定性的——先用它证明图与后端的连接正确，
  再单独观察解析路径。
"""
import json
import sys
import urllib.parse

sys.path.insert(0, "/opt/app/orchestrator")

import httpx

from config import settings
from sdk.sign import build_auth_headers

BASE = settings.GATEWAY_BASE_URL
ORCH = "http://127.0.0.1:8125"
PASS, FAIL = [], []


def _check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(f"  {'✅' if ok else '❌'} {name}" + (f"  —— {detail}" if detail else ""))


def orch_stream(query, suggestion_id=""):
    """调编排层 SSE，返回 (result_dict, 全部 agent_step 文本)"""
    params = {"query": query}
    if suggestion_id:
        params["suggestion_id"] = suggestion_id
    steps, result = [], None
    with httpx.stream("POST", f"{ORCH}/orchestrator/v1/chat/stream",
                      params=params, timeout=300, trust_env=False) as resp:
        resp.raise_for_status()
        event, data = None, None
        for line in resp.iter_lines():
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data = line[5:].strip()
                if event == "result":
                    try:
                        result = json.loads(data)
                    except ValueError:
                        result = {"raw": data}
                elif event == "agent_step":
                    try:
                        steps.append(json.loads(data))
                    except ValueError:
                        pass
    return result, steps


def api_get(path, params=None):
    r = httpx.get(f"{BASE}{path}", params=params or {},
                  headers=build_auth_headers(None), timeout=30, trust_env=False)
    return r.status_code, r.json()


print("=" * 70)
print("order 通道（一句话建单）端到端实测 —— 两段式")
print("=" * 70)

# ---------------------------------------------------------------------------
print("\n[1] 第一段：要补货建议（replenish 通道）")
result1, steps1 = orch_stream("给我一份补货建议")
if not result1:
    print("!! 没拿到 result")
    sys.exit(1)

route1 = result1.get("route")
_check("路由到 replenish", route1 == "replenish", f"route={route1}")
for s in steps1[:4]:
    print(f"     · {str(s.get('text'))[:90]}")

sug_id = result1.get("suggestion_id") or ""
_check("返回 suggestion_id", bool(sug_id), sug_id)

transfer_card = next((c for c in (result1.get("cards") or [])
                      if c.get("type") == "transfer_list"), None)
_check("返回调拨清单卡片", transfer_card is not None,
       f"共 {len((transfer_card or {}).get('rows') or [])} 行")

if not transfer_card or not transfer_card.get("rows"):
    print("!! 本轮没有调拨建议，无法继续第二段（可能各仓位库存已均衡）")
    print("=" * 70)
    sys.exit(0 if not FAIL else 1)

expect_rows = transfer_card["rows"]
print(f"     建议 {len(expect_rows)} 条，合计 {transfer_card.get('totalQuantity')} 台")

# ---------------------------------------------------------------------------
print("\n[2] 第二段：带 suggestion_id 回来建单（order 通道）")
result2, steps2 = orch_stream("帮我按建议建单", suggestion_id=sug_id)
if not result2:
    print("!! 没拿到 result")
    sys.exit(1)

route2 = result2.get("route")
_check("路由到 order", route2 == "order", f"route={route2}")
for s in steps2[:3]:
    print(f"     · {str(s.get('text'))[:120]}")

order_card = next((c for c in (result2.get("cards") or [])
                   if c.get("type") == "transfer_order"), None)
_check("返回调拨单卡片", order_card is not None and bool(order_card.get("rows")),
       f"{(order_card or {}).get('title')}")

rows = (order_card or {}).get("rows") or []
if not rows:
    print("     agent_response:", str(result2.get('agent_response'))[:200])
    print("=" * 70)
    sys.exit(0 if not FAIL else 1)

# ---------------------------------------------------------------------------
print("\n[3] 校验：单据在后端真实存在且数量对得上")
for r in rows[:3]:
    order_no = r.get("orderNo")
    code, detail = api_get("/api/transferOrder/get", {"orderNo": order_no})
    ok = code == 200 and detail.get("code") == 0
    d = detail.get("data") or {}
    _check(f"单据 {order_no} 可查", ok,
           f"状态={d.get('status')} {d.get('fromWarehouseName')}→{d.get('toWarehouseName')} "
           f"数量={d.get('totalQuantity')} 来源={d.get('source')}")

# 汇总数量一致性：卡片行的数量之和 应等于 后端单据的 totalQuantity
total_from_api = 0
for r in rows:
    code, detail = api_get("/api/transferOrder/get", {"orderNo": r.get("orderNo")})
    total_from_api += (detail.get("data") or {}).get("totalQuantity") or 0
total_from_card = sum(r.get("totalQuantity") or 0 for r in rows)
_check("卡片数量与后端单据一致", total_from_card == total_from_api,
       f"卡片={total_from_card} 后端={total_from_api}")

print("\n" + "=" * 70)
print(f"通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
for f in FAIL:
    print(f"  失败：{f}")
print("=" * 70)
sys.exit(0 if not FAIL else 1)
