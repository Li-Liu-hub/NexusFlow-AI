#!/bin/bash
# ============================================================================
# build-and-push.sh —— 构建 5 个应用镜像并推送到镜像仓库
#
# 为什么要有这个脚本：本机 Docker Desktop 起不来（奇安信天擎拦截，见 mem 记录），
#   所以「构建」这一步目前只能在**服务器**上做 —— 正好 4 个 jar 本来就在服务器
#   /opt/app/*/app.jar，连上传都省了。
#
# 用法 A｜在服务器上构建并推送（推荐，今天就能跑通）
#   sudo -E REGISTRY=ccr.ccs.tencentyun.com/<命名空间> JARS_DIR=/opt/app \
#        TAG=$(date +%Y%m%d) bash build-and-push.sh
#
# 用法 B｜在本机构建并推送（需先 bash collect-artifacts.sh，且本机 Docker 可用）
#   REGISTRY=<仓库> TAG=<标签> bash build-and-push.sh
#
# 用法 C｜只构建不推送
#   ... bash build-and-push.sh --no-push
#
# 环境变量：
#   REGISTRY   必填。镜像仓库前缀（含命名空间），如 registry.example.com/your-namespace
#   TAG        选填。默认取 git 短 SHA，取不到就用日期
#   JARS_DIR   选填。默认脚本同级的 jars/；服务器上填 /opt/app
#   LOGIN=1    选填。设为 1 则脚本内执行 docker login（会交互输入账号密码）
# ============================================================================
set -euo pipefail

PUSH=1
for a in "$@"; do
  case "$a" in
    --no-push) PUSH=0 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "未知参数：$a" >&2; exit 2 ;;
  esac
done

REGISTRY="${REGISTRY:?必须指定 REGISTRY，例如 ccr.ccs.tencentyun.com/my-ns}"
HERE="$(cd "$(dirname "$0")" && pwd)"
JARS_DIR="${JARS_DIR:-$HERE/jars}"

if [ -z "${TAG:-}" ]; then
  # ★ 默认 tag 必须【每次都不同】，否则会踩这个大坑：
  #   `docker compose pull` 默认策略是 `missing` —— 本地已有同名 tag 就跳过，
  #   不比对远端 digest。沿用旧 tag 覆盖推送后，服务器 pull 会报 "up to date"
  #   并继续跑旧镜像，让人误以为更新没生效。
  #   所以默认用「日期-时分秒」，天然唯一；要精确追溯可加 git sha。
  TAG="$(date +%Y%m%d-%H%M%S)"
  GITSHA="$(git -C "$HERE" rev-parse --short HEAD 2>/dev/null || true)"
  [ -n "$GITSHA" ] && TAG="${TAG}-${GITSHA}"
fi

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '\033[1;32m    + %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
die(){ printf '\033[1;31m    ! %s\033[0m\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "找不到 docker。服务器上先装：curl -fsSL https://get.docker.com | sh"
docker info >/dev/null 2>&1 || die "Docker 守护进程没在跑（docker info 失败）"

echo "=============================================================="
echo " 构建并推送应用镜像"
echo "   仓库前缀 REGISTRY = $REGISTRY"
echo "   标签     TAG      = $TAG"
echo "   jar 目录 JARS_DIR = $JARS_DIR"
echo "   推送     PUSH     = $PUSH"
echo "=============================================================="

if [ "${LOGIN:-0}" = "1" ]; then
  # Docker Hub 的 REGISTRY 是「用户名/仓库」而不是「域名/命名空间」，docker login 需要的是域名：
  #   myuser              → docker.io
  #   docker.io/myuser    → docker.io
  #   ccr.ccs.tencentyun.com/ns → ccr.ccs.tencentyun.com
  HOST="$(printf '%s' "$REGISTRY" | cut -d/ -f1)"
  case "$HOST" in *.*) ;; *) HOST="docker.io" ;; esac
  log "docker login $HOST"
  docker login "$HOST"
else
  HOST="$(printf '%s' "$REGISTRY" | cut -d/ -f1)"
  case "$HOST" in *.*) ;; *) HOST="docker.io" ;; esac
  echo "提示：若尚未登录仓库，请先执行  docker login $HOST"
fi

# 兼容两种目录布局：扁平 jars/<名字>.jar  或  服务器 /opt/app/<服务目录>/app.jar
resolve_jar(){
  local name="$1" svcdir="$2" c
  for c in "$JARS_DIR/$name.jar" "$JARS_DIR/$svcdir/app.jar"; do
    if [ -f "$c" ]; then printf '%s' "$c"; return 0; fi
  done
  return 1
}

