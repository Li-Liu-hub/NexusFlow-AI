#!/bin/bash
# ============================================================================
# 75-prepare-server.sh —— 铺好容器化所需的目录与配置（**不启动任何容器**）
#
# 前置：把下面 4 个文件先传到 /tmp（脚本会归位）：
#   /tmp/docker-compose.full.yml ← deploy/server-docker/docker-compose.full.yml
#   /tmp/nginx.conf              ← deploy/server-docker/nginx/nginx.conf
#   /tmp/75-mysql-init.sh        ← deploy/server-docker/init/mysql-init/01-init.sh
#   /tmp/75-pg-init.sh           ← deploy/server-docker/init/pg-init/01-init.sh
#
# 注：本脚本会把 docker-compose.full.yml 归位为服务器上的 compose/docker-compose.yml
#     （仅改文件名，便于直接 `docker compose -f docker-compose.yml` 使用）。
#
# 用法：sudo -E REGISTRY=jsyl6 TAG=20261010 bash 75-prepare-server.sh
#
# 目录布局（数据全部落服务器本地盘，备份 = tar 这一个目录）：
#   /opt/agent-platform/
#   ├── compose/{docker-compose.yml,.env}
#   ├── data/{mysql,pg,redis,rabbitmq,nacos,nacos-logs,kb-uploads}
#   ├── init/{mysql-init,pg-init}/01-init.sh
#   ├── nginx/nginx.conf
#   ├── web/{portal,admin}
#   └── backup/
# ============================================================================
set -uo pipefail

