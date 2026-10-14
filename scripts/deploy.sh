#!/bin/bash
# ============================================================================
# deploy.sh —— 把平台部署到远端服务器（从本机一键驱动）
#
# 做三件事（可裁剪）：
#   ① 传产物或拉镜像  ② 更新配置/静态文件  ③ 重启并健康检查
#
# 用法：
#   bash scripts/deploy.sh                    # 全量：拉镜像 + 重启全部应用 + 检查
#   bash scripts/deploy.sh --apps-only        # 只更新 5 个应用（最常用）
#   bash scripts/deploy.sh --web-only         # 只同步前端静态文件
#   bash scripts/deploy.sh --check            # 只做健康检查，不改动
#   bash scripts/deploy.sh --prepare          # 首次部署：铺目录与配置（不启动）
#   bash scripts/deploy.sh --tag 20261011     # 指定镜像 tag
#
# 前置：
#   · 服务器已装 Docker；`/opt/agent-platform/` 目录已就绪（首次用 --prepare）
#   · 若 server-docker/ 下有产物（jars/ web/），会自动上传
#
# 凭据：
#   · 服务器 SSH 口令优先从 deploy/server-secure/credentials.env 读 SERVER_SSH_PW
#   · 未配置则读环境变量 SSH_PW，最后回落到 D:/env/ssh_helper.py（本机助手）
#   ⚠️ 请勿把口令写进仓库 —— 见「安全提示」一节
# ============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE="$(cd "$HERE/.." && pwd)"
DEPLOY="$BASE/deploy/server-docker"
SECURE="$BASE/deploy/server-secure"

SERVER="${SERVER:-49.234.49.192}"
SSH_USER="${SSH_USER:-ubuntu}"
TAG="${TAG:-$(date +%Y%m%d)}"
REGISTRY="${REGISTRY:-jsyl6}"
REMOTE_BASE="/opt/agent-platform"

MODE="full"
case "${1:-}" in
  --apps-only) MODE="apps"; shift ;;
  --web-only)  MODE="web"; shift ;;
  --check)     MODE="check"; shift ;;
  --prepare)   MODE="prepare"; shift ;;
  "") ;;
  --tag)  TAG="$2"; shift 2 ;;
  *) ;;
esac
# 再解析剩余参数（支持 --tag 与模式混用）
while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="$2"; shift 2 ;;
    --server) SERVER="$2"; shift 2 ;;
    *) shift ;;
  esac
done

