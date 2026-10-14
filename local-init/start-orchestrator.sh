#!/usr/bin/env bash
# ============================================================
# agent-orchestrator (Python / LangGraph) 启动脚本
#
# 注意：uv 的 venv 建在项目目录下（路径含中文）时，venv 内的 python 启动器
# 读不到自己的 pyvenv.cfg（报 `Cannot read 'D:\????\...'`），所以本项目把
# venv 放到 ASCII 路径 D:\env\venvs\agent-orchestrator（UV_PROJECT_ENVIRONMENT）。
#   重建：cd agent-orchestrator && UV_PROJECT_ENVIRONMENT='D:\env\venvs\agent-orchestrator' uv sync
# ============================================================
set -uo pipefail

VENV="D:/env/venvs/agent-orchestrator"
BASE="D:/亚信科技/学习日志/比赛/agent-platform/agent-orchestrator"

[ -x "$VENV/Scripts/python.exe" ] || { echo "ERROR: 未找到 venv：$VENV（先跑 uv sync）" >&2; exit 1; }

cd "$BASE" || exit 1
echo "启动 agent-orchestrator (端口 8125) ..."
exec "$VENV/Scripts/python.exe" -m uvicorn server:app --host 127.0.0.1 --port 8125
