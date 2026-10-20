#!/usr/bin/env bash
# ============================================================
# aismp-orchestrator（Java · Spring AI Alibaba Graph）本机启动脚本
#
# 2026-10-20：原 Python/LangGraph 版已移除，编排层改为 jar 启动，端口仍是 8125，
# 网关 /api/orchestrator/** 与前端都不需要改。
#
# 本机三件事（都在这里处理，别再手工补）：
#   1. JAVA_HOME 必须是 JDK 21 —— shell 里常年挂着的 17 会让 Spring Boot 4 直接起不来
#   2. unset SERVER_PORT / SERVER__PORT —— 这两个环境变量会劫持 server.port
#   3. 敏感值走环境变量（LLM_API_KEY / ORCH_SVC_USER / ORCH_SVC_PASSWORD）
#
# 先构建：cd aismp-orchestrator && mvn -B -DskipTests package
# ============================================================
set -uo pipefail

BASE="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$BASE/aismp-orchestrator/target/aismp-orchestrator-0.0.1-SNAPSHOT.jar"

export JAVA_HOME="${NEXUSFLOW_JDK21:-D:/env/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
unset SERVER_PORT SERVER__PORT

[ -f "$JAR" ] || { echo "ERROR: 未找到 jar：$JAR（先 mvn -B -DskipTests package）" >&2; exit 1; }

echo "启动 aismp-orchestrator (端口 8125) ..."
echo "  JAVA_HOME=${JAVA_HOME}"
echo "  内核地址=${DIGITAL_HUMAN_DIRECT_URL:-http://127.0.0.1:8126}"
exec java -Xms128m -Xmx384m -jar "$JAR" --server.port=8125
