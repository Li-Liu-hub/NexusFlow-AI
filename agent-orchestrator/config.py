# 统一配置：从项目根 .env 读取，全项目唯一入口
from pathlib import Path

from dotenv import load_dotenv
from pydantic_settings import BaseSettings

_root = Path(__file__).resolve().parent
load_dotenv(_root / ".env")


class Settings(BaseSettings):
    # 网关鉴权三元组
    GATEWAY_BASE_URL: str = "http://localhost:8090"
    GATEWAY_ACCESS_KEY: str = ""
    GATEWAY_SECRET_KEY: str = ""

    # 图来源开关（画布方案，docs/Dify编排迁移-工程量评估.md 第十二节）：
    #   code = 现网硬编码图（默认，行为与改造前完全一致）
    #   dsl  = 最新 published 画布图（管理端发布后新对话立即生效）
    # dsl 链路故障自动回退 code —— 画布故障绝不阻塞对话
    GRAPH_SOURCE: str = "code"

    # LLM（OpenAI 兼容，z.ai GLM）
    LLM_API_KEY: str = ""
    LLM_BASE_URL: str = "https://api.z.ai/api/coding/paas/v4"
    LLM_MODEL: str = "glm-5.2"
    # LLM 出国代理（api.z.ai 国际端点直连 TLS 会被干扰，实测 3/3 握手失败；走本地
    # FlClash 隧道 5/5 全通 0.3~0.6s）。空串=直连；网关 127.0.0.1 调用永远不受此影响
    LLM_PROXY: str = ""

    # 数字员工内核（aismp-digital-human，Spring Boot 4 + Spring AI 2.0 + SAA）
    DIGITAL_HUMAN_AGENT_CODE: str = "terminal_manager"
    DIGITAL_HUMAN_TIMEOUT: int = 180  # 数字员工内部会跑 ReAct 多轮 + LLM 调用，超时给足

    # 是否经网关调用数字员工：
    #   True  = 走网关（AK/SK 鉴权 + 限流 + 调用计量）—— 生产形态。
    #           绕开网关等于绕开平台的核心价值，M4 的 token 计量也收不到数字员工的消耗。
    #   False = 直连 8126 —— 仅在本机单独调试内核、网关未起时用。
    DIGITAL_HUMAN_VIA_GATEWAY: bool = True
    DIGITAL_HUMAN_DIRECT_URL: str = "http://127.0.0.1:8126"

    # 库存告警定时巡检周期（秒）。2026-10-07 起巡检闭环（scan→判定→notify）上移到编排层，
    # agent 只提供原子能力；周期属于编排决策，改这里即可。
    # 默认 600（10 分钟）；演示时可调成 60；≤0 = 关闭定时（手动端点仍可用）。
    ALERT_INTERVAL_SECONDS: int = 600

    # backend 的 LLM 用量落账端点（llm.py 的 _UsageChargeHandler 旁路落账）。
    # /inner/** 不经网关。原生部署同机 127.0.0.1 成立；容器里必须由 compose
    # 注入 http://backend:7529，否则打到编排层自己的回环 → Connection refused，
    # 编排层那 5 类 LLM 节点（意图识别/图表选型/报告/建单/质检）的 token 用量落不了账。
    CHARGE_URL: str = "http://127.0.0.1:7529/api/inner/token/charge"

    # PostgreSQL：会话/消息持久化（orch_conversation / orch_message）。
    # 与内核 RAG 同实例、不同表；PG 不可用时历史暂不记录（对话不受影响）。
    PG_HOST: str = "127.0.0.1"
    PG_PORT: int = 5432
    PG_USER: str = "postgres"
    PG_PASSWORD: str = ""
    PG_DATABASE: str = "terminal_kb"


settings = Settings()
