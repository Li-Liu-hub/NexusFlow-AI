#!/bin/bash
# ============================================================================
# 65-install-docker.sh —— 在服务器安装 Docker + 配置镜像源 + 防火墙收口
#
# 设计依据（全部实测，非推测）：
#   ① 服务器连不上 Docker Hub（registry-1.docker.io / auth.docker.io / hub.docker.com
#      DNS 能解析但 TCP 建连超时）→ 必须配国内 pull-through 镜像源
#   ② 镜像源只加速 pull 不能 push，且**不是所有源都代理用户命名空间**：
#      docker.1ms.run ✅ / hub.rat.dev ✅ / mirror.ccs.tencentyun.com ✅
#      docker.m.daocloud.io ❌ 拒绝 jsyl6（所以不能照抄开发机配置）
#   ③ dockerd 默认只监听 unix socket（不开 2375/2376）→ 不占现有任何 TCP 端口 → 零停机
#   ④ ★ Docker 安装时会把 iptables FORWARD 策略从 DROP 改成 ACCEPT，
#      并插入 DOCKER-USER / DOCKER 链 → 让 ufw 的 deny (routed) 失效。
#      Docker 的 DOCKER 链规则**优先于 ufw**，所以任何 publish 的端口都会**直接对公网开放**，
#      哪怕 ufw 没放行。这里用 DOCKER-USER 链（Docker 官方留给用户的钩子）补兜底：
#        只允许外部访问容器的 80（当前唯一公网入口），其余从 eth0 进来的转发流量全丢。
#      规则用 systemd 单元持久化（iptables 规则重启会丢，Docker 不会替我们重建它）。
#
# 用法：
#   sudo bash 65-install-docker.sh --preflight   # 只检查，不动系统
#   sudo bash 65-install-docker.sh --install
#   sudo bash 65-install-docker.sh --verify
# ============================================================================
set -uo pipefail

MODE="install"
for a in "$@"; do
  case "$a" in
    --preflight) MODE="preflight" ;;
    --install)   MODE="install" ;;
    --verify)    MODE="verify" ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "未知参数：$a" >&2; exit 2 ;;
  esac
done
[ "$(id -u)" -eq 0 ] || { echo "请用 root 运行：sudo bash $0 $*"; exit 1; }

IFACE="${IFACE:-eth0}"
ALLOW_PORTS="${ALLOW_PORTS:-80}"      # 允许从外部到达容器的端口（当前只有 nginx 的 80）
MIRRORS='["https://docker.1ms.run","https://hub.rat.dev","https://mirror.ccs.tencentyun.com"]'

