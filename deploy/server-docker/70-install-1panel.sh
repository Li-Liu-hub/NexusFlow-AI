#!/bin/bash
# ============================================================================
# 70-install-1panel.sh —— 1Panel 安装前探测 + 安装后加固
#
# 用法（三步，全部可非交互执行）：
#     sudo bash 70-install-1panel.sh --preflight   # 装之前探测风险（只读）
#     sudo bash 70-install-1panel.sh --install     # 装（非交互，凭据自动生成并存盘）
#     sudo bash 70-install-1panel.sh --post        # 装之后加固（端口收口 + 内存上限）
#     sudo bash 70-install-1panel.sh --verify      # 复查
#
# ★ 2026-10-10 读 v2.3.2 的 install.sh 源码后确认：它【支持 --non-interactive】，
#   所以不需要你在自己的 SSH 终端里手动回答问题（我原先的说法是错的，已改）。
#
# 加固做两件事（对应方案文档 §1 坑1 与 §2）：
#   ① 面板端口【不】在 ufw 放行 → 只能走 SSH 隧道访问，零新增对外暴露面
#   ② 给 1panel* 的所有 systemd 单元加 MemoryMax → 把它的占用也框住，
#      免得 compose 的 mem_limit 合计 + 1Panel + OS 超过物理内存后被内核随机 OOM-kill
# ============================================================================
set -uo pipefail

MODE="guide"
for a in "$@"; do
  case "$a" in
    --preflight) MODE="preflight" ;;
    --install)   MODE="install" ;;
    --post)      MODE="post" ;;
    --verify)    MODE="verify" ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$a" >&2; exit 2 ;;
  esac
done
[ "$(id -u)" -eq 0 ] || { echo "请用 root 运行：sudo bash $0 $*"; exit 1; }

PANEL_PORT="${PANEL_PORT:-23456}"     # 装的时候请填这个；填了别的就 export PANEL_PORT=...
MEM_MAX="${MEM_MAX:-384M}"            # 1Panel 内存硬上限（社区实测空载 150~200MB）

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '\033[1;32m    + %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
die(){ printf '\033[1;31m    ! %s\033[0m\n' "$*" >&2; exit 1; }

unit_list(){ systemctl list-unit-files --no-pager --no-legend 2>/dev/null | awk '{print $1}' | grep -E '^1panel' ; }

