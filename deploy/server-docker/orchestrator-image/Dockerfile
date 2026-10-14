# ============================================================================
# 编排层镜像：Python + LangGraph（源码整体拷入，uv 装依赖）
#
# 2026-10-10 修正：
#   1. 监听地址改为可用环境变量控制，默认 127.0.0.1（保持「服务器只对外暴露 8000」的加固，
#      原版硬编码 --host 0.0.0.0 会把 8125 直接暴露到公网，是安全回退）
#   2. 加 MALLOC_ARENA_MAX=2 —— 实测这台机器上编排层 471MB 里约 350MB 是 glibc arena 碎片
#      （3 个线程却有 6 个 40~87MB 匿名映射），加完掉到 88MB
#
# 构建：docker build -f orchestrator-image/Dockerfile -t <仓库>/ap-orchestrator:<tag> orchestrator-image
# 构建上下文 orchestrator-image/ 里必须有 agent-orchestrator/（collect-artifacts.sh 会拷）
# ============================================================================
FROM python:3.12-slim

# 2026-10-11：清华 PyPI 镜像抽风（pip install uv 报 from versions: none），改用阿里云源
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ \
    UV_DEFAULT_INDEX=https://mirrors.aliyun.com/pypi/simple/ \
    MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=65536 \
    ORCH_HOST=127.0.0.1 \
    ORCH_PORT=8125

WORKDIR /app

# 先拷依赖清单以复用层缓存
COPY agent-orchestrator/pyproject.toml agent-orchestrator/uv.lock ./
RUN pip install --no-cache-dir uv && uv sync --frozen --no-dev

# 再拷源码
COPY agent-orchestrator/ ./

RUN useradd -r -u 1001 appuser && chown -R appuser /app
USER appuser

EXPOSE 8125
ENTRYPOINT ["sh", "-c", "exec .venv/bin/python -m uvicorn server:app --host $ORCH_HOST --port $ORCH_PORT \"$@\"", "--"]