BASE=/opt/agent-platform
SRC=/tmp
REGISTRY="${REGISTRY:?必须指定 REGISTRY（如 jsyl6）}"
TAG="${TAG:-20261010}"
CRED=/opt/agent-platform-secure/credentials.env

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '\033[1;32m    + %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
die(){ printf '\033[1;31m    ! %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 运行"
[ -f "$CRED" ] || die "找不到凭据文件 $CRED"

log "1) 建目录树"
mkdir -p "$BASE"/{compose,backup} \
         "$BASE"/data/{mysql,pg,redis,rabbitmq,nacos,nacos-logs,kb-uploads} \
         "$BASE"/init/{mysql-init,pg-init} \
         "$BASE"/nginx \
         "$BASE"/web/{portal,admin}
ok "$BASE 已建"

log "2) 归位 compose / nginx.conf / init 脚本"
# 源文件名是 docker-compose.full.yml（本地唯一的 compose 定义），
# 到服务器后统一叫 docker-compose.yml，方便日常 `docker compose -f docker-compose.yml`。
COMPOSE_SRC="$SRC/docker-compose.full.yml"
[ -f "$COMPOSE_SRC" ] || COMPOSE_SRC="$SRC/docker-compose.yml"   # 兼容老流程
[ -f "$COMPOSE_SRC" ]            || die "缺 $SRC/docker-compose.full.yml"
[ -f "$SRC/nginx.conf" ]         || die "缺 $SRC/nginx.conf"
install -m644 "$COMPOSE_SRC" "$BASE/compose/docker-compose.yml"
install -m644 "$SRC/nginx.conf"         "$BASE/nginx/nginx.conf"
install -m755 "$SRC/75-mysql-init.sh"   "$BASE/init/mysql-init/01-init.sh"
install -m755 "$SRC/75-pg-init.sh"      "$BASE/init/pg-init/01-init.sh"
ok "compose + nginx.conf + 2 个 init 脚本已就位"

log "3) 静态文件：直接从原生 nginx 目录搬（不本地构建，避免 vite/esbuild 中文路径坑）"
if [ -d /var/www/portal ]; then
  cp -a /var/www/portal/. "$BASE/web/portal/"
  ok "portal: $(ls "$BASE/web/portal" | tr '\n' ' ')"
else warn "/var/www/portal 不存在"; fi
if [ -d /var/www/admin ]; then
  cp -a /var/www/admin/. "$BASE/web/admin/"
  ok "admin: $(ls "$BASE/web/admin" | tr '\n' ' ')"
else warn "/var/www/admin 不存在"; fi

log "4) 生成 .env（compose 变量替换用，chmod 600）"
ENVF="$BASE/compose/.env"
umask 077
{
  echo "# 由 75-prepare-server.sh 生成于 $(date '+%F %T') —— 不入 git"
  echo "REGISTRY=$REGISTRY"
  echo "TAG=$TAG"
  echo
  echo "# ---- 以下来自 $CRED ----"
  grep -vE '^[[:space:]]*(#|$)' "$CRED"
  echo
  echo "# ---- 网关调用凭证（backend/agent 的 application.yml 里硬编码的就是这对，
  #      而 credentials.env 里没有，故在此补齐）----"
  echo "GW_ACCESS_KEY=aismp-console"
  echo "GW_SECRET_KEY=7c1f9a3e5d0b2846af31c7e9b0d45268"
} > "$ENVF"
chmod 600 "$ENVF"
ok ".env 生成完毕（$(( $(wc -l < "$ENVF") )) 行）"
echo "    含变量：$(grep -oE '^[A-Z_]+=' "$ENVF" | tr -d '=' | tr '\n' ' ')"

log "5) 数据目录属主"
# MySQL / PostgreSQL 官方镜像的 entrypoint 会以 root 启动并 chown 数据目录，所以留空 + root 属主即可。
chown -R 999:999 "$BASE/data/mysql" "$BASE/data/pg"
# Redis / RabbitMQ / Nacos 镜像内的运行用户不确定（alpine 与 debian 变体不同），
# 给 777 换掉一整类启动期权限故障。这是单租户服务器、目录在 /opt 下、面板不对外，
# 属于刻意接受的取舍；首次起来验活通过后若想收紧，可按容器实际用户改回。
chmod 777 "$BASE/data/redis" "$BASE/data/rabbitmq" "$BASE/data/nacos" "$BASE/data/nacos-logs"
# 内核容器里跑的是 Dockerfile 建的 appuser(uid 1001)，知识库上传目录要它能写
chown -R 1001:1001 "$BASE/data/kb-uploads"
ok "mysql/pg=999:999  redis/rabbitmq/nacos=777  kb-uploads=1001:1001"

log "6) 骨架自检"
printf '    %-46s\n' "路径                                      大小 / 内容"
du -sh "$BASE" 2>/dev/null | sed 's/^/    总大小: /'
for p in compose/docker-compose.yml compose/.env nginx/nginx.conf \
         init/mysql-init/01-init.sh init/pg-init/01-init.sh \
         web/portal/portal.html web/admin/index.html; do
  if [ -e "$BASE/$p" ]; then printf '    ✅ %s\n' "$p"; else printf '    ❌ %s 缺失\n' "$p"; fi
done
echo
echo "    数据目录："
for d in mysql pg redis rabbitmq nacos nacos-logs kb-uploads; do
  printf '      %-12s %s\n' "$d" "$(stat -c '%U:%G %a' "$BASE/data/$d" 2>/dev/null)"
done
echo
echo "    磁盘：$(df -h / | tail -1 | awk '{print $4" 可用"}')"
echo "    内存：$(free -m | awk '/Mem:/{print $7" MB 可用"}')"

log "7) 与 compose 的一致性核对（防路径写错）"
miss=0
for p in $(grep -oE '/opt/agent-platform/[A-Za-z0-9_./-]+' "$BASE/compose/docker-compose.yml" | sort -u); do
  # 只校验"源"路径：跳过容器内的目标路径（它们出现在冒号右边）
  [ -e "$p" ] && continue
  case "$p" in
    */data/*|*/init/*|*/nginx/*|*/web/*) printf '    ⚠️ compose 引用了但不存在: %s\n' "$p"; miss=$((miss+1));;
  esac
done
[ "$miss" -eq 0 ] && ok "compose 里引用的宿主路径全部存在" || warn "$miss 个路径缺失（首次启动前 init 目录会由 Docker 创建为空目录，data 目录已建）"

echo
echo "  下一步（先别启动容器）："
echo "    sudo -E REGISTRY=$REGISTRY TAG=$TAG bash /tmp/deploy-from-registry.sh --full"