# ---------------------------------------------------------------- 装前探测
preflight(){
  log "1) 系统与内存"
  . /etc/os-release 2>/dev/null || true
  echo "    发行版 : ${PRETTY_NAME:-未知}  (codename=${VERSION_CODENAME:-未知})  内核=$(uname -r)"
  awk '/MemTotal/{t=$2} /MemAvailable/{a=$2} END{printf "    内存   : 总 %.0f MB / 可用 %.0f MB\n", t/1024, a/1024}' /proc/meminfo
  av=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)
  if [ "$av" -lt 1000 ]; then
    warn "可用内存 ${av}MB < 1000MB，低于 1Panel 官方建议；建议先做完容器化再装"
  else
    ok "可用内存 ${av}MB，够"
  fi

  log "2) Docker 是否就绪（1Panel 要接管 Docker）"
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    ok "$(docker --version)"
  else
    warn "Docker 未就绪。先完成容器化再装 1Panel；或让安装脚本自己装（不推荐，会多一套配置）"
  fi

  log "3) 端口占用（★ 两个已知冲突点）"
  occ=$(ss -tlnH 2>/dev/null | awk '{print $4}' | grep -oE '[0-9]+$' | sort -un | tr '\n' ' ')
  echo "    当前监听端口: $occ"
  port_busy(){ ss -tlnH 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1\$"; }
  if port_busy 8000; then
    warn "端口 8000 已被占用 —— 这是我们的【门户入口】。1Panel 面板默认端口也是 8000，"
    warn "  所以安装时必须自定义面板端口（建议 $PANEL_PORT），绝不能用 8000"
  fi
  if port_busy 80; then
    warn "端口 80 已被占用（我们的原生 nginx：门户静态站 + /api 反代）"
    warn "  1Panel 的 Web 服务默认想要 80/443，安装时请跳过它的「网站功能」"
  fi
  [ "$PANEL_PORT" = "8000" ] && die "PANEL_PORT 不能是 8000（和门户冲突）"

  log "4) 发行版兼容性探测（这台是较新的 Ubuntu，1Panel 可能不认）"
  probe=$(curl -fsSL --max-time 25 https://resource.fit2cloud.com/1panel/package/v2/quick_start.sh 2>/dev/null)
  if [ -z "$probe" ]; then
    warn "下载不到安装脚本（网络问题）。国内源不通可换：https://resource.1panel.pro/v2/quick_start.sh"
  else
    echo "    安装脚本已下载，大小 $(printf '%s' "$probe" | wc -c) 字节"
    if printf '%s' "$probe" | grep -qiE "${VERSION_CODENAME:-__none__}|${VERSION_ID:-__none__}"; then
      ok "脚本里出现了本机 codename/version，识别大概率没问题"
    else
      warn "脚本里没出现 ${VERSION_CODENAME:-?} / ${VERSION_ID:-?} → 发行版识别【可能失败】"
      warn "  若安装报'不支持的系统'，退路是官网下载离线安装包手工解包部署"
    fi
    printf '%s' "$probe" > /tmp/1panel-quick-start.sh
    ok "已存到 /tmp/1panel-quick-start.sh 供检查"
  fi

  log "5) 是否已装过 1Panel"
  u=$(unit_list)
  if [ -n "$u" ]; then warn "已存在单元：$(printf '%s' "$u" | tr '\n' ' ')"; else ok "未安装"; fi
}

# ---------------------------------------------------------------- 安装
# ★ 2026-10-10 读 v2.3.2 的 install.sh 源码后修正：它【支持完全非交互安装】，
#   所以这一步不需要你在自己终端里手动回答，脚本可以一次装完。
#   关键参数（都是读源码确认的真实参数，不是猜的）：
#     --non-interactive            不提问，未指定的项用默认值
#     --port                      面板端口（默认是随机的 10000~55535+10000，不是 8000）
#     --entrance                  安全入口（默认随机 10 位 md5）
#     --install-docker n          不让它装 Docker（我们已装）
#     --configure-accelerator n   不配它的 Docker 加速器
#     --replace-daemon-json n     ★ 绝不让它改写 /etc/docker/daemon.json
#                                  否则会把我们的镜像源（1ms.run 等）替换掉，
#                                  若换成 daocloud 会直接导致拉不到 jsyl6/* 的镜像
#   另外 install.sh 自己会执行 `ufw allow <面板端口>`（源码 933~938 行），
#   这破坏我们的加固 —— 所以 --post 一定会把这个放行删掉。
install_panel(){
  log "0) 准备安装包"
  V=$(curl -s --max-time 25 https://resource.fit2cloud.com/1panel/package/v2/stable/latest)
  [ -z "$V" ] && die "取不到最新版本号（网络问题）"
  ok "最新稳定版：$V"
  D="/opt/1panel-installer/1panel-${V}-linux-amd64"
  PKG="1panel-${V}-linux-amd64.tar.gz"
  mkdir -p /opt/1panel-installer
  if [ ! -f "/opt/1panel-installer/$PKG" ]; then
    log "    下载 $PKG（约 63MB，实测 CDN 15MB/s 约 4 秒）"
    ( cd /opt/1panel-installer && curl -LOk --max-time 300 --retry 2 \
        "https://resource.fit2cloud.com/1panel/package/v2/stable/$V/release/$PKG" )
  else
    ok "已存在本地安装包（复用）"
  fi
  [ -f "/opt/1panel-installer/$PKG" ] || die "下载失败"
  [ -d "$D" ] || tar zxf "/opt/1panel-installer/$PKG" -C /opt/1panel-installer
  [ -f "$D/install.sh" ] || die "解包失败，找不到 $D/install.sh"
  ok "已解包：$D"

  log "1) 生成面板凭据（随机强密码 + 随机安全入口）"
  PANEL_ENTRANCE="${PANEL_ENTRANCE:-$(head -c 16 /dev/urandom | md5sum | head -c 10)}"
  PANEL_USERNAME="${PANEL_USERNAME:-admin}"
  if [ -z "${PANEL_PASSWORD:-}" ]; then
    PANEL_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
  fi
  CRED=/opt/agent-platform-secure/1panel-credentials.txt
  mkdir -p /opt/agent-platform-secure && chmod 700 /opt/agent-platform-secure
  umask 077
  cat > "$CRED" <<EOF
# 1Panel 面板凭据（$(date '+%F %T') 生成，chmod 600）
# 访问：先开隧道 ssh -L $PANEL_PORT:127.0.0.1:$PANEL_PORT ubuntu@<服务器IP> -N
#       再开 http://127.0.0.1:$PANEL_PORT/$PANEL_ENTRANCE
PANEL_URL=http://127.0.0.1:$PANEL_PORT/$PANEL_ENTRANCE
PANEL_PORT=$PANEL_PORT
PANEL_ENTRANCE=$PANEL_ENTRANCE
PANEL_USERNAME=$PANEL_USERNAME
PANEL_PASSWORD=$PANEL_PASSWORD
EOF
  chmod 600 "$CRED"
  ok "凭据已存 $CRED（chmod 600，不入 git）"

  log "2) 执行非交互安装"
  echo "    端口=$PANEL_PORT  用户=$PANEL_USERNAME  安全入口=$PANEL_ENTRANCE"
  echo "    install-docker=n  configure-accelerator=n  replace-daemon-json=n"
  ( cd "$D" && bash install.sh --non-interactive --lang zh \
      --port "$PANEL_PORT" --entrance "$PANEL_ENTRANCE" \
      --username "$PANEL_USERNAME" --password "$PANEL_PASSWORD" \
      --install-docker n --configure-accelerator n --replace-daemon-json n )
  rc=$?
  [ $rc -ne 0 ] && die "install.sh 退出码 $rc，看 /opt/1panel/credentials/1panel.log 或安装目录的 install.log"

  log "3) 校验：Docker daemon.json 有没有被 1Panel 改掉（关键！）"
  if [ -f /etc/docker/daemon.json ]; then
    if grep -q '1ms.run' /etc/docker/daemon.json; then
      ok "我们的镜像源还在（1ms.run 命中）"
    else
      warn "⚠️ daemon.json 里没有 1ms.run —— 可能被覆盖了！内容："
      sed 's/^/      /' /etc/docker/daemon.json
      warn "  请手工把 registry-mirrors 改回 [\"https://docker.1ms.run\",\"https://hub.rat.dev\",\"https://mirror.ccs.tencentyun.com\"] 后 systemctl restart docker"
    fi
  fi

  echo
  warn "装完了，继续跑：  sudo bash $0 --post   （端口收口 + 内存上限）"
}

# ---------------------------------------------------------------- 装后加固
post(){
  log "1) 探测 1Panel 单元"
  u=$(unit_list)
  [ -z "$u" ] && die "没找到 1panel* 单元，安装可能没成功。先看 /opt/1panel/credentials/1panel.log"
  ok "单元：$(printf '%s' "$u" | tr '\n' ' ')"

  log "2) 取面板端口"
  real=""
  if command -v 1pctl >/dev/null 2>&1; then
    info=$(1pctl user-info 2>/dev/null)
    echo "    1pctl user-info:"
    printf '%s\n' "$info" | sed 's/^/      /'
    real=$(printf '%s' "$info" | grep -oE ':[0-9]{2,5}' | head -1 | tr -d ':')
  fi
  if [ -z "$real" ]; then
    warn "没自动解析到端口，使用 PANEL_PORT=$PANEL_PORT（若你装的时候填了别的，请 export PANEL_PORT=... 重跑 --post）"
    real="$PANEL_PORT"
  else
    ok "面板端口 = $real"
    [ "$real" = "8000" ] && warn "⚠️ 面板端口是 8000，和门户冲突！请在面板里改端口"
  fi

  log "3) ★ 端口收口：确保 ufw 不放行面板端口"
  if command -v ufw >/dev/null 2>&1; then
    if ufw status 2>/dev/null | grep -qE "^$real(/tcp)?[[:space:]]+ALLOW"; then
      ufw delete allow "$real/tcp" >/dev/null 2>&1 && ok "已删除 ufw 对 $real/tcp 的放行"
      ufw delete allow "$real"     >/dev/null 2>&1 || true
    else
      ok "ufw 未放行 $real（保持）"
    fi
    echo "    ufw 现状："
    ufw status numbered 2>/dev/null | sed 's/^/      /'
    ok "ufw 默认放行 lo，所以 SSH 隧道不受影响"
  else
    warn "没装 ufw，请自行确认安全组/防火墙没放行 $real"
  fi

  log "4) ★ 内存上限：给 1panel* 加 MemoryMax=$MEM_MAX"
  for s in $u; do
    d="/etc/systemd/system/$s.d"
    if grep -qs MemoryMax "$d/10-memlimit.conf" 2>/dev/null; then
      ok "$s 已有限制，跳过"
    else
      mkdir -p "$d"
      printf '[Service]\nMemoryMax=%s\n' "$MEM_MAX" > "$d/10-memlimit.conf"
      ok "$s → MemoryMax=$MEM_MAX"
    fi
  done
  systemctl daemon-reload
  ok "已 daemon-reload（下次启动或 systemctl restart 生效）"

  cat <<EOF

============================================================
访问方式（不对外开放任何端口）：

  在本机开一条隧道：
    ssh -L $real:127.0.0.1:$real ubuntu@49.234.49.192 -N

  然后本机浏览器打开：
    http://127.0.0.1:$real/<安全入口>
  （安全入口用 1pctl user-info 看）

下一步（方案文档 §3 第 ⑥ 步）：
  在面板里「容器 → 编排 → 创建 → 路径选择」，选中
    /opt/agent-platform/compose/docker-compose.yml
  ★ 必须由面板创建，否则它会归到 Local 来源、启停按钮是灰的
============================================================
EOF
}

# ---------------------------------------------------------------- 复查
verify(){
  log "1Panel 状态"
  u=$(unit_list); [ -z "$u" ] && { warn "未安装"; return 0; }
  for s in $u; do printf '    %-28s %s\n' "$s" "$(systemctl is-active "$s" 2>/dev/null)"; done
  echo
  echo "    已设内存上限："
  for s in $u; do v=$(systemctl show -p MemoryMax --value "$s" 2>/dev/null); printf '      %-28s %s\n' "$s" "${v:-（无）}"; done
  echo
  command -v 1pctl >/dev/null 2>&1 && { echo "    1pctl user-info:"; 1pctl user-info 2>/dev/null | sed 's/^/      /'; }
  echo
  echo "    ufw："; ufw status numbered 2>/dev/null | sed 's/^/      /'
  echo
  echo "    内存："; free -m | sed 's/^/      /'
}

case "$MODE" in
  guide)     preflight; install_panel ;;
  preflight) preflight ;;
  install)   install_panel ;;
  post)      post ;;
  verify)    verify ;;
esac
