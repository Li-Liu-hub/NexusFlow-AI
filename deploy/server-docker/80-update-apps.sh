#!/bin/bash
# ============================================================================
# 80-update-apps.sh —— 服务器侧：拉取新镜像并滚动重启应用
#
# 用法：
#   sudo env REGISTRY=jsyl6 TAG=<新tag> bash 80-update-apps.sh            # 更新全部 5 个应用
#   sudo env REGISTRY=jsyl6 TAG=<新tag> bash 80-update-apps.sh gateway    # 只更新网关
#   sudo env REGISTRY=jsyl6 TAG=<新tag> bash 80-update-apps.sh --web-only # 只同步前端静态文件
#
# ★ 为什么必须 `pull --policy always`：
#   `docker compose pull` 默认策略是 `missing` —— 本地已有同名 tag 就跳过，
#   不比对远端 digest。沿用旧 tag 覆盖推送后，pull 会报 "up to date"、
#   容器继续跑旧镜像，让人误以为更新没生效（很隐蔽）。
#   `--policy always` 强制重新拉取并按 digest 判断是否需要换层。
#
# 注意：改 docker-compose.yml 或 .env 里的配置，**不需要拉镜像**，
#       直接 `up -d` 就会用新配置重建容器（restart 不会，必须 up -d）。
# ============================================================================
set -uo pipefail

COMPOSE_DIR=/opt/agent-platform/compose
COMPOSE="$COMPOSE_DIR/docker-compose.yml"
WEB=/opt/agent-platform/web
APP_SERVICES="gateway backend agent digital-human orchestrator"
ALL_SERVICES="$APP_SERVICES"
STATE_FILE=/opt/agent-platform/.last-tag

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '\033[1;32m    + %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
die(){ printf '\033[1;31m    ! %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 运行"

WEB_ONLY=0
TARGETS=""
for a in "$@"; do
  case "$a" in
    --web-only) WEB_ONLY=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) TARGETS="$TARGETS $a" ;;
  esac
done
[ -z "$TARGETS" ] && TARGETS="$ALL_SERVICES"

cd "$COMPOSE_DIR" || die "找不到 $COMPOSE_DIR"

# ---------------------------------------------------------------- 只同步前端
if [ "$WEB_ONLY" = "1" ]; then
  log "只更新前端静态文件"
  echo "    前端是 bind mount 到 nginx 容器的（$WEB/portal、$WEB/admin），"
  echo "    所以覆盖文件即刻生效，**连容器都不用重启**。"
  echo "    请把新的 portal.html/portal.css/portal.js 覆盖到 $WEB/portal/，"
  echo "    admin 产物覆盖到 $WEB/admin/，然后："
  echo "      curl -s -o /dev/null -w '%{http_code}\\n' http://127.0.0.1/portal.html"
  echo "    （若浏览器有缓存，强刷 Ctrl+F5）"
  ls -la "$WEB/portal" "$WEB/admin" 2>/dev/null | sed 's/^/    /'
  exit 0
fi

# ---------------------------------------------------------------- 前置
[ -n "${REGISTRY:-}" ] || die "必须指定 REGISTRY（如 jsyl6）"
[ -n "${TAG:-}" ] || die "必须指定 TAG（且应当是【新】tag，不要沿用旧的）"

log "更新目标"
echo "    REGISTRY = $REGISTRY"
echo "    TAG      = $TAG"
echo "    服务     =$TARGETS"
if [ -f "$STATE_FILE" ] && [ "$(cat "$STATE_FILE" 2>/dev/null)" = "$TAG" ]; then
  warn "TAG 与上次相同（$TAG）—— 若你确实没改代码可以继续；改了代码请换新 tag"
fi

# ---------------------------------------------------------------- 记录旧状态（便于回滚）
log "记录当前镜像（便于回滚）"
BEFORE="$COMPOSE_DIR/.images-before.txt"
docker compose --env-file .env -f "$COMPOSE" images 2>/dev/null > "$BEFORE"
cp -a "$BEFORE" "$COMPOSE_DIR/.images-before-$(date +%Y%m%d-%H%M%S).txt"
sed 's/^/    /' "$BEFORE"

# ---------------------------------------------------------------- 1) 拉新镜像
log "1) 强制拉取新镜像（--policy always，绕开 policy=missing 的坑）"
docker compose --env-file .env -f "$COMPOSE" pull --policy always $TARGETS || die "拉取失败：检查 TAG 是否真的推上去了、是否已登录（公有仓库无需登录）"

# ---------------------------------------------------------------- 2) 滚动重建
log "2) 滚动重建容器"
# up -d 会按新镜像重建变化的容器；不相关的服务保持不动
docker compose --env-file .env -f "$COMPOSE" up -d $TARGETS || die "重建失败，看日志：docker compose logs <服务>"

# ---------------------------------------------------------------- 3) 探活
# 应用端口不 publish 到宿主（只在 docker 网络内），所以从 ap-nginx 容器里探 —— 它有 curl。
log "3) 等待并探活（Spring Boot 冷启动约 19~23s，最多等 180s）"
declare -A PROBE=(
  [gateway]="http://gateway:8090/actuator/health"
  [backend]="http://backend:7529/api/health"
  [agent]="http://agent:8124/terminal/health/suggest"
  [digital-human]="http://digital-human:8126/"
  [orchestrator]="http://orchestrator:8125/docs"
)
for s in $TARGETS; do
  cid="ap-${s}"
  url="${PROBE[$s]:-}"
  code=""
  for i in $(seq 1 36); do
    if [ -n "$url" ]; then
      code=$(docker exec ap-nginx curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null)
    fi
    # 404/405 也算"端口在服务"（路径可能不存在，但进程活着）
    case "$code" in
      2*|3*|4*|5*) break ;;
    esac
    sleep 5
  done
  running=$(docker inspect -f '{{.State.Running}}' "$cid" 2>/dev/null)
  if [ "$running" = "true" ] && [ -n "$code" ]; then
    ok "$s 已就绪（$cid 在跑，探测 $url → HTTP=$code）"
  elif [ "$running" = "true" ]; then
    warn "$s 容器在跑但探测无响应，看：docker logs --tail 50 $cid"
  else
    warn "$s 没起来，看：docker logs --tail 50 $cid"
  fi
done

# ---------------------------------------------------------------- 4) 端到端
log "4) 端到端自检"
sleep 15
for u in /portal.html /admin/ /gw/ /oa/ /orchestrator/; do
  printf '    %-22s ' "$u"
  curl -s -o /dev/null -w "HTTP=%{http_code}  %{time_total}s\n" --noproxy '*' --max-time 15 "http://127.0.0.1$u"
done
echo
echo "    容器状态："
docker compose --env-file .env -f "$COMPOSE" ps --format '      {{.Name}}  {{.Status}}'
echo
echo "    注册到 Nacos 的实例（应是你刚更新的那些）："
for c in ap-gateway ap-backend ap-agent ap-digital-human; do
  printf '      %-20s ' "$c"
  docker logs --tail 400 "$c" 2>&1 | grep -oE 'nacos registry, DEFAULT_GROUP [a-z-]+ [0-9.]+:[0-9]+' | tail -1
done

echo "$TAG" > "$STATE_FILE"
log "完成（tag=$TAG 已记录到 $STATE_FILE）"
echo "  回滚到上一版："
echo "    查看旧镜像： cat $BEFORE"
echo "    sudo env REGISTRY=$REGISTRY TAG=<旧tag> bash $0 ${TARGETS}"
