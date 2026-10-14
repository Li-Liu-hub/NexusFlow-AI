#!/usr/bin/env bash
# ============================================================
# 用户端（Vite + React）启动脚本
#
# 前置：数字员工内核已在 8126 运行（./start-services.sh digital-human 或手动 java -jar）
# 访问：http://127.0.0.1:5173
#
# 说明：Vite 开发服务器通过 proxy 把 /api 转发到 127.0.0.1:8126，
#       所以浏览器侧不存在跨域问题，前端代码也不需要区分环境。
# ============================================================
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BASE="$(cd "$HERE/.." && pwd)"
APP="$BASE/aismp-frontend-user"
LOGS="$HERE/logs"
mkdir -p "$LOGS"

if [ ! -d "$APP/node_modules" ]; then
  echo "首次运行，先安装依赖 ..."
  (cd "$APP" && npm install --no-fund --no-audit) || { echo "npm install 失败"; exit 1; }
fi

# 数字员工内核是唯一上游，先确认它在
if ! curl -s -o /dev/null --max-time 3 --noproxy '*' http://127.0.0.1:8126/health; then
  echo "⚠️  数字员工内核(8126)似乎未启动 —— 界面能打开但提问会失败"
  echo "    启动：unset SERVER_PORT SERVER__PORT && \\"
  echo "          java -jar aismp-digital-human/target/aismp-digital-human-0.0.1-SNAPSHOT.jar --server.port=8126"
fi

echo "启动用户端 http://127.0.0.1:5173 ..."
cd "$APP"
exec npm run dev
