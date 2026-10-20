#!/bin/bash
# ============================================================================
# 在「本机」执行：收集部署所需产物到 deploy/server-docker/
# 产物：jars/（6 个 jar）、web/（前端构建产物）
# 全程用绝对路径定位 jar（首版在 cd 之后用相对路径，多层目录下必错）
# ============================================================================
set -euo pipefail
BASE="$(cd "$(dirname "$0")/../.." && pwd)"      # agent-platform/
DEST="$BASE/deploy/server-docker"
# 2026-10-10 修正：backend/gateway 的 pom 里也是 <java.version>21</java.version>，
#   原版用 JDK8 构建是错的（只有 aismp-interface 声明 8，而 JDK21 能 --release 8 编它）。
JDK21=/d/env/jdk-21
mkdir -p "$DEST/jars" "$DEST/web"

echo "══ [1/4] 构建 backend 聚合工程（backend / gateway / interface，全部 JDK21）══"
(cd "$BASE/aismp-backend" && JAVA_HOME="$JDK21" mvn -B clean package -DskipTests -q)
cp "$BASE/aismp-backend/target/aismp-backend-0.0.1-SNAPSHOT.jar"       "$DEST/jars/aismp-backend.jar"
cp "$BASE/aismp-backend/aismp-gateway/target/aismp-gateway-0.0.1-SNAPSHOT.jar"     "$DEST/jars/aismp-gateway.jar"

echo "══ [2/4] 构建 JDK21 三个（agent / digital-human / orchestrator）══"
(cd "$BASE/aismp-agent" && JAVA_HOME="$JDK21" mvn -B clean package -DskipTests -q)
cp "$BASE/aismp-agent/target/aismp-agent-0.0.1-SNAPSHOT.jar" "$DEST/jars/aismp-agent.jar"
(cd "$BASE/aismp-digital-human" && JAVA_HOME="$JDK21" mvn -B clean package -DskipTests -q)
cp "$BASE/aismp-digital-human/target/aismp-digital-human-0.0.1-SNAPSHOT.jar" "$DEST/jars/aismp-digital-human.jar"
# 编排层：原 Python 版已移除，现在是同版本栈的 Java 模块（Spring AI Alibaba Graph）
(cd "$BASE/aismp-orchestrator" && JAVA_HOME="$JDK21" mvn -B clean package -DskipTests -q)
cp "$BASE/aismp-orchestrator/target/aismp-orchestrator-0.0.1-SNAPSHOT.jar" "$DEST/jars/aismp-orchestrator.jar"

echo "══ [3/4] 前端构建产物 ══"
# 门户脚本：public/portal.js 是 src/portal/portal.ts 的构建产物（已 gitignore），
# 所以这里必须先构建一次，否则拷走的是旧文件或根本没有文件。
cd "$BASE/aismp-frontend-user"
if [ ! -d node_modules ]; then
  npm install --registry=https://registry.npmmirror.com --no-fund --no-audit
fi
export ESBUILD_BINARY_PATH='D://env//esbuild//esbuild-admin.exe'
npx vite build --config vite.portal.config.ts
cd "$BASE"
# portal（单文件：样式与脚本全部内联，无外部资源缓存问题）
rm -rf "$DEST/web/portal" && mkdir -p "$DEST/web/portal"
cp "$BASE/aismp-frontend-user/public/portal.html" "$DEST/web/portal/portal.html"
# 同目录的 portal.css / portal.js 也要一起带走（曾漏拷导致门户无样式）
for f in portal.css portal.js; do
  [ -f "$BASE/aismp-frontend-user/public/$f" ] && \
    cp "$BASE/aismp-frontend-user/public/$f" "$DEST/web/portal/$f"
done
# admin（Vite build，base 必须是 /admin/ —— 与 nginx 的 location /admin/ 对齐）
# ⚠️ 这里曾写成 --base=/admin-ui/，与 nginx 实际路由不符，导致管理端白屏：
#    产物引用 /admin-ui/assets/*.js，而 nginx 只服务 /admin/，SPA fallback 把 index.html 当 JS 返回，
#    表现为「接口 200 但页面全白」。vite.config.js 已固化 base，此处保持一致，勿再改回。
cd "$BASE/aismp-frontend-admin"
if [ ! -f node_modules/@vitejs/plugin-react/package.json ]; then
  npm install --registry=https://registry.npmmirror.com --no-fund --no-audit
fi
export ESBUILD_BINARY_PATH='D:\env\esbuild\esbuild-admin.exe'
# ⚠️ 上面这份替身必须与 aismp-frontend-admin/node_modules/esbuild 的版本【一致】（当前 0.21.5）。
#    曾误指向 D:\env\esbuild\esbuild.exe（0.28.2），报
#    "Cannot start service: Host version 0.21.5 does not match binary version 0.28.2" 直接构建失败。
#    换前端依赖版本时，需同步：从 node_modules/@esbuild/win32-x64/esbuild.exe 拷一份到 ASCII 路径。
# ⚠️ MSYS_NO_PATHCONV=1 必须加：Git Bash 会把 --base=/admin/ 当成 Unix 路径转成
#    /WorkBuddy/resources/vendor/PortableGit/admin/，产物引用错路径，管理台白屏
#    （现象：控制台报 "base option should start with a slash"，index.html 里出现 PortableGit 字样）。
MSYS_NO_PATHCONV=1 npx vite build --base=/admin/ --outDir "$DEST/web/admin" --emptyOutDir
# 构建后自检：产物必须引用 /admin/assets/，否则直接失败（防止白屏产物静默上线）
if ! grep -q 'src="/admin/assets/' "$DEST/web/admin/index.html"; then
  echo "✗ 管理台产物 base 不正确，index.html 实际内容：" >&2
  head -12 "$DEST/web/admin/index.html" >&2
  exit 1
fi
echo "✓ 管理台产物 base 已验证为 /admin/"
echo "══ [4/4] 产物清单校验 ══"
# 缺少任何一个 jar 都直接失败：以前是「静默少一个 jar，部署后某个服务起不来」
for j in aismp-backend aismp-gateway aismp-agent aismp-digital-human aismp-orchestrator; do
  [ -f "$DEST/jars/$j.jar" ] || { echo "✗ 缺 jar：$DEST/jars/$j.jar" >&2; exit 1; }
done
[ -f "$DEST/web/portal/portal.js" ] || { echo "✗ 缺门户脚本：$DEST/web/portal/portal.js" >&2; exit 1; }
echo "✓ 6 个 jar + 门户脚本齐全"

echo ""
echo "产物就绪："
du -sh "$DEST/jars" "$DEST/web"
# 打包（在原目录的上一级执行，故 tar 的 -C 指向 deploy 的父目录）
echo "→ 打包上传：tar czf server-docker.tgz -C \"$(dirname "$DEST")\" server-docker && 上传解压"