log(){ printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok(){  printf '\033[1;32m    + %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m    ! %s\033[0m\n' "$*"; }
die(){ printf '\033[1;31m    ! %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 防火墙兜底规则
# 用 systemd 单元持久化。iptables 规则重启即丢，Docker 只重建它自己的链，不管我们加的。
FWRULE=/usr/local/sbin/docker-user-firewall.sh
FWUNIT=/etc/systemd/system/docker-user-firewall.service

write_fw_script(){
  cat > "$FWRULE" <<EOF
#!/bin/bash
# 由 65-install-docker.sh 生成：让 DOCKER-USER 链重新生效
# 目的：Docker 的转发规则会绕过 ufw，这里只放行容器对外需要的 $ALLOW_PORTS
set -u
IFACE=$IFACE

# ⚠️ 2026-10-10 修复（关键，别删）：必须放行「已建立连接的回程流量」。
#   原规则只放行「目标是 $ALLOW_PORTS 的新连接」，但外网回来的响应包入接口同样是 eth0、
#   目标端口却是**高位随机端口**（不匹配 $ALLOW_PORTS）→ 命中下面最后的 DROP →
#   容器能发出请求却收不到任何响应。症状：容器内所有外部 HTTPS 调用超时
#   （LLM 调用全部挂起、AI 对话接口无限等待，而 LLM/网络本身完全正常）。
#   安全意图不变：**新**的入站连接仍然只放行 $ALLOW_PORTS。
iptables -C DOCKER-USER -i "\$IFACE" -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN 2>/dev/null || \\
  iptables -I DOCKER-USER 1 -i "\$IFACE" -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN

for p in $(printf '%s' "$ALLOW_PORTS" | tr ',' ' '); do
  iptables -C DOCKER-USER -i "\$IFACE" -p tcp --dport "\$p" -j RETURN 2>/dev/null || \\
    iptables -I DOCKER-USER 1 -i "\$IFACE" -p tcp --dport "\$p" -j RETURN
done
iptables -C DOCKER-USER -i "\$IFACE" -j DROP 2>/dev/null || \\
  iptables -A DOCKER-USER -i "\$IFACE" -j DROP
exit 0
EOF
  chmod 755 "$FWRULE"
}

write_fw_unit(){
  cat > "$FWUNIT" <<EOF
[Unit]
Description=DOCKER-USER firewall hardening (only allow $ALLOW_PORTS inbound to containers)
After=docker.service network-online.target
Wants=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=$FWRULE

[Install]
WantedBy=multi-user.target
EOF
  chmod 644 "$FWUNIT"
}

# ---------------------------------------------------------------- 装前检查
preflight(){
  log "1) 现状"
  . /etc/os-release 2>/dev/null || true
  echo "    发行版 : ${PRETTY_NAME:-?}  内核 $(uname -r)"
  awk '/MemTotal/{t=$2} /MemAvailable/{a=$2} END{printf "    内存   : 总 %.0f MB / 可用 %.0f MB\n", t/1024, a/1024}' /proc/meminfo
  if command -v docker >/dev/null 2>&1; then warn "已装 docker：$(docker --version)"; else ok "未装 docker（符合预期）"; fi

  log "2) 网段冲突检查（Docker 默认 172.17.0.0/16；本项目 compose 计划 172.28.0.0/16）"
  ip -4 -o addr show | awk '{print "    "$2" "$4}'
  if ip route | grep -qE '^172\.1[0-9]\.|^172\.2[0-9]\.'; then warn "已有 172.x 路由，注意冲突"; else ok "无 172.x 路由占用，两个网段都干净"; fi

  log "3) 防火墙现状（Docker 会动 FORWARD）"
  echo "    ufw: $(ufw status 2>/dev/null | head -1)"
  ufw status 2>/dev/null | awk 'NR>4 && NF{print "      "$0}'
  echo "    -P FORWARD $(iptables -S FORWARD 2>/dev/null | awk '/^-P/{print $3}')"
  if iptables -L DOCKER-USER -n >/dev/null 2>&1; then
    warn "DOCKER-USER 链已存在 → Docker 已装过"
  else
    ok "DOCKER-USER 链不存在 → Docker 未装（确认）"
  fi

  log "4) 镜像源可达性（只测 pull 侧）"
  for m in docker.1ms.run hub.rat.dev mirror.ccs.tencentyun.com docker.m.daocloud.io; do
    printf "    %-32s " "$m"
    curl -s -o /dev/null -w "HTTP=%{http_code} %{time_total}s\n" --max-time 12 "https://$m/v2/" 2>/dev/null || echo "连不上"
  done
  warn "注意：daocloud 可达但实测【拒绝 jsyl6 命名空间】，不能作为主源"

  log "5) 关键约束提醒"
  echo "    · 容器 publish 的端口会【绕过 ufw】直接对公网开放"
  echo "    · 所以本脚本只放行容器的 $ALLOW_PORTS，其余在 DOCKER-USER 里 DROP"
  echo "    · 以后若要新增对外端口，必须同时改 ALLOW_PORTS 重跑本脚本"
}

# ---------------------------------------------------------------- 安装
install_docker(){
  log "1) 安装 docker-ce（腾讯云镜像，不触发其它服务重启）"
  export NEEDRESTART_MODE=l DEBIAN_FRONTEND=noninteractive
  install -m0755 -d /etc/apt/keyrings
  if [ ! -f /etc/apt/keyrings/docker.asc ]; then
    curl -fsSL https://mirrors.cloud.tencent.com/docker-ce/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
      || curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
      || die "下载 Docker GPG key 失败"
    chmod a+r /etc/apt/keyrings/docker.asc
  fi
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://mirrors.cloud.tencent.com/docker-ce/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  # 腾讯镜像可能没有 26.04(resolute) 的目录 → 回退到 noble
  if ! curl -fsI --max-time 15 "https://mirrors.cloud.tencent.com/docker-ce/linux/ubuntu/dists/${VERSION_CODENAME}/Release" >/dev/null 2>&1; then
    warn "腾讯镜像没有 ${VERSION_CODENAME} 源，回退到 noble"
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://mirrors.cloud.tencent.com/docker-ce/linux/ubuntu noble stable" \
      > /etc/apt/sources.list.d/docker.list
  fi
  apt-get update -qq || die "apt-get update 失败"
  apt-get install -y -qq --no-install-recommends \
    docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin \
    || die "安装 docker 失败"
  ok "$(docker --version)"
  ok "$(docker compose version 2>/dev/null | head -1)"

  log "2) 写 /etc/docker/daemon.json（镜像源 + 日志轮转）"
  mkdir -p /etc/docker
  if [ -f /etc/docker/daemon.json ] && grep -q '1ms.run' /etc/docker/daemon.json; then
    ok "已存在且含我们的镜像源，保持不动"
  else
    [ -f /etc/docker/daemon.json ] && cp -a /etc/docker/daemon.json "/etc/docker/daemon.json.bak.$(date +%s)"
    cat > /etc/docker/daemon.json <<EOF
{
  "registry-mirrors": $MIRRORS,
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
    ok "已写入：$MIRRORS"
  fi

  log "3) 启动 Docker"
  systemctl enable --now docker >/dev/null 2>&1
  sleep 5
  docker info >/dev/null 2>&1 || die "docker 启动失败：journalctl -u docker -n50"
  ok "docker 已运行，DataRoot=$(docker info --format '{{.DockerRootDir}}')"
  echo "    docker0: $(ip -4 -o addr show docker0 2>/dev/null | awk '{print $4}')"
  echo "    镜像源生效："
  docker info 2>/dev/null | sed -n '/Registry Mirrors/,/^$/p' | sed 's/^/      /'

  log "4) ★ 防火墙收口：写 DOCKER-USER 兜底规则"
  if ! iptables -L DOCKER-USER -n >/dev/null 2>&1; then
    die "DOCKER-USER 链不存在（Docker 未正常初始化 iptables），请检查 iptables 后端"
  fi
  write_fw_script
  write_fw_unit
  systemctl daemon-reload
  systemctl enable --now docker-user-firewall.service >/dev/null 2>&1
  ok "规则已应用并持久化（$FWUNIT）"

  verify
}

# ---------------------------------------------------------------- 复查
verify(){
  log "Docker"
  if command -v docker >/dev/null 2>&1; then
    echo "    版本   : $(docker --version)"
    echo "    运行   : $(systemctl is-active docker)"
    echo "    数据盘 : $(docker info --format '{{.DockerRootDir}}')"
    echo "    镜像源 : $(docker info 2>/dev/null | sed -n '/Registry Mirrors/,/^$/p' | grep -c 'https') 个"
    echo "    磁盘   : $(df -h /var/lib/docker 2>/dev/null | tail -1 | awk '{print $4" 可用"}')"
  else
    warn "未安装"
  fi

  log "防火墙（关键）"
  echo "    -P FORWARD      : $(iptables -S FORWARD 2>/dev/null | awk '/^-P/{print $3}')  ← Docker 会改成 ACCEPT，故下面必须有规则"
  echo "    DOCKER-USER 链  :"
  iptables -L DOCKER-USER -n --line-numbers 2>/dev/null | sed 's/^/      /' || warn "链不存在"
  echo "    持久化单元      : $(systemctl is-enabled docker-user-firewall.service 2>/dev/null) / $(systemctl is-active docker-user-firewall.service 2>/dev/null)"
  echo "    ufw             : $(ufw status 2>/dev/null | head -1)"

  log "网段"
  ip -4 -o addr show | awk '{print "    "$2" "$4}'

  echo
  echo "  下一步："
  echo "    1) 构建并推送镜像（本机）→ sudo -E REGISTRY=jsyl6 TAG=20261010 bash deploy-from-registry.sh --full"
  echo "    2) 或先只拉中间件镜像做验证：docker pull mysql:8.4"
  echo "    3) 装 1Panel：sudo bash 70-install-1panel.sh --install"
}

case "$MODE" in
  preflight) preflight ;;
  install)   preflight; install_docker ;;
  verify)    verify ;;
esac
