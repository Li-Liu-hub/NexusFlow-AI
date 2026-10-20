#!/bin/bash
# ============================================================================
# deploy-from-registry.sh —— 在服务器上：拉镜像 → 停 systemd 版 → 起容器
#
# 前提：
#   1. 已用 build-and-push.sh 把 5 个镜像推到了镜像仓库
#   2. 服务器已装 docker + compose 插件（本脚本可代装，见 --install-docker）
#   3. /opt/agent-platform-secure/credentials.env、/opt/app/kernel/kernel.env、
#      /opt/app/orchestrator/.env 都还在（容器用 env_file 读它们）
#
# 用法：
#   sudo -E REGISTRY=ccr.ccs.tencentyun.com/<ns> TAG=20261010 bash deploy-from-registry.sh
#   sudo -E REGISTRY=... TAG=... bash deploy-from-registry.sh --install-docker
#   sudo -E REGISTRY=... TAG=... bash deploy-from-registry.sh --rollback
#
# 切换是「先停后起」，会有约 20~40 秒服务不可用。
# ============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ENVFILE="/opt/agent-platform-secure/compose.env"
APP_UNITS="aismp-gateway aismp-backend aismp-digital-human aismp-agent aismp-orchestrator"
MID_UNITS="nginx nacos rabbitmq-server redis-server postgresql@18-main mysql"
DECLARE_PORTS="8090 7529 8126 8124 8125"

MODE="apps"; INSTALL_DOCKER=0; ROLLBACK=0
for a in "$@"; do
  case "$a" in
    --install-docker) INSTALL_DOCKER=1 ;;
    --rollback)       ROLLBACK=1 ;;
    --full)           MODE="full" ;;
    --apps)           MODE="apps" ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$a" >&2; exit 2 ;;
  esac
done
if [ "$MODE" = "full" ]; then
  COMPOSE_FILE="$HERE/docker-compose.full.yml"
  UNITS="$APP_UNITS $MID_UNITS"     # 全量：中间件也要一起停，否则端口/数据目录冲突
else
  # 只更新应用时同样用 full.yml（compose 可按服务名操作，无需另存一份 apps 版）。
  # 曾存在的 docker-compose.apps.yml 已删除 —— 两份文件容易版本漂移（旧版就是因此用了
  # redis:7 / nacos:v2.2.3 的错误版本），统一单一来源。
  COMPOSE_FILE="$HERE/docker-compose.full.yml"
  UNITS="$APP_UNITS"
