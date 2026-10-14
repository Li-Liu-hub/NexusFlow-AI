# 终端业务取数客户端：编排层 → 网关 → aismp-agent(8124) 的终端库存/调拨接口
#
# 链路：本模块 → 网关(/api/terminal/**、/api/transferOrder/**，见 72 号登记)
#       → lb://aismp-agent（网关 StripPrefix=1 剥掉 /api）
#       → MySQL api_db(terminal / warehouse / transfer_order)
#
# 为什么走网关而不是直连 8124：
#   1. 网关是平台的核心价值（AK/SK 鉴权 + 限流 + 调用计量），绕开它等于没落地平台；
#   2. 计量链路（网关 → RabbitMQ → 账本）是 M4 token 统计的数据来源，必须经过。
#
# 设计约定（与 tools/java_agent.py 保持一致）：
#   - body 走 json.dumps 默认 ensure_ascii=True —— 请求体同时要塞进 HTTP header 供网关验签，
#     header 只允许 ASCII，\uXXXX 转义是合法 JSON，服务端解析后内容不变
#   - GET 无请求体 → build_auth_headers(None) → 网关按 "null."+sk 验签
#   - 非 200 一律抛 TerminalApiError，工具层不做重试/降级，交给图上层决策
import json
import sys
from pathlib import Path
from typing import Any

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import settings
from sdk.sign import build_auth_headers

# 网关把 /api/terminal/** 与 /api/transferOrder/** 路由到 lb://aismp-agent，见 gateway 的 terminal_biz_route
PATH_SNAPSHOT = "/api/terminal/inventory/snapshot"
PATH_AGGREGATE = "/api/terminal/inventory/aggregate"
PATH_AGING = "/api/terminal/inventory/aging"
PATH_BY_MODEL = "/api/terminal/inventory/byModel"
PATH_STAT_CARDS = "/api/terminal/stat/cards"
PATH_WAREHOUSES = "/api/terminal/warehouse/list"
PATH_ORDER_CREATE = "/api/transferOrder/create"
PATH_ORDER_LIST = "/api/transferOrder/list/page"
PATH_ORDER_GET = "/api/transferOrder/get"
PATH_NOTICE_UPSERT = "/api/transferOrder/notice"
PATH_STOCK_ALERT_SCAN = "/api/terminal/stockAlert/scan"

DEFAULT_TIMEOUT = 30


class TerminalApiError(Exception):
    """终端业务接口调用失败（网关拦截 / 下游 5xx / 超时 / 业务错误码非 0）。"""


def _get(path: str, params: dict | None = None) -> Any:
    """GET 调用：无 body，网关按 "null." + secretKey 验签"""
    headers = build_auth_headers(None)
    try:
        resp = httpx.get(
            f"{settings.GATEWAY_BASE_URL}{path}",
            params=params or {},
            headers=headers,
            timeout=DEFAULT_TIMEOUT,
            trust_env=False,  # 网关在 127.0.0.1，走系统代理会被转发超时
        )
    except httpx.HTTPError as exc:
        # 网络层异常（连接拒绝/超时/DNS）必须转成 TerminalApiError：
        # 图上层只捕获这一种，漏掉就会让整个编排崩溃而不是优雅降级
        raise TerminalApiError(f"{path} 网关不可达: {exc}") from exc
    return _unwrap(resp, path)


def _post(path: str, payload: dict, params: dict | None = None) -> Any:
    """POST 调用：body 既作为请求体，也进 header 供网关验签；params 走查询串"""
    body = json.dumps(payload)  # ensure_ascii=True，保证 header 全 ASCII
    headers = build_auth_headers(body)
    try:
        resp = httpx.post(
            f"{settings.GATEWAY_BASE_URL}{path}",
            params=params or {},
            content=body.encode("utf-8"),
            headers={"Content-Type": "application/json", **headers},
            timeout=DEFAULT_TIMEOUT,
            trust_env=False,
        )
    except httpx.HTTPError as exc:
        raise TerminalApiError(f"{path} 网关不可达: {exc}") from exc
    return _unwrap(resp, path)


def _unwrap(resp: httpx.Response, path: str) -> Any:
    """拆掉 BaseResponse 外壳，只返回 data；业务错误码非 0 时抛异常。"""
    if resp.status_code != 200:
        raise TerminalApiError(
            f"{path} 调用失败 status={resp.status_code} body={resp.text[:300]}"
        )
    try:
        payload = resp.json()
    except ValueError as exc:
        raise TerminalApiError(f"{path} 返回非 JSON：{resp.text[:200]}") from exc

    code = payload.get("code")
    if code != 0:
        raise TerminalApiError(
            f"{path} 业务错误 code={code} message={payload.get('message')}"
        )
    return payload.get("data")


# ----------------------------------------------------------------------------
# 取数接口
# ----------------------------------------------------------------------------

def fetch_snapshot(warehouse_code: str | None = None, model: str | None = None) -> dict:
    """库存快照 —— fetch 取数智能体的首选入口（一次取全，保证一致视图）"""
    params = {}
    if warehouse_code:
        params["warehouseCode"] = warehouse_code
    if model:
        params["model"] = model
    return _get(PATH_SNAPSHOT, params)