build_java(){
  local name="$1" svcdir="$2"
  local jar img ctx
  jar="$(resolve_jar "$name" "$svcdir")" || die "找不到 jar：$JARS_DIR/$name.jar 或 $JARS_DIR/$svcdir/app.jar"
  img="$REGISTRY/yp-${3}:$TAG"
  ctx="$(mktemp -d)"
  mkdir -p "$ctx/jars"
  cp "$jar" "$ctx/jars/$name.jar"
  cp "$HERE/Dockerfile.jdk21" "$ctx/Dockerfile.jdk21"
  log "构建 $img   ← $jar ($(du -h "$jar" | cut -f1))"
  docker build -f "$ctx/Dockerfile.jdk21" --build-arg "JAR=$name.jar" -t "$img" "$ctx" >/dev/null
  ok "已构建 $img"
  # 注意：本机 NODE_OPTIONS 的 safe-delete 守卫会拦掉对 mktemp 目录的 rm，
  # 返回非 0 并被 set -e 放大成整个脚本退出（2026-10-10 实际踩到）。
  # 清理失败无所谓，所以吞掉错误。
  rm -rf "$ctx" 2>/dev/null || true
  IMAGES+=("$img")
  if [ "$PUSH" = "1" ]; then
    push_image "$img"
  fi
}

push_image(){
  local img="$1" n=0 out
  # 2026-10-10 实测：国内经 Docker Desktop 的 http.docker.internal 代理推 ~800MB 镜像，
  # 收尾阶段会瞬时失败（层已传完、manifest 那步掉线）。所以必须重试，
  # 且不能吞掉错误输出——否则完全不知道为什么失败。
  while [ "$n" -lt 4 ]; do
    n=$((n+1))
    if out="$(docker push "$img" 2>&1)"; then
      ok "已推送 $img"
      return 0
    fi
    warn "第 $n 次推送 $img 失败，10 秒后重试（已推送的层会复用，不会重传）"
    printf '%s\n' "$out" | tail -4 | sed 's/^/        /'
    sleep 10
  done
  printf '%s\n' "$out" | tail -8 | sed 's/^/      /' >&2
  die "推送 $img 连续失败 4 次。上面是最后一次的错误输出。"
}

IMAGES=()

build_java aismp-gateway       gateway   gateway
build_java aismp-backend       backend   backend
build_java aismp-digital-human kernel    digital-human
build_java aismp-agent         agent     agent

# ---- 编排层（Java · Spring AI Alibaba Graph）----
# 2026-10-20：原 Python/LangGraph 镜像已移除，改为和其他服务一样的 jar 镜像。
# 镜像名保持 ap-orchestrator 不变 —— compose 与网关路由都不用跟着改。
if [ -f "$JARS_DIR/aismp-orchestrator.jar" ]; then
  img="$REGISTRY/ap-orchestrator:$TAG"
  ctx="$(mktemp -d)"
  mkdir -p "$ctx/jars"
  cp "$JARS_DIR/aismp-orchestrator.jar" "$ctx/jars/app.jar"
  cp "$HERE/Dockerfile.jdk21" "$ctx/Dockerfile.jdk21"
  log "构建 $img   ← $JARS_DIR/aismp-orchestrator.jar"
  docker build -f "$ctx/Dockerfile.jdk21" --build-arg "JAR=app.jar" -t "$img" "$ctx" >/dev/null
  ok "已构建 $img"
  rm -rf "$ctx" 2>/dev/null || true
  IMAGES+=("$img")
  if [ "$PUSH" = "1" ]; then push_image "$img"; fi
else
  echo
  echo "跳过编排层：缺 $JARS_DIR/aismp-orchestrator.jar"
  echo "  先执行：bash deploy/server-docker/collect-artifacts.sh"
fi

if [ "$PUSH" = "1" ]; then
  log "全部完成：${#IMAGES[@]} 个镜像已构建并推送（每个构建完立即推送，见上方日志）"
else
  log "已构建 ${#IMAGES[@]} 个镜像（--no-push，未推送）"
fi

log "结果"
docker images --format '  {{.Repository}}:{{.Tag}}  {{.Size}}' | grep -F "$REGISTRY/" | sort || true
echo
echo "下一步（在服务器上）："
echo "  REGISTRY=$REGISTRY TAG=$TAG bash deploy-from-registry.sh"
