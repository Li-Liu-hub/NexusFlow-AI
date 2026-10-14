#!/usr/bin/env bash
# 构建 agent-platform 的 Java 侧（aismp-backend 系列）
# ⚠️ 必须用 JDK 21：aismp-backend / aismp-gateway 的 pom 是 <java.version>21</java.version>，
#    用 JDK 8 会 fail-fast 报「无效的目标发行版：21」（实测）。
#    （aismp-interface 是 JDK 8，但它已不部署，见 deploy/server-docker/Dockerfile.jdk21 注释）
# 顺序：common/sdk 先 install 到本地仓库，backend/gateway/interface 才能解析依赖
set -uo pipefail
export JAVA_HOME=/d/env/jdk-21
export PATH="/d/env/jdk-21/bin:$PATH"
MVN="/d/Office software/mainfile/apache-maven-3.9.9-bin/apache-maven-3.9.9/bin/mvn"
BASE="D:/亚信科技/学习日志/比赛/agent-platform/aismp-backend"
FAIL=0

step() {
  local name="$1" dir="$2" goals="$3"
  echo ""
  echo "########## $name ##########"
  cd "$dir" || { echo ">>> 目录不存在: $dir"; FAIL=1; return 1; }
  "$MVN" $goals -B 2>&1 | tail -30
  local rc=${PIPESTATUS[0]}
  echo ">>> $name exit=$rc"
  [ "$rc" -ne 0 ] && FAIL=1
  return $rc
}

step "1/5 aismp-common (install)"     "$BASE/aismp-common"     "clean install -DskipTests"
step "2/5 aismp-client-sdk (install)" "$BASE/aismp-client-sdk" "clean install -DskipTests"
step "3/5 aismp-backend (package)"    "$BASE"                  "clean package -DskipTests"
step "4/5 aismp-gateway (package)"    "$BASE/aismp-gateway"    "clean package -DskipTests"
step "5/5 aismp-interface (package)"  "$BASE/aismp-interface"  "clean package -DskipTests"

echo ""
echo "########## 产物 ##########"
ls -lh "$BASE/target/"*.jar "$BASE/aismp-gateway/target/"*.jar "$BASE/aismp-interface/target/"*.jar 2>/dev/null
echo "FAIL=$FAIL"
echo "BUILD_ALL_DONE"
