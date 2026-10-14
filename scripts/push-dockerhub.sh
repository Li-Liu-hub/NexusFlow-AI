#!/bin/bash
# ============================================================================
# push-dockerhub.sh —— 构建并推送 5 个应用镜像到 Docker Hub
#
# 这是对 deploy/server-docker/build-and-push.sh 的**薄封装**（多一层便利性），
# 真正的构建逻辑复用那份已验证的脚本，避免两份实现漂移。
#
# 本脚本额外提供：
#   · 默认参数（账号 jsyl6 / tag=日期 / jars 目录）
#   · 代理设置（国内直连 Docker Hub 常失败）
#   · 无参数可跑（build-and-push.sh 必须显式给 REGISTRY）
#   · 远端构建模式（本机 Docker Desktop 起不来时的兜底）
#
# 用法：
#   bash scripts/push-dockerhub.sh                          # 默认 tag = 今天
#   bash scripts/push-dockerhub.sh --tag 20261011
#   bash scripts/push-dockerhub.sh --proxy http://127.0.0.1:7890
#   bash scripts/push-dockerhub.sh --no-push                # 只构建
#   bash scripts/push-dockerhub.sh --server                 # 在远端服务器上构建
#
# 环境变量：
#   DOCKER_USER    Docker Hub 账号（默认 jsyl6）
#   DOCKER_TAG     镜像 tag（默认日期 YYYYMMDD）
#   DOCKER_TOKEN   有则免交互登录（CI 用）
#   HTTP_PROXY     代理地址
#   SERVER         远端服务器 IP（--server 模式用，默认 49.234.49.192）
# ============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE="$(cd "$HERE/.." && pwd)"
DEPLOY="$BASE/deploy/server-docker"
REAL="$DEPLOY/build-and-push.sh"

DOCKER_USER="${DOCKER_USER:-jsyl6}"
DOCKER_TAG="${DOCKER_TAG:-$(date +%Y%m%d)}"
JARS_DIR="${JARS_DIR:-$DEPLOY/jars}"
PROXY="${HTTP_PROXY:-}"
DO_PUSH=1
ON_SERVER=0
SERVER="${SERVER:-49.234.49.192}"

while [ $# -gt 0 ]; do
  case "$1" in
    --tag)     DOCKER_TAG="$2"; shift 2 ;;
    --user)    DOCKER_USER="$2"; shift 2 ;;
    --proxy)   PROXY="$2"; shift 2 ;;
    --jars)    JARS_DIR="$2"; shift 2 ;;
    --server)  ON_SERVER=1; shift ;;
    --no-push) DO_PUSH=0; shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

info(){ printf '\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){   printf '\033[1;32m    + %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
die(){  printf '\033[1;31m    ! %s\033[0m\n' "$*" >&2; exit 1; }

[ -f "$REAL" ] || die "找不到底层构建脚本：$REAL"

info "配置"
echo "    仓库账号 : $DOCKER_USER"
echo "    镜像 tag : $DOCKER_TAG"
echo "    jar 目录 : $JARS_DIR"
echo "    代理     : ${PROXY:-（未设置）}"
echo "    构建位置 : $([ $ON_SERVER -eq 1 ] && echo "远端服务器 $SERVER" || echo '本机')"
echo "    推送     : $([ $DO_PUSH -eq 1 ] && echo '是' || echo '否（仅构建）')"

# ---------- 远端构建模式 ----------
if [ $ON_SERVER -eq 1 ]; then
  # 本机 Docker Desktop 常因安全软件起不来（进程立即消失）；
  # 此时把脚本传到服务器执行 —— 服务器上本来就有 jar 与 Docker。
  warn "远端构建模式：jar 从服务器 /opt/app 读取"
  UPLOAD="D:/env/ssh_upload.py"
  RUNNER="D:/env/ssh_run_script.py"
  PY="C:/Users/20886/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
  [ -f "$PY" ] || PY="python"
  [ -f "$UPLOAD" ] || die "找不到 $UPLOAD（SSH 上传助手）"
  [ -f "$RUNNER" ] || die "找不到 $RUNNER（SSH 执行助手）"

  info "上传构建脚本到服务器 /tmp"
  # ⚠️ 必须加 MSYS_NO_PATHCONV=1，否则 Git Bash 会把远端路径当本地路径转换，报 No such file
  MSYS_NO_PATHCONV=1 "$PY" "$UPLOAD" "$REAL" /tmp/build-and-push.sh || die "上传失败"
  ok "上传完成"

  info "在服务器上构建并推送"
  CMD="REGISTRY=$DOCKER_USER TAG=$DOCKER_TAG JARS_DIR=/opt/app LOGIN=1 bash /tmp/build-and-push.sh"
  [ $DO_PUSH -eq 0 ] && CMD="$CMD --no-push"
  "$PY" "$RUNNER" "$CMD" || die "远端构建失败"
  ok "远端构建推送完成"
  exit 0
fi

# ---------- 本机构建 ----------
command -v docker >/dev/null 2>&1 || die "本机没有 docker。若 Docker Desktop 起不来，改用 --server 模式"
docker info >/dev/null 2>&1 || die "Docker 未运行。若 Docker Desktop 起不来，改用 --server 模式"
[ -d "$JARS_DIR" ] || die "缺少 jar 目录：$JARS_DIR（先跑 deploy/server-docker/collect-artifacts.sh）"

if [ -n "$PROXY" ]; then
  export HTTP_PROXY="$PROXY" HTTPS_PROXY="$PROXY" NO_PROXY="localhost,127.0.0.1"
  ok "已设置代理：$PROXY"
fi

# 登录：build-and-push.sh 通过 LOGIN=1 触发；有 token 则先免交互登录
if [ $DO_PUSH -eq 1 ] && [ -n "${DOCKER_TOKEN:-}" ]; then
  info "用 DOCKER_TOKEN 免交互登录"
  echo "$DOCKER_TOKEN" | docker login -u "$DOCKER_USER" --password-stdin || die "docker login 失败"
  ok "登录成功"
fi

export REGISTRY="$DOCKER_USER"
export TAG="$DOCKER_TAG"
export JARS_DIR
[ $DO_PUSH -eq 1 ] && { [ -z "${DOCKER_TOKEN:-}" ] && export LOGIN=1; }

info "调用底层构建脚本（$REAL）"
EXTRA=()
[ $DO_PUSH -eq 0 ] && EXTRA+=(--no-push)
bash "$REAL" "${EXTRA[@]}"
RC=$?

echo ""
if [ $RC -eq 0 ]; then
  ok "全部镜像处理完成（tag=$DOCKER_TAG）"
  echo ""
  echo "    服务器更新："
  echo "      bash scripts/deploy.sh --apps-only"
else
  warn "构建/推送存在失败项（退出码 $RC），请看上方日志"
fi
exit $RC
