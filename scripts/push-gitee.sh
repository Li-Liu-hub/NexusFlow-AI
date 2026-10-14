#!/bin/bash
# ============================================================================
# push-gitee.sh —— 把代码推送到 Gitee
#
# 为什么需要单独的脚本：
#   本机 git 的 credential.helper 链里有 PortableGit 的 `helper-selector`，
#   它会弹一个「Select a credential helper」GUI 选择框 —— 在非交互环境里
#   无人点击，命令**永久挂死**（表现为 push / ls-remote 永不返回）。
#   判别：`tasklist | grep -i git-credential-helper-sel` 有进程 = 弹窗在等人点。
#
#   解法：清空 helper 链，直连 GCM（Git Credential Manager，凭据存在
#   Windows 凭据管理器 `LegacyGeneric:target=git:https://gitee.com`）。
#
# 用法：
#   bash scripts/push-gitee.sh                 # 推当前分支
#   bash scripts/push-gitee.sh main            # 推指定分支
#   bash scripts/push-gitee.sh --status        # 只看状态，不推
#   bash scripts/push-gitee.sh --dry-run       # 试推（不实际推送）
#
# 前置：Gitee HTTPS **禁用账号密码**，必须用**私人令牌**（设置 → 安全设置 → 私人令牌），
#       令牌当密码用，且必须是**有该仓库写权限的账号**的令牌。
# ============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE="$(cd "$HERE/.." && pwd)"
cd "$BASE"

log(){  printf '\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){   printf '\033[1;32m    + %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
die(){  printf '\033[1;31m    ! %s\033[0m\n' "$*" >&2; exit 1; }

# GCM 的绝对路径（本机 PortableGit 自带）
GCM="D:/WorkBuddy/resources/vendor/PortableGit/mingw64/bin/git-credential-manager.exe"
[ -f "$GCM" ] || warn "未找到 GCM（$GCM），将尝试系统默认凭据方式"

# ---- 参数 ----
BRANCH=""
DRY=0
STATUS_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    --status)  STATUS_ONLY=1; shift ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    -*) warn "未知参数：$1"; shift ;;
    *) BRANCH="$1"; shift ;;
  esac
done

# ---- 0. 前置检查 ----
command -v git >/dev/null 2>&1 || die "没有 git"

REMOTE_URL="$(git remote get-url origin 2>/dev/null)" || die "没有配置 origin 远端"
log "仓库：$REMOTE_URL"

CUR="$(git rev-parse --abbrev-ref HEAD)"
BRANCH="${BRANCH:-$CUR}"
log "分支：$BRANCH（当前 $CUR）"

# ---- 1. 状态 ----
log "工作区状态"
DIRTY="$(git status --porcelain | wc -l | tr -d ' ')"
if [ "$DIRTY" -gt 0 ]; then
  warn "有 $DIRTY 个未提交改动："
  git status --short | head -15
  echo ""
  warn "脚本不会替你提交。请先 commit，或用 /commit 工作流。"
else
  ok "工作区干净"
fi

AHEAD="$(git rev-list --count "origin/$BRANCH..$BRANCH" 2>/dev/null || echo '?')"
echo "    待推送提交数：$AHEAD（相对 origin/$BRANCH）"

if [ "$STATUS_ONLY" -eq 1 ]; then
  echo ""
  log "最近提交"
  git log --oneline -5
  exit 0
fi

[ "$DIRTY" -eq 0 ] || die "请先提交改动再推送"

# ---- 2. 推送 ----
# 关键：-c credential.helper= 清空继承的 helper 链（含会弹窗的 helper-selector），
#       再用 -c credential.helper=!<GCM> 直连 GCM。
PUSH_ARGS=(-c "credential.helper=")
if [ -f "$GCM" ]; then
  PUSH_ARGS+=(-c "credential.helper=!$GCM")
fi

if [ "$DRY" -eq 1 ]; then
  log "试推（dry-run，不会真正推送）"
  git "${PUSH_ARGS[@]}" push --dry-run origin "$BRANCH" || die "试推失败（多半是凭据问题）"
  ok "试推通过"
  exit 0
fi

log "推送到 origin/$BRANCH"
warn "若卡住不动：多半是 credential helper 弹窗在等人点。"
warn "  另开终端执行：tasklist | grep -i git-credential  （看到 sel 就是在等）"
warn "  或直接 Ctrl+C，然后重跑本脚本。"

if git "${PUSH_ARGS[@]}" push origin "$BRANCH"; then
  ok "推送成功"
  echo ""
  log "最近提交"
  git log --oneline -5
else
  RC=$?
  echo ""
  die "推送失败（退出码 $RC）。常见原因：
    1. 凭据失效 —— 打开「凭据管理器 → Windows 凭据」，删掉 git:https://gitee.com 后重试
    2. 令牌无权限 —— Gitee HTTPS 必须用**有写权限账号**的私人令牌（不能用账号密码）
    3. 远端有新提交 —— 先 git pull --rebase origin $BRANCH
    4. 弹窗挂死 —— 见上面的 tasklist 判别法"
fi
