#!/usr/bin/env bash
# 停止 agent-platform 全部服务（含 Nacos / bge-m3）
kill_port() { # $1=端口 $2=名称
  local pids
  pids=$(netstat -ano 2>/dev/null | grep LISTENING | grep -E ":$1 " | awk '{print $5}' | sort -u)
  [ -z "$pids" ] && { echo "  - $2 ($1) 未在运行"; return; }
  for pid in $pids; do
    taskkill //F //PID "$pid" >/dev/null 2>&1 && echo "  ✓ 已停止 $2 ($1, PID $pid)"
  done
}
kill_port 8125 agent-orchestrator
kill_port 8124 aismp-agent
kill_port 8123 aismp-interface
kill_port 8090 aismp-gateway
kill_port 7529 aismp-backend
kill_port 8099 bge-m3
kill_port 8848 Nacos
echo "完成。"