def aggregate_inventory(warehouse_code: str | None = None,
                        model: str | None = None,
                        big_type: str | None = None) -> list[dict]:
    """库存聚合（按 仓位 × 物料 × 型号）"""
    params = {}
    if warehouse_code:
        params["warehouseCode"] = warehouse_code
    if model:
        params["model"] = model
    if big_type:
        params["bigType"] = big_type
    return _get(PATH_AGGREGATE, params) or []


def aging_distribution(warehouse_code: str | None = None) -> list[dict]:
    """库龄分布"""
    params = {"warehouseCode": warehouse_code} if warehouse_code else {}
    return _get(PATH_AGING, params) or []


def aggregate_by_model() -> list[dict]:
    """按型号跨仓位汇总（判断全省是否有货、是否只能采购）"""
    return _get(PATH_BY_MODEL) or []


def stat_cards() -> dict:
    """首页四个统计卡片"""
    return _get(PATH_STAT_CARDS)


def list_warehouses() -> list[dict]:
    """仓位列表"""
    return _get(PATH_WAREHOUSES) or []


# ----------------------------------------------------------------------------
# 写接口（AI 只用到建单这一步）
# ----------------------------------------------------------------------------

def create_transfer_order(operation_type: str,
                          from_warehouse: str,
                          to_warehouse: str,
                          items: list[dict],
                          creator: str = "ai-agent",
                          source: str = "AI",
                          suggestion_id: str | None = None) -> dict:
    """创建调拨单 —— AI 智能体的唯一写操作。

    出库/入库不在编排层发生（开发文档 M2 关键机制 3：AI 不介入库存事务）。
    """
    payload: dict = {
        "operationType": operation_type,
        "fromWarehouse": from_warehouse,
        "toWarehouse": to_warehouse,
        "creator": creator,
        "source": source,
        "items": items,
    }
    if suggestion_id:
        payload["suggestionId"] = suggestion_id
    return _post(PATH_ORDER_CREATE, payload)


def list_transfer_orders(current: int = 1, page_size: int = 10,
                         status: str | None = None) -> dict:
    """调拨单分页列表"""
    params: dict = {"current": current, "pageSize": page_size}
    if status:
        params["status"] = status
    return _get(PATH_ORDER_LIST, params)


def get_transfer_order(order_no: str) -> dict:
    """调拨单详情"""
    return _get(PATH_ORDER_GET, {"orderNo": order_no})


# ----------------------------------------------------------------------------
# 告警闭环的两个原子接口（编排层 scan → 判定 → notify）
# ----------------------------------------------------------------------------

def stock_alert_scan(warehouse_code: str | None = None) -> dict:
    """库存告警巡检（原子能力）：查 + 判定，返回发现清单（含通知草稿）。

    agent 侧不做定时、不写通知 —— 定时节奏与投递由编排层负责
    （见 alert_graph.py）。返回结构：
        {"scanned": N, "findings": [{"warehouseCode","warehouseName",
          "suggestionCount","notice":{"receiver","noticeType","title","content"}}]}
    """
    params = {"warehouseCode": warehouse_code} if warehouse_code else None
    return _post(PATH_STOCK_ALERT_SCAN, {}, params=params) or {}


def upsert_notice(receiver: str, notice_type: str, title: str, content: str,
                  order_no: str | None = None) -> dict:
    """写 / 刷新一条站内通知（幂等：同 receiver+type 未读 → 只刷新不新增）。

    :return: {"action": "inserted"|"updated", "id": ...}
    """
    payload: dict = {
        "receiver": receiver,
        "noticeType": notice_type,
        "title": title,
        "content": content,
    }
    if order_no:
        payload["orderNo"] = order_no
    return _post(PATH_NOTICE_UPSERT, payload) or {}


# ----------------------------------------------------------------------------
# 时间维度取数（2026-10-08）：入库趋势 / 月度对比 —— 图表路线 time 维度的数据源
# 口径：全部按【首次入库时间 firstInboundAt】归桶（历史视角，不随设备现状变化）。
# ----------------------------------------------------------------------------

PATH_INBOUND_TREND = "/api/terminal/inventory/inboundTrend"
PATH_MONTH_COMPARE = "/api/terminal/inventory/monthCompare"


def inbound_trend(granularity: str = "month", from_date: str | None = None,
                  to_date: str | None = None, group_by: str = "none") -> dict:
    """入库量时间序列（月/年分桶）。

    返回 InboundTrendVO 形态：
        {"granularity","from","to","totalCnt","bucketCount",
         "rows":[{"period","groupKey","groupName","cnt"}]}
    group_by=warehouse/model 时同一 period 内多行（编排层按可见范围自行聚合）；
    totalCnt 恒为各桶之和，聚合后取总和即可，不让 LLM 自己求和（数字红线）。
    """
    params: dict = {"granularity": granularity, "groupBy": group_by}
    if from_date:
        params["from"] = from_date
    if to_date:
        params["to"] = to_date
    return _get(PATH_INBOUND_TREND, params) or {}


def month_compare(months_csv: str) -> dict:
    """指定月份的入库量对比（months_csv 如 "2026-03,2026-09"，升序返回，缺失补 0）。"""
    return _get(PATH_MONTH_COMPARE, {"months": months_csv}) or {}
