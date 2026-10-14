#!/usr/bin/env bash
# ============================================================
# 管理端（数字员工运维台）启动脚本
#
# 前置：数字员工内核已在 8126 运行
# 访问：http://127.0.0.1:5174
#
# 两个本机特有的坑，这里都已处理：
#   1. esbuild 在中文路径下会被安全软件拦截（fatal error: winmm.dll not found），
#      且**不同版本表现不同**（0.28.x 侥幸能跑、0.21.x 必挂）—— 不能靠"试一下能跑"下结论。
#      解法：把 exe 放到 ASCII 路径，用 ESBUILD_BINARY_PATH 指过去。
#   2. npm 在本机不可靠（卡死 / 静默跳过 optional 依赖 / 中断后留下空壳包）。
#      解法：一律用 npmmirror 镜像；装完务必校验 package.json 是否存在。
# ============================================================
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
BASE="$(cd "$HERE/.." && pwd)"
APP="$BASE/aismp-frontend-admin"

# ---- ① esbuild 绕行 ----
mkdir -p /d/env/esbuild
if [ -f "$APP/node_modules/@esbuild/win32-x64/esbuild.exe" ]; then
  cp -f "$APP/node_modules/@esbuild/win32-x64/esbuild.exe" /d/env/esbuild/esbuild.exe 2>/dev/null || true
fi
export ESBUILD_BINARY_PATH='D:\env\esbuild\esbuild.exe'

# ---- ② 依赖安装（镜像源）----
if [ ! -f "$APP/node_modules/@vitejs/plugin-react/package.json" ]; then
  echo "首次运行，安装依赖（npmmirror 镜像）..."
  (cd "$APP" && npm install --registry=https://registry.npmmirror.com --no-fund --no-audit) \
    || { echo "npm install 失败"; exit 1; }
fi

# ---- ③ 校验关键依赖（npm 可能装出空壳包：目录在但无 package.json）----
MISSING=""
for p in react react-dom antd vite @vitejs/plugin-react rollup @emotion/hash @emotion/unitless; do
  [ -f "$APP/node_modules/$p/package.json" ] || MISSING="$MISSING $p"
done
if [ -n "$MISSING" ]; then
  echo "⚠️  缺失依赖:$MISSING"
  echo "    补装： cd $APP && npm install$MISSING --registry=https://registry.npmmirror.com"
  exit 1
fi

# ---- ④ 上游检查 ----
if ! curl -s -o /dev/null --max-time 3 --noproxy '*' http://127.0.0.1:8126/health; then
  echo "⚠️  数字员工内核(8126)未启动 —— 界面能打开但所有配置接口都会失败"
fi

echo "启动管理端 http://127.0.0.1:5174 ..."
cd "$APP"
exec node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5174
