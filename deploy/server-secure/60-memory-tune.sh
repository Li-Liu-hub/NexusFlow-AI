#!/usr/bin/env bash
# ============================================================================
# 60-memory-tune.sh — 服务器内存优化（依据 2026-10-10 实测诊断）
# 配套文档：内存优化诊断-2026-10-10.md
#
#   sudo bash 60-memory-tune.sh --dry-run   # 只打印将要做的动作，不改任何东西（先跑这个）
#   sudo bash 60-memory-tune.sh             # 默认 = 档1：改配置 + 重启服务，不重启机器
#   sudo bash 60-memory-tune.sh --stage1
#   sudo bash 60-memory-tune.sh --stage2    # 关 kdump + 去掉 crashkernel 预留（需重启机器）
#   sudo bash 60-memory-tune.sh --all
#
# 幂等：所有替换前先 grep 判断，重复执行不会重复插入；替换未命中会自动还原。
# 回滚：所有被改动的单元文件与 postgresql.conf 都备份在 /opt/backup/memory-tune-<时间戳>/
# ============================================================================
set -uo pipefail

DRY=0; DO_S1=0; DO_S2=0
for a in "$@"; do
  case "$a" in
    --dry-run) DRY=1 ;;
    --stage1)  DO_S1=1 ;;
    --stage2)  DO_S2=1 ;;
    --all)     DO_S1=1; DO_S2=1 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "未知参数：$a（可用 --dry-run --stage1 --stage2 --all）" >&2; exit 2 ;;
  esac
done
if [ $DO_S1 -eq 0 ] && [ $DO_S2 -eq 0 ]; then DO_S1=1; fi
if [ "$(id -u)" -ne 0 ]; then echo "请用 root 运行：sudo bash $0 $*"; exit 1; fi

STAMP=$(date +%Y%m%d-%H%M%S)
BK="/opt/backup/memory-tune-$STAMP"

