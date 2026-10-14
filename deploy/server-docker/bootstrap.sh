#!/bin/bash
# ============================================================================
# 服务器一键部署（在新装 Ubuntu 上执行）
# 流程：装 Docker → 准备 .env/jars/前端 → docker compose up → 导入数据 → 健康检查
#
# 用法：
#   1. 把整个 server-docker/ 目录 + jars/ + web/ 上传到服务器（bootstrap 会提示缺什么）
#   2. 配置 .env
#   3. bash bootstrap.sh
# ============================================================================
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

echo "══ [1/6] 安装 Docker（官方源，含 compose 插件）══"
if command -v docker >/dev/null 2>&1; then
  echo "  Docker 已安装：$(docker --version)"
else
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker
  echo "  已安装：$(docker --version)"
fi
docker compose version >/dev/null 2>&1 || { echo "✘ compose 插件缺失"; exit 1; }

echo "══ [2/6] 校验 .env ══"
[ -f .env ] || { echo "✘ 缺少 .env（从 .env.example 复制填写）"; exit 1; }
set -a; . ./.env; set +a
for v in MYSQL_ROOT_PW MYSQL_APP_USER MYSQL_APP_PW PG_PW REDIS_PW RABBIT_PW MIMO_API_KEY; do
  [ -n "${!v:-}" ] || { echo "✘ .env 缺少 $v"; exit 1; }
done
echo "  .env 必填项齐全"

echo "══ [3/6] 校验构建产物 ══"
MISS=""
for j in aismp-backend.jar aismp-gateway.jar aismp-agent.jar aismp-digital-human.jar; do
  [ -f "jars/$j" ] || MISS="$MISS $j"
done
[ -d orchestrator-image/agent-orchestrator ] || MISS="$MISS agent-orchestrator/"
[ -d web/portal ] || MISS="$MISS web/portal/"
[ -f .env ] || true
if [ -n "$MISS" ]; then
  echo "✘ 缺少:$MISS"
  echo "  本机执行 deploy/server-docker/collect-artifacts.sh 生成后，随目录一起上传"
  exit 1
fi
echo "  jars / 源码 / 前端产物齐全"

echo "══ [4/6] 启动中间件层 ══"
docker compose up -d mysql postgres redis rabbitmq nacos
echo "  等待健康检查..."
for i in $(seq 1 30); do
  ok=$(docker compose ps --format json 2>/dev/null | grep -c '"healthy"' || true)
  [ "$ok" -ge 5 ] && { echo "  中间件全部 healthy"; break; }
  sleep 5
done

echo "══ [5/6] 构建并启动应用层 + nginx ══"
docker compose build
docker compose up -d
sleep 20

echo "══ [6/6] 健康检查 ══"
for c in ap-mysql ap-postgres ap-redis ap-rabbitmq ap-nacos ap-backend ap-gateway ap-agent ap-digital-human ap-nginx; do
  st=$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null || echo missing)
  printf "  %-18s %s\n" "$c" "$st"
done
echo ""
echo "入口：http://<服务器IP>/  业务门户    http://<服务器IP>/admin/  管理台"
echo "如需导入旧数据：bash migrate/import-to-docker.sh"
