#!/usr/bin/env bash
# ============================================================
# start-all.sh —— 本机一键启动全部服务（按依赖顺序）
#
# 用法：
#   bash scripts/start-all.sh              # 启动
#   bash scripts/start-all.sh --stop       # 停止全部
#   bash scripts/start-all.sh --status     # 只看端口状态
#   bash scripts/start-all.sh --no-verify  # 启动但不跑端到端验证
#
# 前置（本机常驻服务，需自行在跑）：
#   MySQL 3306 / PostgreSQL 5432 / Redis 6379 / RabbitMQ 5672
#   （本机这些是原生服务，不用 Docker —— Docker Desktop 在奇安信环境下起不来）
#
# 日志：local-init/logs/*.log
#
# 与旧版 local-init/start-all.sh 的差异（已修正的问题）：
#   ① 不再启动 aismp-interface（8123）—— Mock 服务，已不部署
#   ② 补上 aismp-digital-human（8126）—— 知识问答路线依赖它，旧版漏了
#   ③ 正确透传额外参数给 java
#   ④ 端到端验证失败不会中断（旧版会让整个脚本报错退出）
# ============================================================
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE="$(cd "$HERE/.." && pwd)"
INIT="$BASE/local-init"
LOGS="$INIT/logs"
mkdir -p "$LOGS"

# ★ 本机进程环境里有 SERVER__PORT=53176，Spring Boot 会把它当 server.port 覆盖配置文件。
#   必须清掉，否则所有 Java 服务都会挤到同一个端口上。
unset SERVER_PORT SERVER__PORT

MODE="start"
VERIFY=1
for a in "$@"; do
  case "$a" in
    --stop)      MODE="stop" ;;
    --status)    MODE="status" ;;
    --no-verify) VERIFY=0 ;;
    -h|--help)   sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "未知参数：$a" >&2; exit 2 ;;
  esac
done

info(){ printf '\033[1;36m%s\033[0m\n' "$*"; }
ok(){   printf '  \033[1;32m✅ %s\033[0m\n' "$*"; }
warn(){ printf '  \033[1;33m⚠️  %s\033[0m\n' "$*"; }
die(){  printf '\033[1;31m❌ %s\033[0m\n' "$*" >&2; exit 1; }

listen() { netstat -ano 2>/dev/null | grep -qE ":$1 .*LISTENING"; }

# ---------- 状态 ----------
if [ "$MODE" = "status" ]; then
  info "服务端口状态"
  for pair in "3306:MySQL" "5432:PostgreSQL" "6379:Redis" "5672:RabbitMQ" \
              "8848:Nacos" "8099:bge-m3" "7529:aismp-backend" "8090:aismp-gateway" \
              "8124:aismp-agent" "8126:aismp-digital-human" "8125:agent-orchestrator"; do
    p="${pair%%:*}"; n="${pair##*:}"
    if listen "$p"; then ok "$n ($p)"; else warn "$n ($p) 未监听"; fi
  done
  exit 0
fi

