#!/bin/bash
# 01-install.sh — install the middleware stack
#
# 适配系统：Ubuntu 26.04 LTS（2026-10-01 重装后的实际版本）
#   注意：26.04 的默认软件版本比 24.04 高一个大版本，包名必须跟着改：
#     PostgreSQL  16 → 18（pgvector 包名随之变为 postgresql-18-pgvector）
#     MySQL 8.0 → 8.4 / Redis 7 → 8.0 / RabbitMQ 3.12 → 4.0
#   三个 JDK 都要装：JDK 8（backend/gateway/interface）、17（Nacos）、21（aismp-agent）
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=a
export UCF_FORCE_CONFFNEW=1

echo "=== [0/2] os-release ==="
grep -E "^(PRETTY_NAME|VERSION_ID)=" /etc/os-release

echo "=== [1/2] apt-get update ==="
apt-get update -qq

echo "=== [2/2] apt-get install ==="
apt-get install -y --no-install-recommends \
  curl wget unzip tar ca-certificates gnupg lsb-release jq \
  mysql-server \
  redis-server \
  rabbitmq-server \
  postgresql-18 postgresql-18-pgvector postgresql-contrib \
  openjdk-8-jre-headless openjdk-17-jre-headless openjdk-21-jre-headless

echo
echo "=== versions ==="
echo -n "mysql : "; mysqld --version || true
echo -n "redis : "; redis-server --version || true
echo -n "rabbit: "; (rabbitmqctl version 2>/dev/null | tail -1) || true
echo -n "psql  : "; psql --version || true
echo -n "java  : "; java -version 2>&1 | head -1 || true
echo "--- 已安装的 JDK ---"
ls -d /usr/lib/jvm/*/ 2>/dev/null | sed 's/^/  /'

echo
echo "=== enabled services ==="
systemctl is-enabled mysql redis-server rabbitmq-server postgresql 2>&1 || true
echo "=== active services ==="
systemctl is-active mysql redis-server rabbitmq-server postgresql 2>&1 || true

echo
echo "DONE-INSTALL"
