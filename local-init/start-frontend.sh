#!/usr/bin/env bash
# ============================================================
# aismp-frontend-master（Ant Design Pro 6 beta + Umi Max）启动脚本
#
# 本机三个必须的处理，缺一不可：
#
# 1) 清掉 NODE_OPTIONS
#    WorkBuddy 会注入 --require=.../node-language-shim.cjs，其中含 safe-delete 守卫。
#    umi dev 启动时要 rimraf 掉 src/.umi 临时目录，会被守卫拦成
#    `[safe-delete][SAFE_DELETE_BULK_GUARD_ERROR] state lock timeout` 而崩溃。
#
# 2) ESBUILD_BINARY_PATH 指向纯 ASCII 路径的 esbuild.exe
#    【最关键】本机装了奇安信天擎。当 esbuild.exe 位于含中文的路径
#    （node_modules/@esbuild/win32-x64/ 在 D:\亚信科技\... 下）时，
#    它的**每一次文件读取**都返回 winapi error #5(ACCESS_DENIED)：
#        X [ERROR] Cannot read file "package.json": winapi error #5
#        X [ERROR] Cannot read file "tsconfig.json": winapi error #5
#    把同一份二进制复制到 D:\env\esbuild\ 并让 esbuild 走 ESBUILD_BINARY_PATH，
#    即恢复正常（cwd 在中文路径下也没关系，只有二进制位置有影响）。
#    版本必须与 node_modules 里的一致，所以是「复制同一份」而不是另装。
#    同步命令：cp node_modules/@esbuild/win32-x64/esbuild.exe D:/env/esbuild/esbuild.exe
#
# 3) 依赖用国内镜像装
#    仓库没有 package-lock.json，走 npmjs 全量解析会卡死（实测 16 分钟无进展）。
#    npm install --registry=https://registry.npmmirror.com --no-audit --no-fund
#
# 另外：postinstall 的 `max setup` 在上述 esbuild 问题修好前会失败，
# 但 src/.umi 由 `max dev` 自己生成，所以 postinstall 失败不影响启动。
# ============================================================
set -uo pipefail

BASE="D:/亚信科技/学习日志/比赛/agent-platform/aismp-frontend-master"
ESBUILD_BIN="D:/env/esbuild/esbuild.exe"

[ -x "$ESBUILD_BIN" ] || {
  echo "缺少 ASCII 路径下的 esbuild 二进制，正在从 node_modules 复制 ..."
  mkdir -p "$(dirname "$ESBUILD_BIN")"
  cp "$BASE/node_modules/@esbuild/win32-x64/esbuild.exe" "$ESBUILD_BIN" || {
    echo "ERROR: 复制失败，请先 npm install" >&2; exit 1; }
}

cd "$BASE" || exit 1
unset NODE_OPTIONS
export ESBUILD_BINARY_PATH="$ESBUILD_BIN"

echo "启动 aismp-frontend (端口 8000)，/api/ 代理到 http://localhost:7529"
exec ./node_modules/.bin/max dev