# ---------- 停止 ----------
if [ "$MODE" = "stop" ]; then
  if [ -x "$INIT/stop-all.sh" ]; then
    info "调用 local-init/stop-all.sh"
    bash "$INIT/stop-all.sh"
  else
    warn "没有 $INIT/stop-all.sh，改为按端口杀进程"
    for p in 7529 8090 8124 8126 8125; do
      pid="$(netstat -ano 2>/dev/null | grep -E ":$p .*LISTENING" | awk '{print $5}' | head -1)"
      [ -n "$pid" ] && { taskkill //PID "$pid" //F >/dev/null 2>&1 && ok "已停止端口 $p (PID $pid)"; }
    done
  fi
  exit 0
fi

# ---------- 启动 ----------
wait_port() {  # $1=端口 $2=名称 $3=超时秒
  local p="$1" n="$2" t="${3:-90}" i=0
  while [ $i -lt "$t" ]; do
    listen "$p" && { ok "$n ($p) 已就绪"; return 0; }
    sleep 1; i=$((i+1))
  done
  warn "$n ($p) 等待超时（${t}s）—— 看 $LOGS 下对应日志"
  return 1
}

# 若端口已监听则跳过启动（幂等）
start_if_down() {  # $1=端口 $2=名称 $3=日志名  $4..=启动命令
  local p="$1" n="$2" log="$3"; shift 3
  if listen "$p"; then
    ok "$n ($p) 已在运行，跳过"
  else
    info "启动 $n ..."
    nohup "$@" >"$LOGS/$log.log" 2>&1 &
  fi
}

info "═══ 前置检查 ═══"
for pair in "3306:MySQL" "5432:PostgreSQL" "6379:Redis" "5672:RabbitMQ"; do
  p="${pair%%:*}"; n="${pair##*:}"
  listen "$p" && ok "$n ($p) 在跑" || warn "$n ($p) 未监听 —— 请先启动本机原生服务"
done

echo ""
info "═══ 按依赖顺序启动 ═══"

# 1) Nacos（注册中心，backend/gateway/agent 都依赖）
start_if_down 8848 Nacos nacos "$INIT/start-services.sh" nacos
wait_port 8848 Nacos 120

# 2) bge-m3 本地嵌入服务（agent 的知识库与向量检索依赖）
#    脚本在 D:/env（local-init 下没有），历史曾因路径错而启动失败
if listen 8099; then
  ok "bge-m3 (8099) 已在运行"
elif [ -f /d/env/start-bge-m3-8099.sh ]; then
  info "启动 bge-m3 ..."
  nohup bash /d/env/start-bge-m3-8099.sh >"$LOGS/bge-m3.log" 2>&1 &
else
  warn "找不到 /d/env/start-bge-m3-8099.sh，跳过嵌入服务"
fi
wait_port 8099 bge-m3 90

# 3) backend（管理后端）
start_if_down 7529 aismp-backend backend "$INIT/start-services.sh" backend
wait_port 7529 aismp-backend 120

# 4) gateway（统一入口，必须在 backend 之后）
start_if_down 8090 aismp-gateway gateway "$INIT/start-services.sh" gateway
wait_port 8090 aismp-gateway 120

# 5) aismp-agent（终端业务 + RAG）
if [ -z "${MIMO_API_KEY:-}" ]; then
  warn "缺 MIMO_API_KEY，aismp-agent 会启动失败。请先 setx MIMO_API_KEY <key> 并重开终端"
fi
start_if_down 8124 aismp-agent agent "$INIT/start-services.sh" agent
wait_port 8124 aismp-agent 120

# 6) digital-human（知识问答路线依赖；旧版 start-all 漏了它）
start_if_down 8126 aismp-digital-human digital-human "$INIT/start-services.sh" digital-human
wait_port 8126 aismp-digital-human 120

# 7) agent-orchestrator（编排层）
start_if_down 8125 agent-orchestrator orchestrator "$INIT/start-orchestrator.sh"
wait_port 8125 agent-orchestrator 90

echo ""
info "═══ 汇总 ═══"
for pair in "8848:Nacos" "8099:bge-m3" "7529:backend" "8090:gateway" "8124:agent" "8126:digital-human" "8125:orchestrator"; do
  p="${pair%%:*}"; n="${pair##*:}"
  listen "$p" && ok "$n" || warn "$n 未起"
done

echo ""
echo "    日志目录：$LOGS"
echo "    状态查看：bash scripts/start-all.sh --status"
echo "    停止    ：bash scripts/start-all.sh --stop"

# ---------- 端到端验证 ----------
if [ "$VERIFY" -eq 1 ] && [ -f "$INIT/e2e-test.mjs" ]; then
  echo ""
  info "═══ 端到端验证 ═══"
  if (cd "$INIT" && node e2e-test.mjs); then
    ok "端到端验证通过"
  else
    warn "端到端验证未通过（不影响服务运行，请按上方输出排查）"
  fi
fi
