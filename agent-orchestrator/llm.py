# LLM 统一出口：rewrite/respond(M2)、critic 判分(M4) 全部从这里拿实例
# OpenAI 兼容端点（z.ai GLM）：换厂商只改 .env，不改代码
import logging
import threading

import httpx
from langchain_core.callbacks import BaseCallbackHandler
from langchain_openai import ChatOpenAI

from config import settings

log = logging.getLogger("orchestrator.llm")

# backend 的落账端点（同机直连，与内核 BackendTokenClient 同构；/inner/** 不经网关）
# 2026-10-10 容器化修复：原写死 http://127.0.0.1:7529 —— 原生部署"同机"成立，
# 容器里 127.0.0.1 是编排层自己的回环 → Connection refused，
# 编排层那 5 类 LLM 节点的 token 用量一直落不了账（管理台恒为 0）。
# 改为可配置（config.CHARGE_URL），compose 注入 http://backend:7529/api/inner/token/charge
_CHARGE_URL = settings.CHARGE_URL


class _UsageChargeHandler(BaseCallbackHandler):
    """把每一跳 LLM 用量旁路落账到 backend 账本（2026-10-08 计量盲区修复）。

    此前编排层 5 类 LLM 调用（意图识别/图表选型/报告/建单/质检）直连模型、
    不经内核 ChatModel 装饰器，用量在 a_token_usage_daily 里完全不可见 ——
    管理台「智能体 token 用量」卡上这些节点恒为 0。

    粒度与内核对齐：每跳模型调用一笔（on_llm_end 触发一次），
    agent_code 用 a_agent 目录里注册的节点名，管理台按目录名展示。
    落账走守护线程 fire-and-forget：计量是旁路，绝不能拖慢/拖垮对话。
    """

    def __init__(self, user_id, agent_code):
        self.user_id = user_id or "anonymous"
        self.agent_code = agent_code

    def on_llm_end(self, response, **kwargs) -> None:
        try:
            usage = (getattr(response, "llm_output", None) or {}).get("token_usage") or {}
            prompt_tokens = int(usage.get("prompt_tokens") or 0)
            completion_tokens = int(usage.get("completion_tokens") or 0)
            if prompt_tokens <= 0 and completion_tokens <= 0:
                return
            threading.Thread(
                target=self._post,
                args=(prompt_tokens, completion_tokens),
                daemon=True,
            ).start()
        except Exception as exc:  # noqa: BLE001 —— 计量失败不影响对话
            log.warning("编排层用量落账调度失败（忽略）: %s", exc)

    def _post(self, prompt_tokens, completion_tokens) -> None:
        try:
            httpx.post(_CHARGE_URL, timeout=3, json={
                "userId": self.user_id,
                "agentCode": self.agent_code,
                "model": settings.LLM_MODEL,
                "requests": 1,
                "promptTokens": prompt_tokens,
                "completionTokens": completion_tokens,
                "totalTokens": prompt_tokens + completion_tokens,
                "toolCalls": 0,
            })
        except Exception as exc:  # noqa: BLE001
            log.warning("编排层用量落账失败（忽略）: %s", exc)


def get_llm(user_id=None, agent_code=None, **overrides) -> ChatOpenAI:
    """返回配置好的 ChatOpenAI 实例；overrides 可临时覆盖参数（如 temperature）。

    agent_code 传入时挂用量回调（落账到 a_token_usage_daily，归属 user_id）——
    只有编排层自己的 LLM 节点需要；工具内嵌/其它直连场景不传即与旧行为一致。
    """
    params = dict(
        model=settings.LLM_MODEL,
        api_key=settings.LLM_API_KEY,
        base_url=settings.LLM_BASE_URL,
        timeout=60,
        # 网络策略实证（2026-09-17）：api.z.ai 国际端点直连 TLS 握手会被干扰（curl 裸测
        # 3/3 失败、单次吊 76s），走本地 FlClash 隧道则 5/5 全通 0.3~0.6s。
        # trust_env=False 摆脱 Windows 系统代理的开关状态（注册表常指死端口→10061），
        # LLM_PROXY 显式声明出国代理——代理软件没开时是"明确失败"而不是 60s 静默挂死
        http_client=httpx.Client(
            trust_env=False, timeout=60,
            proxy=settings.LLM_PROXY or None),
    )
    params.update(overrides)
    if agent_code:
        params["callbacks"] = [_UsageChargeHandler(user_id, agent_code)]
    return ChatOpenAI(**params)
