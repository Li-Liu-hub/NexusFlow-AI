# 数字员工内核调用工具：编排层 → 数字员工内核（aismp-digital-human）的唯一出口
#
# 两条链路：
#   经网关（默认，DIGITAL_HUMAN_VIA_GATEWAY=True）：
#     本模块 → 网关 /digitalHuman/**（AK/SK 签名 + 限流 + 调用计量）
#            → 网关 StripPrefix 去掉 /digitalHuman → 内核真实路径 /agent/... → aismp-digital-human(8126)
#   直连（仅本机调试）：本模块 → 127.0.0.1:8126/agent/...
#
# 为什么必须经网关：
#   1. 网关是平台的核心价值（鉴权 + 限流 + 计量），绕开它等于没落地平台；
#   2. 计量链路（网关 → RabbitMQ → 账本）是 M4 token 统计的数据来源，必须经过。
#
# 接口已在 interface_info 登记（见 local-init/61-digital-human-interface-registry.sql），
# 登记的是**带 /digitalHuman 前缀的网关侧路径** —— 因为鉴权过滤器 getOrder()=-1，
# 先于 StripPrefix 执行，看到的是原始请求路径。
import json
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import settings
from sdk.sign import build_auth_headers

# 网关侧路径（带前缀）
CHAT_PATH_GW = "/digitalHuman/agent/{agent_code}/chat"
INTROSPECT_PATH_GW = "/digitalHuman/agent/{agent_code}/introspect"
# 内核真实路径（直连时用）
CHAT_PATH_DIRECT = "/agent/{agent_code}/chat"
INTROSPECT_PATH_DIRECT = "/agent/{agent_code}/introspect"

# 内核登录鉴权：编排层用 SERVICE 账号（a_user.role=SERVICE）拿 24h 令牌，
# 所有对内核的调用带 Bearer 头；SERVICE 身份允许在 body 里代传终端用户 userId。
# 登录必须直连内核 /auth/login（匿名接口）—— 网关只放行 interface_info 登记过的路径。
_SVC_USER = "svc_orchestrator"
_SVC_PASS = "svc-orch-2026"
_token_cache: dict = {"token": None}


def _service_token() -> str:
    """拿服务令牌：模块级缓存，首次调用时登录内核。"""
    if _token_cache["token"]:
        return _token_cache["token"]
    try:
        response = httpx.post(
            f"{settings.DIGITAL_HUMAN_DIRECT_URL}/auth/login",
            json={"userCode": _SVC_USER, "password": _SVC_PASS},
            timeout=10,
            trust_env=False,
        )
    except httpx.HTTPError as exc:
        # 2026-10-10 补：此前漏了这个 except —— 连不上内核时裸抛 httpx.ConnectError，
        # 被图外层兜底成「编排异常：ConnectError: ...」（信息量最低）。
        # 与 terminal_api/java_agent 的处理对齐：转成领域异常，错误信息带上实际目标地址。
        raise DigitalHumanError(
            f"内核不可达（{settings.DIGITAL_HUMAN_DIRECT_URL}）: {exc}") from exc
    if response.status_code != 200:
        raise DigitalHumanError(f"服务账号登录失败 status={response.status_code} body={response.text[:200]}")
    data = response.json()
    if data.get("error"):
        raise DigitalHumanError(f"服务账号登录被拒: {data['error']}")
    _token_cache["token"] = data["token"]
    return _token_cache["token"]


def _reset_service_token() -> None:
    """令牌失效（内核 401）后清缓存，下次调用重新登录。"""
    _token_cache["token"] = None


class DigitalHumanError(Exception):
    """数字员工调用失败（服务未起 / 网关拦截 / 5xx / 超时）。降级决策交给图上层，工具只报事实。"""


def _post_chat(agent_code: str, message: str, data_scope: list[str] | None = None,
               conversation_id: str | None = None) -> dict:
    """经网关调用：body 同时作为请求体，也进 header 供网关验签（ensure_ascii 理由见 java_agent.py）。

    网关验签（AK/SK）与内核登录鉴权（Bearer）叠加：网关透传 Authorization 头到内核。

    data_scope：调用方的数据可见范围（仓位编码，本仓 + 全部下级仓）。
    内核用它给本轮提问加【数据权限】片段，并在工具层拦截越权仓位（2026-10-07）。
    conversation_id：内核侧会话 id（归档 + 记忆，追问接上下文）。
    """
    payload: dict = {"message": message}
    if data_scope:
        payload["dataScope"] = list(data_scope)
    if conversation_id:
        payload["conversationId"] = conversation_id
    body = json.dumps(payload)
    headers = build_auth_headers(body)
    headers["Authorization"] = f"Bearer {_service_token()}"
    path = CHAT_PATH_GW.format(agent_code=agent_code)
    try:
        response = httpx.post(
            f"{settings.GATEWAY_BASE_URL}{path}",
            content=body.encode("utf-8"),
            headers={"Content-Type": "application/json", **headers},
            timeout=settings.DIGITAL_HUMAN_TIMEOUT,
            trust_env=False,  # 网关在 127.0.0.1，走系统代理会被转发超时
        )
    except httpx.HTTPError as exc:
        raise DigitalHumanError(f"网关不可达: {exc}") from exc
    return _unwrap(response, path)


