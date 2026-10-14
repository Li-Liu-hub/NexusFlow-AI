#!/bin/bash
# stack-status.sh — one-glance health check for the agent-platform middleware stack
# installed on the server as /usr/local/bin/stack-status.sh
export LANG=C
HOSTIP="$(curl -s -m 5 http://metadata.tencentyun.com/latest/meta-data/public-ipv4 2>/dev/null || echo 127.0.0.1)"

line() { printf '%-16s %-9s %-22s %s\n' "$1" "$2" "$3" "$4"; }

echo "=================== agent-platform middleware ==================="
printf '%-16s %-9s %-22s %s\n' SERVICE ACTIVE LISTEN REMARK
printf '%s\n' "--------------------------------------------------------------------------"

# PostgreSQL
PG=$(systemctl is-active postgresql 2>/dev/null)
line "PostgreSQL" "$PG" "$(ss -tlnH 'sport = :5432' 2>/dev/null | awk '{print $4}' | paste -sd, -)" \
  "db=terminal_kb user=my_user pw=123456"

# MySQL
MY=$(systemctl is-active mysql 2>/dev/null)
line "MySQL" "$MY" "$(ss -tlnH 'sport = :3306' 2>/dev/null | awk '{print $4}' | paste -sd, -)" \
  "user=root pw=123456 db=api_db"

# Redis
RD=$(systemctl is-active redis-server 2>/dev/null)
line "Redis" "$RD" "$(ss -tlnH 'sport = :6379' 2>/dev/null | awk '{print $4}' | paste -sd, -)" \
  "no password, db=0"

# RabbitMQ
RB=$(systemctl is-active rabbitmq-server 2>/dev/null)
line "RabbitMQ" "$RB" "$(ss -tlnH '( sport = :5672 or sport = :15672 )' 2>/dev/null | awk '{print $4}' | paste -sd, -)" \
  "user=guest pw=guest"

# Nacos
NA=$(systemctl is-active nacos 2>/dev/null)
line "Nacos" "$NA" "$(ss -tlnH '( sport = :8848 or sport = :9848 or sport = :9849 )' 2>/dev/null | awk '{print $4}' | paste -sd, -)" \
  "standalone, auth off"

echo
echo "----------------- functional probes -----------------"
echo -n "PostgreSQL  : "
PGPASSWORD=123456 psql -h 127.0.0.1 -U my_user -d terminal_kb -tAc \
  "SELECT 'ok, vector '||extversion FROM pg_extension WHERE extname='vector';" 2>&1 | head -1

echo -n "MySQL       : "
mysql -uroot -p123456 -N -e "SELECT CONCAT('ok, ', COUNT(*), ' interfaces') FROM api_db.interface_info;" 2>/dev/null || echo "fail"

echo -n "Redis       : "
redis-cli ping 2>&1 | head -1

echo -n "RabbitMQ    : "
RV=$(curl -s -m 8 -u guest:guest http://127.0.0.1:15672/api/overview 2>/dev/null \
  | sed -n 's/.*"rabbitmq_version":"\([^"]*\)".*/ok, \1/p')
echo "${RV:-fail}"

echo -n "Nacos       : "
NV=$(curl -s -m 8 http://127.0.0.1:8848/nacos/v1/console/health/readiness 2>/dev/null)
echo "${NV:-fail}"

echo
echo "----------------- resources -----------------"
free -h | head -2
df -h / | tail -1
echo
echo "public ip: ${HOSTIP}"
echo "boot autostart: postgresql=$(systemctl is-enabled postgresql 2>/dev/null) mysql=$(systemctl is-enabled mysql 2>/dev/null) redis-server=$(systemctl is-enabled redis-server 2>/dev/null) rabbitmq-server=$(systemctl is-enabled rabbitmq-server 2>/dev/null) nacos=$(systemctl is-enabled nacos 2>/dev/null)"
echo "================================================================"
