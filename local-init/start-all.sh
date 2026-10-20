#!/usr/bin/env bash
# ============================================================
# agent-platform 一键启动（按依赖顺序拉起全部服务）
#
# 前置（本机服务，需自行在跑）：MySQL 3306 / PostgreSQL 5432 / Redis 6379 / RabbitMQ 5672
# 日志统一写到 local-init/logs/*.log
# 停止： ./stop-all.sh
# ============================================================
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LOGS="$HERE/logs"
mkdir -p "$LOGS"

# 本机进程环境里存在 SERVER__PORT=53176，会把 Spring Boot 的端口劫持走
unset SERVER_PORT SERVER__PORT

wait_port() { # $1=端口 $2=名称 $3=超时秒
  local p="$1" n="$2" t="${3:-90}" i=0
  while [ $i -lt "$t" ]; do
    netstat -ano | grep -q "LISTENING" && netstat -ano | grep -qE ":$p .*LISTENING" && { echo "  ✅ $n ($p) 已就绪"; return 0; }
    sleep 1; i=$((i+1))
  done
  echo "  ⚠️  $n ($p) 等待超时"; return 1
}

echo "[1/8] Nacos"
netstat -ano | grep -qE ":8848 .*LISTENING" || { nohup "$HERE/start-services.sh" nacos >"$LOGS/nacos.log" 2>&1 & }
wait_port 8848 Nacos 120

echo "[2/8] bge-m3 嵌入服务"
# 脚本在 D:/env（不在本目录：local-init 下没有 start-bge-m3-8099.sh，曾因此启动失败）
netstat -ano | grep -qE ":8099 .*LISTENING" || { nohup bash /d/env/start-bge-m3-8099.sh >"$LOGS/bge-m3.log" 2>&1 & }
wait_port 8099 "bge-m3" 90

echo "[3/8] aismp-backend"
netstat -ano | grep -qE ":7529 .*LISTENING" || { nohup "$HERE/start-services.sh" backend >"$LOGS/backend.log" 2>&1 & }
wait_port 7529 aismp-backend 120

echo "[4/8] aismp-gateway"
netstat -ano | grep -qE ":8090 .*LISTENING" || { nohup "$HERE/start-services.sh" gateway >"$LOGS/gateway.log" 2>&1 & }
wait_port 8090 aismp-gateway 120

echo "[5/8] aismp-interface"
netstat -ano | grep -qE ":8123 .*LISTENING" || { nohup "$HERE/start-services.sh" interface >"$LOGS/interface.log" 2>&1 & }
wait_port 8123 aismp-interface 90

echo "[6/8] aismp-agent"
netstat -ano | grep -qE ":8124 .*LISTENING" || { nohup "$HERE/start-services.sh" agent >"$LOGS/agent.log" 2>&1 & }
wait_port 8124 aismp-agent 90

echo "[7/8] aismp-orchestrator (Spring AI Alibaba Graph)"
netstat -ano | grep -qE ":8125 .*LISTENING" || { nohup "$HERE/start-orchestrator.sh" >"$LOGS/orchestrator.log" 2>&1 & }
wait_port 8125 aismp-orchestrator 60

echo "[8/8] 端到端验证"
cd "$HERE" && node e2e-test.mjs

echo ""
echo "全部就绪。日志目录：$LOGS"