log(){  printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
ok(){   printf '\033[1;32m    + %s\033[0m\n' "$*"; }
mem(){  awk '/MemTotal/{t=$2} /MemAvailable/{a=$2} /SwapFree/{s=$2} END{printf "    MemTotal=%.0fMB  MemAvailable=%.0fMB  SwapFree=%.0fMB\n", t/1024, a/1024, s/1024}' /proc/meminfo; }

wait_port(){
  local p="$1" n="${2:-40}" i=0
  while [ "$i" -lt "$n" ]; do
    if (exec 3<>/dev/tcp/127.0.0.1/"$p") 2>/dev/null; then exec 3>&- ; return 0; fi
    sleep 2; i=$((i+1))
  done
  return 1
}

# 幂等替换：patch_unit <单元文件> <校验标记> <sed 脚本>
patch_unit(){
  local f="$1" marker="$2" script="$3"
  if [ ! -f "$f" ]; then warn "找不到单元文件：$f（跳过）"; return 0; fi
  if grep -q -- "$marker" "$f"; then ok "已优化过，跳过：$(basename "$f")"; return 0; fi
  if [ "$DRY" -eq 1 ]; then
    printf '    [dry-run] cp -a %s %s/\n    [dry-run] sed -i %s %s\n' "$f" "$BK" "'$script'" "$f"
    return 0
  fi
  cp -a "$f" "$BK/"
  sed -i "$script" "$f"
  if grep -q -- "$marker" "$f"; then
    ok "已修改 $(basename "$f")  ->  $(grep -o -- '-Xm[sx][^ ]*\|-XX:[^ ]*' "$f" | tr '\n' ' ')"
  else
    warn "替换未命中（文件内容与预期不符），已还原：$(basename "$f")"
    cp -a "$BK/$(basename "$f")" "$f"
  fi
}

echo "=============================================================="
echo " 60-memory-tune.sh     DRY-RUN=$DRY   stage1=$DO_S1   stage2=$DO_S2"
echo " 备份目录: $BK"
echo "=============================================================="
echo "[改前]"; mem

if [ "$DRY" -eq 0 ]; then mkdir -p "$BK"; fi

# ---------------------------------------------------------------------------
if [ $DO_S1 -eq 1 ]; then

log "1) Python 编排层：MALLOC_ARENA_MAX=2   预期 -150~250MB"
echo "    说明：治 glibc malloc arena 碎片（实测 3 线程却有 6 个 40~87MB 匿名映射）"
D=/etc/systemd/system/aismp-orchestrator.service.d
if [ "$DRY" -eq 1 ]; then
  printf '    [dry-run] mkdir -p %s\n    [dry-run] 写入 %s/10-memory.conf:\n              [Service]\n              Environment=MALLOC_ARENA_MAX=2\n              Environment=MALLOC_TRIM_THRESHOLD_=65536\n' "$D" "$D"
elif grep -qs MALLOC_ARENA_MAX "$D/10-memory.conf" 2>/dev/null; then
  ok "已存在 $D/10-memory.conf，跳过"
else
  mkdir -p "$D"
  printf '# 60-memory-tune.sh 生成：限制 glibc malloc arena 数量 + 更积极归还内存\n[Service]\nEnvironment=MALLOC_ARENA_MAX=2\nEnvironment=MALLOC_TRIM_THRESHOLD_=65536\n' > "$D/10-memory.conf"
  ok "已写入 $D/10-memory.conf"
fi

log "2) 4 个业务 JVM：C1-only + 大幅降 Xmx + SerialGC   预期 -250~380MB"
echo "    演示场景（单用户），Xmx 按实测堆用量 ×2~3 留余量：gateway 256->128 / backend 256->192 / kernel 384->192 / agent 384->256"
patch_unit /etc/systemd/system/aismp-gateway.service       TieredStopAtLevel \
  's#-Xmx256m -XX:MaxMetaspaceSize=160m -XX:+UseG1GC#-Xmx128m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:TieredStopAtLevel=1#'
patch_unit /etc/systemd/system/aismp-backend.service       TieredStopAtLevel \
  's#-Xmx256m -XX:MaxMetaspaceSize=160m -XX:+UseG1GC#-Xmx192m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:TieredStopAtLevel=1#'
patch_unit /etc/systemd/system/aismp-digital-human.service TieredStopAtLevel \
  's#-Xmx384m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC#-Xmx192m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1#'
patch_unit /etc/systemd/system/aismp-agent.service         TieredStopAtLevel \
  's#-Xmx384m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC#-Xmx256m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:TieredStopAtLevel=1#'
echo "    注：agent 保留 256m（Spring AI + 向量检索，堆用量 79MB/committed 178MB，是 5 个里唯一需要留余量的）"

log "3) Nacos：Xms128m / Xmx192m / 去掉 -Xmn / C1-only   预期 -60~110MB"
patch_unit /etc/systemd/system/nacos.service TieredStopAtLevel \
  's#-Xms256m -Xmx256m -Xmn128m#-Xms128m -Xmx192m -XX:TieredStopAtLevel=1#'

log "4) PostgreSQL：idle_session_timeout=5min   预期 -30~50MB（仅 reload，不重启 PG）"
if [ "$DRY" -eq 0 ]; then
  cur=$(sudo -u postgres psql -tAc "show idle_session_timeout;" 2>/dev/null || echo "?")
  if [ "$cur" = "5min" ]; then
    ok "已是 5min，跳过"
  else
    cp -a /etc/postgresql/18/main/postgresql.conf "$BK/" 2>/dev/null || true
    if sudo -u postgres psql -c "ALTER SYSTEM SET idle_session_timeout='5min';" >/dev/null 2>&1; then
      ok "已写入 postgresql.auto.conf"
      systemctl reload postgresql@18-main && ok "已 reload PostgreSQL（无需重启）" || warn "reload 失败，请手工 systemctl reload postgresql@18-main"
    else
      warn "ALTER SYSTEM 失败（当前值=$cur），跳过"
    fi
  fi
else
  printf '    [dry-run] sudo -u postgres psql -c "ALTER SYSTEM SET idle_session_timeout='\''5min'\'';"\n'
  printf '    [dry-run] systemctl reload postgresql@18-main\n'
fi

log "5) 关闭云主机上无用的守护进程   预期 -50~90MB"
for s in multipathd ModemManager udisks2 apport sysstat; do
  printf '    -> disable --now %s\n' "$s"
  if [ "$DRY" -eq 0 ]; then systemctl disable --now "$s" >/dev/null 2>&1 || true; fi
done
for s in fwupd fwupd-refresh.timer upower; do
  printf '    -> mask --now %s%s\n' "$s" "$( [ "$s" = fwupd ] && echo '   (static 单元，disable 无效，必须 mask)' || echo '')"
  if [ "$DRY" -eq 0 ]; then systemctl mask --now "$s" >/dev/null 2>&1 || true; fi
done
echo "    保留不动：unattended-upgrades / fail2ban / ufw / 云镜 YunJing / stargate(监控曲线)"

log "6) daemon-reload + 按序重启服务"
if [ "$DRY" -eq 1 ]; then
  printf '    [dry-run] systemctl daemon-reload\n'
  printf '    [dry-run] systemctl restart nacos ；等待 8848 就绪\n'
  printf '    [dry-run] systemctl restart aismp-agent aismp-digital-human aismp-backend aismp-gateway\n'
  printf '    [dry-run] systemctl restart aismp-orchestrator\n'
else
  systemctl daemon-reload
  echo "    -- 重启 Nacos（先起注册中心）"
  systemctl restart nacos
  wait_port 8848 60 && ok "Nacos 8848 已就绪" || warn "Nacos 8848 未在 120s 内就绪，检查：journalctl -u nacos -n50"
  for u in aismp-agent aismp-digital-human aismp-backend aismp-gateway; do
    echo "    -- 重启 $u"
    systemctl restart "$u"
  done
  for p in "aismp-agent:8124" "aismp-digital-human:8126" "aismp-backend:7529" "aismp-gateway:8090"; do
    if wait_port "${p#*:}" 45; then ok "${p%:*} 端口 ${p#*:} 已就绪"; else warn "${p%:*} 端口 ${p#*:} 未就绪，检查：journalctl -u ${p%:*} -n50"; fi
  done
  echo "    -- 重启 Python 编排层"
  systemctl restart aismp-orchestrator
  wait_port 8125 30 && ok "orchestrator 8125 已就绪" || warn "orchestrator 8125 未就绪，检查：journalctl -u aismp-orchestrator -n50"
fi

fi

# ---------------------------------------------------------------------------
if [ $DO_S2 -eq 1 ]; then

log "7) 关闭 kdump + 去掉 crashkernel 预留   预期 +192MB（★ 需重启机器才生效）"
if [ "$DRY" -eq 1 ]; then
  printf '    [dry-run] systemctl disable --now kdump-tools\n'
  printf '    [dry-run] cp -a /etc/default/grub %s/\n' "$BK"
  printf '    [dry-run] sed -i "s#crashkernel=[^ ]*##" /etc/default/grub\n'
  printf '    [dry-run] update-grub\n'
  printf '    [dry-run] 之后需手动 reboot 才能释放 192MB\n'
elif grep -q 'crashkernel=' /proc/cmdline 2>/dev/null; then
  systemctl disable --now kdump-tools >/dev/null 2>&1 || true
  ok "kdump-tools 已停用"
  cp -a /etc/default/grub "$BK/"
  sed -i 's#crashkernel=[^ ]*##' /etc/default/grub
  if grep -q 'crashkernel=' /etc/default/grub; then warn "GRUB 里仍有 crashkernel=，请手工检查 /etc/default/grub"; else ok "已从 GRUB 移除 crashkernel="; fi
  if command -v update-grub >/dev/null 2>&1; then
    update-grub >/dev/null 2>&1 && ok "update-grub 完成" || warn "update-grub 失败，请手工执行"
  else
    grub-mkconfig -o /boot/grub/grub.cfg >/dev/null 2>&1 && ok "grub-mkconfig 完成" || warn "更新 grub 失败，请手工执行"
  fi
  warn "!! 这 192MB 只有【重启服务器】后才会释放；请在维护窗口执行 sudo reboot"
  warn "!! 重启后请复查 /proc/cmdline 是否被腾讯云 cloud-init 写回 crashkernel="
else
  ok "cmdline 里已无 crashkernel=，跳过"
fi

fi

# ---------------------------------------------------------------------------
echo
echo "=============================================================="
echo "[改后]"; mem
echo "=============================================================="
echo "后续手工步骤："
if [ $DO_S1 -eq 1 ] && [ "$DRY" -eq 0 ]; then
  echo "  1. 观察 Python 编排层是否仍碎片化："
  echo "       grep VmRSS /proc/\$(pgrep -f 'uvicorn server:app')/status"
  echo "       pmap -x \$(pgrep -f 'uvicorn server:app') | sort -k3 -nr | head -8"
  echo "  2. 复查各 JVM 的 code cache 是否降到 ~8MB："
  echo "       /usr/lib/jvm/java-21-openjdk-amd64/bin/jcmd <pid> Compiler.codecache"
  echo "  3. 若 aismp-agent 日志出现 OutOfMemoryError 或频繁 Full GC，把它的 -Xmx256m 回调到 320m"
fi
if [ $DO_S2 -eq 1 ]; then
  echo "  4. 维护窗口执行 sudo reboot，重启后 free -h 应看到 MemTotal ≈ 3.82GiB"
fi
echo "回滚：cp -a $BK/. /etc/systemd/system/ && systemctl daemon-reload && systemctl restart ..."
echo "      （单元文件原名保存在 $BK/）"