fi

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '\033[1;32m    + %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
die(){ printf '\033[1;31m    ! %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 运行：sudo -E bash $0 $*"
[ -f "$COMPOSE_FILE" ] || die "找不到编排文件：$COMPOSE_FILE"

# ---------------------------------------------------------------- 回滚
if [ "$ROLLBACK" = "1" ]; then
  log "回滚：停容器 → 恢复 systemd 版应用"
  docker compose -f "$COMPOSE_FILE" down 2>/dev/null || true
  for u in $UNITS; do systemctl enable --now "$u" >/dev/null 2>&1; done
  sleep 20
  for u in $UNITS; do printf '  %-24s %s\n' "$u" "$(systemctl is-active "$u")"; done
  free -m | head -2
  ok "已回滚到 systemd（systemd 版的单元文件未被动过，仍在 /etc/systemd/system/）"
  exit 0
fi

# ---------------------------------------------------------------- 前置
REGISTRY="${REGISTRY:?必须指定 REGISTRY，例如 ccr.ccs.tencentyun.com/my-ns}"
TAG="${TAG:-latest}"

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  if [ "$INSTALL_DOCKER" = "1" ]; then
    log "安装 Docker（含 compose 插件）"
    if [ -f /etc/os-release ] && grep -q 'ID=ubuntu' /etc/os-release; then
      install -m0755 -d /etc/apt/keyrings
      curl -fsSL https://mirrors.cloud.tencent.com/docker-ce/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc || \
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
      chmod a+r /etc/apt/keyrings/docker.asc
      . /etc/os-release
      echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://mirrors.cloud.tencent.com/docker-ce/linux/ubuntu $VERSION_CODENAME stable" \
        > /etc/apt/sources.list.d/docker.list
      apt-get update -qq && apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    else
      curl -fsSL https://get.docker.com | sh
    fi
    systemctl enable --now docker
    ok "Docker 已安装：$(docker --version)"
  else
    die "没装 docker/compose。加 --install-docker 让脚本代装，或手动装后重跑。"
  fi
fi
docker info >/dev/null 2>&1 || die "Docker 守护进程未运行：systemctl start docker"

# ---------------------------------------------------------------- 配 Docker Hub 镜像源
# 实测（2026-10-10）：这台服务器【连不上 Docker Hub】——registry-1.docker.io / auth.docker.io /
# hub.docker.com 三个域名 DNS 能解析但 TCP 建连超时（7.4s ×3）。而官方镜像（mysql/pgvector/
# redis/rabbitmq/nacos/nginx）和你自己的 5 个镜像都必须从 Docker Hub 拉 → 必须配镜像源。
#
# ★ 关键：能代理「你命名空间(jsyl6)」的源和「只代理热门镜像」的源不是同一批。逐个实测结果：
#     docker.1ms.run            ✅ 会代理 jsyl6（认证通过，对不存在的 tag 返回 404）
#     hub.rat.dev               ✅ 会代理 jsyl6
#     mirror.ccs.tencentyun.com ✅ 会代理 jsyl6（18ms，最快）
#     docker.m.daocloud.io      ❌ 拒绝该命名空间 ★ 本机现在配的就是它，别照抄到服务器
#     docker.1panel.live / docker.ketches.cn  ? 403 无挑战头，不可用
#     docker.mirrors.ustc.edu.cn / dockerpull.org  ❌ 连不上
# 所以固定用 1ms.run 主 + hub.rat.dev / 腾讯云内网 备。换源前请用 mirror_probe2.py 复测。
# ⚠️ 复测时必须用【真实存在的用户名】：拿一个不存在的用户名去测会得到假结果
#    （实测 jsy16 不存在→daocloud 403、腾讯云 403；jsyl6 存在→腾讯云 404 即会代理）。
MIRROR_FILE=/etc/docker/daemon.json
WANT_MIRRORS='https://docker.1ms.run","https://hub.rat.dev","https://mirror.ccs.tencentyun.com'
log "配置 Docker Hub 镜像源：$MIRROR_FILE"
if [ -f "$MIRROR_FILE" ] && grep -q 'registry-mirrors' "$MIRROR_FILE"; then
  ok "已有 registry-mirrors 配置，未改动（如需更新请手工编辑）"
  grep -A4 registry-mirrors "$MIRROR_FILE" | sed 's/^/      /'
else
  [ -f "$MIRROR_FILE" ] && cp -a "$MIRROR_FILE" "$MIRROR_FILE.bak.$(date +%s)"
  cat > "$MIRROR_FILE" <<EOF
{
  "registry-mirrors": ["$WANT_MIRRORS"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
  systemctl restart docker && sleep 5
  docker info >/dev/null 2>&1 || die "改完 daemon.json 后 Docker 起不来了，回滚：cp $MIRROR_FILE.bak.* $MIRROR_FILE && systemctl restart docker"
  ok "已写入并重启 Docker"
fi

# ---------------------------------------------------------------- 生成 compose 用变量文件
log "写入 $ENVFILE（供 compose 做变量替换，chmod 600）"
umask 077
cat > "$ENVFILE" <<EOF
REGISTRY=$REGISTRY
TAG=$TAG
EOF
chmod 600 "$ENVFILE"
ok "REGISTRY=$REGISTRY  TAG=$TAG"

# ---------------------------------------------------------------- 登录 + 拉取
log "docker login（若已登录可忽略提示）"
# Docker Hub 的 REGISTRY 是「用户名」而不是域名，docker login 需要域名，故取第一段并兜底
HOST="$(printf '%s' "$REGISTRY" | cut -d/ -f1)"
case "$HOST" in *.*) ;; *) HOST="docker.io" ;; esac
docker login "$HOST" || warn "登录失败/被跳过 —— 若仓库是私有的，拉取会失败"

log "拉取镜像"
# 这是【首次部署】路径，本地没有任何镜像，用默认策略(missing)即可。
# ⚠️ 但【日常更新】别用这里 —— 默认 policy=missing 在同 tag 已存在时会跳过拉取，
#    导致"pull 成功但还在跑旧镜像"。更新请用 80-update-apps.sh（它带 --policy always）。
docker compose --env-file "$ENVFILE" -f "$COMPOSE_FILE" pull || die "拉取失败，检查 REGISTRY/TAG 是否正确、是否已登录"

# ---------------------------------------------------------------- 切换
log "停止并禁用 systemd 版应用（单元文件保留，便于 --rollback）"
for u in $UNITS; do
  systemctl disable --now "$u" >/dev/null 2>&1 && ok "已停 $u" || warn "$u 停止异常（可能本来就没在跑）"
done
sleep 3

log "启动容器"
docker compose --env-file "$ENVFILE" -f "$COMPOSE_FILE" up -d || die "容器启动失败，看：docker compose -f $COMPOSE_FILE logs"

log "等待端口就绪并验活（最多 150s）"
deadline=$((SECONDS+150))
for p in $DECLARE_PORTS; do
  while [ $SECONDS -lt $deadline ]; do
    if (exec 3<>/dev/tcp/127.0.0.1/"$p") 2>/dev/null; then exec 3>&-; ok "端口 $p 已就绪"; break; fi
    sleep 3
  done
  [ $SECONDS -lt $deadline ] || warn "端口 $p 超时未就绪"
done

log "容器状态"
docker compose --env-file "$ENVFILE" -f "$COMPOSE_FILE" ps
echo
log "内存"
free -m | head -2
echo
echo "门户自检："
curl -s -o /dev/null -w "  http://127.0.0.1:8000/portal.html  HTTP=%{http_code}\n" --noproxy '*' http://127.0.0.1:8000/portal.html 2>/dev/null || true
echo
echo "常用命令："
echo "  看日志   docker compose --env-file $ENVFILE -f $COMPOSE_FILE logs -f --tail=100 <服务名>"
echo "  重启单个 docker compose --env-file $ENVFILE -f $COMPOSE_FILE restart gateway"
echo "  看内存   docker stats --no-stream"
echo "  回滚     sudo -E REGISTRY=$REGISTRY TAG=$TAG bash $0 --rollback"
