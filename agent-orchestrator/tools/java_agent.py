# Java 层 agent 调用工具：编排层 → 网关 → Java agent 的唯一出口
# 链路: 本函数 → POST {GATEWAY}/oa/chat/sync / GET {GATEWAY}/oa/agent/chat/sse
#       （签名 headers 见 sdk/sign.py）→ 网关六层校验 → Dubbo 路由
#       → aismp-agent(8124) → PG(RAG/记忆) → 回答原路返回
# 设计取舍见开发文档 M2-S3 ②、M3-S2 ②
import json
import sys
from collections.abc import Iterator
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import settings
from sdk.sign import build_auth_headers

CHAT_SYNC_PATH = "/oa/chat/sync"
AGENT_SSE_PATH = "/oa/agent/chat/sse"


class JavaAgentError(Exception):
    """Java agent 调用失败（网关拦截 / 下游 5xx / 超时）。降级决策交给图上层，工具只报事实。"""


def java_rag_agent(question: str, conversation_id: str | None = None) -> str:
    """问一个问题给 Java 层 RAG agent，返回回答文本。

    - question: 已重写的查询（S4 中传 rewritten_query）
    - conversation_id: 会话 ID；None = 全新会话（与 Java SDK 行为一致，缺省即省略该字段）
    - 非 200 一律抛 JavaAgentError——工具不做重试/降级，让 S4/M4 的降级链决定怎么办
    """
    payload: dict = {"message": question}
    if conversation_id is not None:
        payload["conversationId"] = conversation_id
    # ensure_ascii 默认 True：非 ASCII 转 \uXXXX。原因：body 同时要塞进 HTTP header（网关取 header 验签），
    # header 值只允许 ASCII；\uXXXX 转义是合法 JSON，下游解析后内容不变
    body = json.dumps(payload)

    headers = build_auth_headers(body)
    try:
        response = httpx.post(
            f"{settings.GATEWAY_BASE_URL}{CHAT_SYNC_PATH}",
            content=body.encode("utf-8"),
            headers={"Content-Type": "application/json", **headers},
            timeout=60,
            trust_env=False,  # 忽略系统代理：网关是 127.0.0.1，走代理会被转发超时
        )
    except httpx.HTTPError as exc:
        # 网络层异常统一转成 JavaAgentError：图上层只捕获这一种
        raise JavaAgentError(f"agent 网关不可达: {exc}") from exc
    if response.status_code != 200:
        raise JavaAgentError(
            f"agent 调用失败 status={response.status_code} body={response.text[:200]}"
        )
    return response.text


def java_rag_agent_stream(question: str, conversation_id: str | None = None) -> Iterator[str]:
    """流式版：GET /oa/agent/chat/sse，Java ReAct 循环每跑完一步推一帧，逐帧 yield 文本。

    - GET 无请求体 → build_auth_headers(None) → 网关按 "null."+sk 验签（与 M3-S1 同语义）
    - message/conversationId 走查询串（OaAgentController.chatStream 的 @RequestParam）
    - SseEmitter 只发 data 帧（无事件名），每帧一条 "Step N: ..." 文本；解析在图侧做
    - 非正常结束（中途断流/超时）抛 JavaAgentError——注意与 java_rag_agent 不同：
      流开始后的失败抛在 yield 之间，调用方（图节点）可能已推过部分步骤
    """
    params: dict = {"message": question}
    if conversation_id is not None:
        params["conversationId"] = conversation_id
    headers = build_auth_headers(None)
    try:
        with httpx.stream(
            "GET",
            f"{settings.GATEWAY_BASE_URL}{AGENT_SSE_PATH}",
            params=params,
            headers=headers,
            timeout=300,  # SseEmitter 5 分钟超时，与 Java 侧对齐
            trust_env=False,  # 忽略系统代理：网关是 127.0.0.1，走代理会被转发超时（M4 冒烟实测踩中）
        ) as resp:
            if resp.status_code != 200:
                raise JavaAgentError(
                    f"agent SSE 调用失败 status={resp.status_code}"
                )
            for line in resp.iter_lines():
                if line.startswith("data:"):
                    # SseEmitter.send(String) 的帧格式 "data:xxx"；冒号后无空格也兼容
                    yield line[5:].lstrip()
    except httpx.HTTPError as exc:
        # 网络层异常统一转成 JavaAgentError（连接失败发生在 yield 之前时尤其重要）
        raise JavaAgentError(f"agent SSE 网关不可达: {exc}") from exc