def _post_chat_direct(agent_code: str, message: str, data_scope: list[str] | None = None,
                      conversation_id: str | None = None) -> dict:
    """直连内核（调试用）：无网关签名，路径不带 /digitalHuman 前缀，带服务令牌。"""
    payload: dict = {"message": message}
    if data_scope:
        payload["dataScope"] = list(data_scope)
    if conversation_id:
        payload["conversationId"] = conversation_id
    path = CHAT_PATH_DIRECT.format(agent_code=agent_code)
    try:
        response = httpx.post(
            f"{settings.DIGITAL_HUMAN_DIRECT_URL}{path}",
            json=payload,
            headers={"Authorization": f"Bearer {_service_token()}"},
            timeout=settings.DIGITAL_HUMAN_TIMEOUT,
            trust_env=False,
        )
        if response.status_code == 401:   # 令牌过期/被清：重登一次再试
            _reset_service_token()
            response = httpx.post(
                f"{settings.DIGITAL_HUMAN_DIRECT_URL}{path}",
                json=payload,
                headers={"Authorization": f"Bearer {_service_token()}"},
                timeout=settings.DIGITAL_HUMAN_TIMEOUT,
                trust_env=False,
            )
    except httpx.HTTPError as exc:
        raise DigitalHumanError(f"数字员工服务不可达: {exc}") from exc
    return _unwrap(response, path)


def _unwrap(response: httpx.Response, path: str) -> dict:
    if response.status_code == 403:
        raise DigitalHumanError(
            f"{path} 被网关拒绝(403) —— 通常是接口未在 interface_info 登记，或签名不对")
    if response.status_code != 200:
        raise DigitalHumanError(
            f"{path} 调用失败 status={response.status_code} body={response.text[:200]}")

    data = response.json()
    # 网关可能包一层统一响应壳，兼容 {code,data} 与裸对象两种形态
    if isinstance(data, dict) and "code" in data and "data" in data:
        if data.get("code") != 0:
            raise DigitalHumanError(f"{path} 业务错误 code={data.get('code')} message={data.get('message')}")
        data = data.get("data")
    if isinstance(data, dict) and data.get("error"):
        raise DigitalHumanError(f"数字员工执行异常: {data['error']}")
    return data


def digital_human_chat(agent_code: str, message: str,
                       data_scope: list[str] | None = None,
                       conversation_id: str | None = None) -> dict:
    """让指定数字员工回答一个问题。

    :param agent_code: 数字员工编码（a_agent.agent_code），决定用哪套人设与工具
    :param message: 用户问题原文
    :param data_scope: 数据可见范围（仓位编码：本仓 + 全部下级仓）；
                       内核据此给提问加【数据权限】并拦截越权工具查询（2026-10-07）
    :param conversation_id: 会话 id —— 内核侧归档 + 选择性记忆（追问能接上下文）；
                            与编排层的 orch_conversation 同 id 对齐
    :return: {"version": int, "answer": str, "elapsedMs": int, ...}
    :raises DigitalHumanError: 网关拦截 / 非 200 / 网络异常
    """
    if settings.DIGITAL_HUMAN_VIA_GATEWAY:
        return _post_chat(agent_code, message, data_scope, conversation_id)
    return _post_chat_direct(agent_code, message, data_scope, conversation_id)


def digital_human_introspect(agent_code: str) -> dict:
    """查看数字员工运行时装配结果（技能/工具/提示词）。排障与演示用。"""
    if settings.DIGITAL_HUMAN_VIA_GATEWAY:
        path = INTROSPECT_PATH_GW.format(agent_code=agent_code)
        url = f"{settings.GATEWAY_BASE_URL}{path}"
        headers = build_auth_headers(None)  # GET 无 body，网关按 "null."+sk 验签
        headers["Authorization"] = f"Bearer {_service_token()}"
    else:
        path = INTROSPECT_PATH_DIRECT.format(agent_code=agent_code)
        url = f"{settings.DIGITAL_HUMAN_DIRECT_URL}{path}"
        headers = {"Authorization": f"Bearer {_service_token()}"}

    try:
        response = httpx.get(url, headers=headers, timeout=30, trust_env=False)
    except httpx.HTTPError as exc:
        raise DigitalHumanError(f"数字员工服务不可达: {exc}") from exc
    if response.status_code != 200:
        raise DigitalHumanError(f"{path} 自省失败 status={response.status_code}")
    return response.json()