log(){  printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){   printf '\033[1;32m    + %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
die(){  printf '\033[1;31m    ! %s\033[0m\n' "$*" >&2; exit 1; }

# ---------- 定位 Python（paramiko 只装在这个 venv 里）----------
PY="C:/Users/20886/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
[ -f "$PY" ] || PY="python"
$PY -c "import paramiko" 2>/dev/null || warn "当前 Python 无 paramiko，SSH 操作可能失败"

SSH_RUN="D:/env/ssh_run_script.py"
SSH_UP="D:/env/ssh_upload.py"

# ---------- 凭据检查 ----------
CRED="$SECURE/credentials.env"
if [ -f "$CRED" ] && grep -q "SERVER_SSH_PW" "$CRED" 2>/dev/null; then
  ok "从 $CRED 读取 SSH 凭据"
elif [ -n "${SSH_PW:-}" ]; then
  ok "从环境变量 SSH_PW 读取 SSH 凭据"
else
  # 兼容旧的本机助手（内含口令，在仓库外）
  warn "未配置 SERVER_SSH_PW / SSH_PW，将依赖 $SSH_RUN 内置凭据"
  warn "建议：把 SERVER_SSH_PW 写入 $CRED（该文件已被 .gitignore 忽略）"
fi

log "部署配置"
echo "    服务器   : $SSH_USER@$SERVER"
echo "    镜像仓库 : $REGISTRY"
echo "    镜像 tag : $TAG"
echo "    模式     : $MODE"
echo "    远端根   : $REMOTE_BASE"

# ---------- 远端执行封装 ----------
remote() {
  # $1 = 远端要执行的 bash 片段
  local script="$1" tmp
  tmp="$(mktemp /tmp/_deploy_XXXX.sh)"
  printf '#!/bin/bash\nset -uo pipefail\n%s\n' "$script" > "$tmp"
  # ⚠️ 必须加 MSYS_NO_PATHCONV=1，否则 Git Bash 会转换远端路径
  MSYS_NO_PATHCONV=1 $PY "$SSH_RUN" "$tmp" || { rm -f "$tmp"; return 1; }
  rm -f "$tmp" 2>/dev/null || true
}

# ---------- 模式：只检查 ----------
if [ "$MODE" = "check" ]; then
  log "健康检查（不改动任何东西）"
  remote '
echo "── 容器状态 ──"
docker ps --format "table {{.Names}}\t{{.Status}}" 2>/dev/null | head -20
echo
echo "── 对外监听 ──"
ss -lntp 2>/dev/null | grep -E ":80 |:22 " || true
echo
echo "── 门户 ──"
curl -s -o /dev/null -w "  GET /        → %{http_code}\n" http://localhost/
curl -s -o /dev/null -w "  GET /admin/  → %{http_code}\n" http://localhost/admin/
echo
echo "── 内存 ──"
free -m | head -2
'
  exit $?
fi

# ---------- 模式：首次铺目录 ----------
if [ "$MODE" = "prepare" ]; then
  log "首次部署：上传配置并铺目录"
  for f in docker-compose.full.yml nginx/nginx.conf init/mysql-init/01-init.sh init/pg-init/01-init.sh 75-prepare-server.sh; do
    src="$DEPLOY/$f"
    [ -f "$src" ] || { warn "缺 $src，跳过"; continue; }
    MSYS_NO_PATHCONV=1 $PY "$SSH_UP" "$src" "/tmp/$(basename "$f")" || die "上传失败：$f"
    ok "已上传 $(basename "$f")"
  done
  log "在服务器上执行 75-prepare-server.sh"
  remote "sudo -E REGISTRY=$REGISTRY TAG=$TAG bash /tmp/75-prepare-server.sh" || die "铺目录失败"
  ok "准备完成。接着执行：bash scripts/deploy.sh"
  exit 0
fi

# ---------- 上传前端静态文件（web 模式）----------
if [ "$MODE" = "web" ]; then
  log "同步前端静态文件"
  for d in web/portal web/admin; do
    [ -d "$DEPLOY/$d" ] || { warn "缺 $DEPLOY/$d，跳过"; continue; }
    # 打包上传再解压，避免逐文件传
    tar czf /tmp/_web.tgz -C "$DEPLOY" "$d" 2>/dev/null || die "打包 $d 失败"
    MSYS_NO_PATHCONV=1 $PY "$SSH_UP" /tmp/_web.tgz /tmp/_web.tgz || die "上传失败"
    remote "sudo mkdir -p $REMOTE_BASE/web && sudo tar xzf /tmp/_web.tgz -C $REMOTE_BASE && echo 解压完成"
    ok "已同步 $d"
  done
  remote "sudo docker restart ap-nginx >/dev/null 2>&1; curl -s -o /dev/null -w '门户 %{http_code}\n' http://localhost/"
  exit 0
fi

# ---------- 更新应用（apps / full）----------
log "在服务器上更新应用镜像并滚动重启"
if [ ! -f "$DEPLOY/80-update-apps.sh" ]; then
  die "缺 $DEPLOY/80-update-apps.sh"
fi
MSYS_NO_PATHCONV=1 $PY "$SSH_UP" "$DEPLOY/80-update-apps.sh" /tmp/80-update-apps.sh || die "上传失败"

if [ "$MODE" = "apps" ]; then
  remote "sudo env REGISTRY=$REGISTRY TAG=$TAG bash /tmp/80-update-apps.sh" || die "更新应用失败"
else
  # 全量：拉所有镜像 + up -d（改配置必须 up -d，restart 不生效）
  remote "
sudo env REGISTRY=$REGISTRY TAG=$TAG bash /tmp/80-update-apps.sh || exit 1
echo
echo '── compose up -d（应用新配置）──'
cd $REMOTE_BASE/compose
sudo docker compose --env-file .env -f docker-compose.yml up -d
" || die "全量更新失败"
fi

# ---------- 收尾健康检查 ----------
log "健康检查"
remote '
echo "── 容器状态 ──"
docker ps --format "{{.Names}}\t{{.Status}}" 2>/dev/null | head -15
echo
curl -s -o /dev/null -w "  门户 /        → %{http_code}\n" http://localhost/
curl -s -o /dev/null -w "  管理台 /admin/ → %{http_code}\n" http://localhost/admin/
echo
free -m | head -2
'

echo ""
ok "部署完成（tag=$TAG）"
echo "    门户：http://$SERVER/"
echo "    管理台：http://$SERVER/admin/"
