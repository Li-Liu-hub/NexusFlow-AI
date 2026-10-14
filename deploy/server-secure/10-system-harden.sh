#!/bin/bash
# 10-system-harden.sh — 系统层加固（重装系统后、装中间件之前跑）
#
# 与 90-harden.sh 的分工：
#   本脚本 = 操作系统层（SSH / fail2ban / ufw / 自动更新 / 内核参数 / swap）
#   90 号  = 中间件层（MySQL / PG / Redis / RabbitMQ / Nacos 的密码与鉴权）
#
# 用法：python ssh_run_script.py 10-system-harden.sh --root
set -uo pipefail
export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=a

echo "########## 系统层加固 $(date '+%F %T') ##########"

############################################
echo; echo "########## [1/6] 基础包 + 自动安全更新 ##########"
############################################
apt-get update -qq
apt-get install -y -qq unattended-upgrades fail2ban ufw chrony ca-certificates curl jq

cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
systemctl enable --now unattended-upgrades >/dev/null 2>&1
echo "  自动安全更新：已开启"
systemctl is-active unattended-upgrades | sed 's/^/    unattended-upgrades: /'

# 时区对齐（取证与日志时间戳需要）
timedatectl set-timezone Asia/Shanghai 2>/dev/null || true
echo -n "  时区："; timedatectl show -p Timezone --value 2>/dev/null

############################################
echo; echo "########## [2/6] SSH 加固 ##########"
############################################
# 说明：保留密码登录（本机 ssh_helper.py 走密码认证），但禁 root 直登、限尝试次数
SSHD_HARDEN=/etc/ssh/sshd_config.d/99-harden.conf
cat > "${SSHD_HARDEN}" <<'EOF'
# ==== 加固（2026-10-01）====
# 保留密码认证：本机部署助手用密码连接
PasswordAuthentication yes
PermitRootLogin no
PermitEmptyPasswords no
MaxAuthTries 3
MaxSessions 5
LoginGraceTime 20
X11Forwarding no
AllowAgentForwarding no
ClientAliveInterval 300
ClientAliveCountMax 2
EOF

# 关键：改 sshd 配置后必须先语法校验，否则 reload 失败会把我们自己锁在外面
if sshd -t 2>/dev/null; then
  systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null
  echo "  sshd 配置校验通过并已重载"
  grep -E "^(PermitRootLogin|MaxAuthTries|PasswordAuthentication)" "${SSHD_HARDEN}" | sed 's/^/    /'
else
  echo "  !! sshd 配置校验失败，已回滚（不重载，避免锁死）"
  rm -f "${SSHD_HARDEN}"
fi

############################################
echo; echo "########## [3/6] fail2ban（防 SSH 爆破）##########"
############################################
# 上一台机器被尝试爆破 5594 次，这一层是必须的
cat > /etc/fail2ban/jail.local <<'EOF'
[DEFAULT]
bantime  = 3600
findtime = 600
maxretry = 5
# 不封自己的运维出口 IP 段
ignoreip = 127.0.0.1/8 ::1

[sshd]
enabled = true
port    = ssh
mode    = aggressive
EOF
systemctl enable fail2ban >/dev/null 2>&1
systemctl restart fail2ban
sleep 2
fail2ban-client status sshd 2>&1 | sed 's/^/    /'

############################################
echo; echo "########## [4/6] ufw 本机防火墙 ##########"
############################################
# 云防火墙管「包能不能到」，ufw 管「到了收不收」——两层不冲突。
# 用户要求不动控制台，所以收口全部在本机做。
ufw --force reset >/dev/null 2>&1
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp comment 'SSH' >/dev/null
ufw allow 8000/tcp comment 'frontend (nginx)' >/dev/null
ufw --force enable >/dev/null
echo "  默认策略：入站拒绝 / 出站放行；仅放行 22、8000"
ufw status numbered 2>/dev/null | sed 's/^/    /'

############################################
echo; echo "########## [5/6] 内核参数加固 ##########"
############################################
cat > /etc/sysctl.d/99-harden.conf <<'EOF'
# SYN flood 防护
net.ipv4.tcp_syncookies = 1
net.ipv4.tcp_syn_retries = 2
# 关闭重定向 / 源路由
net.ipv4.conf.all.accept_redirects = 0
net.ipv4.conf.all.send_redirects = 0
net.ipv4.conf.all.accept_source_route = 0
net.ipv4.conf.all.rp_filter = 1
# 忽略广播 ICMP / 记录可疑包
net.ipv4.icmp_echo_ignore_broadcasts = 1
net.ipv4.conf.all.log_martians = 1
# 内核信息仅 root 可读
kernel.dmesg_restrict = 1
# 内存压力时优先回收页缓存而不是杀进程（对 2C3.6G 这种小内存机器有意义）
vm.swappiness = 10
EOF
sysctl --system >/dev/null 2>&1
echo "  已应用 $(grep -c '=' /etc/sysctl.d/99-harden.conf) 条内核参数"
sysctl net.ipv4.tcp_syncookies vm.swappiness 2>/dev/null | sed 's/^/    /'

############################################
echo; echo "########## [6/6] swap 兜底 ##########"
############################################
# 说明：用户决定不升配。swap 不是内存的替代品（速度差两个数量级），
# 但它能把「系统级 OOM Killer 直接杀进程」变成「先变慢再变慢」，
# 对演示场景是可控的降级，而不是服务凭空消失。
CUR_SWAP=$(free -m | awk '/^Swap:/ {print $2}')
echo "  当前 swap：${CUR_SWAP} MB"
if [ "${CUR_SWAP}" -lt 3000 ]; then
  if [ ! -f /swapfile ]; then
    fallocate -l 4G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=4096 status=none
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
  fi
  swapon /swapfile 2>/dev/null || true
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo "  已挂载 /swapfile 并写入 fstab"
fi
free -m | sed 's/^/    /'

echo
echo "########## 系统层加固完成 $(date '+%F %T') ##########"
echo "DONE-SYSTEM-HARDEN"
