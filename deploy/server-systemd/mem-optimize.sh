#!/bin/bash
# ============================================================
# mem-optimize.sh · 2C/3.7G 轻量服务器内存优化（2026-10-07 已执行）
#
# 背景：k3s 控制面（~1G）叠加 11 个工作负载，需求 ~5.3G > 物理 3.7G，
#   全靠 6G swap 硬扛 → I/O 饥饿 → sshd 无法 fork（banner exchange 超时）、
#   门户响应 16.9s。已确认根因并按下述方案落地。
#
# 结果（实测）：
#   门户 /portal.html 响应 16.9s → 0.11s
#   内存 used 1562MB（仅 k3s + nginx）→ 2.97GB（全套 11 服务在跑）
#   available 745MB，swap 活动 si/so = 0
#
# 三个杠杆：
#   1) 卸载 k3s，服务交回 systemd —— 释放 ~1G，最大单项收益
#   2) 清理 k3s 遗留的孤儿容器（containerd 被杀后 shim 存活，仍占着 5432/6379/5672）
#   3) MySQL / PG 固定开销压缩
#   （各 JVM 堆此前已限过：backend/gateway 256m、agent/digital-human 384m、nacos 256m）
#
# 用法：sudo bash mem-optimize.sh
# 回滚：被改配置均留 <file>.bak.<时间戳>。
# 注：本项目最终采用「全量 Docker」路线（deploy/server-docker/），
#     早期的 k3s 方案已废弃并删除，故此处不再涉及 kubectl 恢复说明。
# ============================================================
set -u

echo "########## 优化前 ##########"
free -m | head -3
uptime

# ---------- 1) 卸载 k3s ----------
echo
echo "--- [1/5] 卸载 k3s ---"
if systemctl list-unit-files 2>/dev/null | grep -q '^k3s\.service'; then
  sudo systemctl disable --now k3s 2>/dev/null || true
  sleep 8
  if [ -x /usr/local/bin/k3s-uninstall.sh ]; then
    sudo /usr/local/bin/k3s-uninstall.sh >/dev/null 2>&1 || true
  fi
  echo "  k3s 已禁用并卸载"
else
  echo "  （未安装 k3s，跳过）"
fi

# ---------- 2) 清理 k3s 孤儿容器 ----------
# stop k3s 只杀 containerd，容器进程会被 reparent 到 PID 1 继续存活并占着端口。
echo
echo "--- [2/5] 清理孤儿容器（否则抢 5432/6379/5672 端口）---"
sudo pkill -f containerd-shim 2>/dev/null || true
sleep 6
leftover=$(ps -eo comm | grep -cE 'containerd-shim|k3s' || true)
echo "  残留容器进程数=$leftover"
sudo rm -rf /var/lib/rancher 2>/dev/null || true

# ---------- 3) 运行时目录 ----------
echo
echo "--- [3/5] 重建运行时目录 ---"
sudo mkdir -p /var/run/mysqld && sudo chown mysql:mysql /var/run/mysqld
sudo mkdir -p /var/run/postgresql && sudo chown postgres:postgres /var/run/postgresql
echo "  ok"

# ---------- 4) 中间件开销压缩 ----------
echo
echo "--- [4/5] MySQL / PG 开销压缩 ---"
MY=/etc/mysql/mysql.conf.d/mysqld.cnf
sudo cp -a "$MY" "$MY.bak.$(date +%s)"
if grep -qE '^[[:space:]]*performance_schema' "$MY"; then
  sudo sed -i -E 's/^[[:space:]]*performance_schema[[:space:]]*=.*/performance_schema = OFF/' "$MY"
else
  echo 'performance_schema = OFF' | sudo tee -a "$MY" >/dev/null
fi
if grep -qE '^[[:space:]]*innodb_buffer_pool_size' "$MY"; then
  sudo sed -i -E 's/^[[:space:]]*innodb_buffer_pool_size.*/innodb_buffer_pool_size = 128M/' "$MY"
else
  echo 'innodb_buffer_pool_size = 128M' | sudo tee -a "$MY" >/dev/null
fi
echo "  MySQL: performance_schema=OFF, innodb_buffer_pool_size=128M"

PGC=/etc/postgresql/18/main/postgresql.conf
sudo cp -a "$PGC" "$PGC.bak.$(date +%s)"
sudo sed -i -E 's/^[#[:space:]]*shared_buffers[[:space:]]*=.*/shared_buffers = 96MB/' "$PGC"
echo "  PG: shared_buffers=96MB"

sudo sysctl -w vm.swappiness=10 >/dev/null 2>&1 || true
echo "  vm.swappiness=$(cat /proc/sys/vm/swappiness)"

sudo systemctl daemon-reload

# ---------- 5) 按依赖顺序拉起并设为开机自启 ----------
echo
echo "--- [5/5] 启动服务（数据库 → 注册中心 → 应用）---"
for u in mysql postgresql redis-server rabbitmq-server; do
  sudo systemctl enable "$u" >/dev/null 2>&1
  sudo systemctl restart "$u"
  printf '  %-22s %s\n' "$u" "$(systemctl is-active "$u")"
done
sleep 15

# nacos 用 MySQL 做存储，必须等 MySQL 起来再拉
sudo systemctl enable nacos >/dev/null 2>&1
sudo systemctl restart nacos
printf '  %-22s %s\n' nacos "$(systemctl is-active nacos)"
sleep 45

for u in nginx aismp-backend aismp-gateway aismp-agent aismp-digital-human agent-orchestrator; do
  sudo systemctl enable "$u" >/dev/null 2>&1
  sudo systemctl restart "$u"
  printf '  %-22s %s\n' "$u" "$(systemctl is-active "$u")"
done

echo
echo "########## 结果 ##########"
sleep 30
sudo ss -tlnH | awk '{print $4}' | grep -oE '[0-9]+$' | sort -un | tr '\n' ' '
echo
free -m
ps -eo rss,comm --sort=-rss | head -12 | awk 'NR==1{print "  RSS(MB)  CMD"} NR>1{printf "  %7.0f  %s\n",$1/1024,$2}'
echo "MEM-OPTIMIZE-DONE"
