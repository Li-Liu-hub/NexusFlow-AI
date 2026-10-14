#!/bin/bash
# 远端侦查脚本（只读）：数据库/内存/进程
set -uo pipefail
. /opt/agent-platform-secure/credentials.env

echo "=== MySQL 库 ==="
mysql -uroot -p"$MYSQL_ROOT_PW" -N -e "SHOW DATABASES;" 2>/dev/null

echo "=== api_db 关键表行数 ==="
mysql -uroot -p"$MYSQL_ROOT_PW" -N -e "SELECT 'interface_info', COUNT(*) FROM api_db.interface_info UNION ALL SELECT 'terminal', COUNT(*) FROM api_db.terminal UNION ALL SELECT 'user', COUNT(*) FROM api_db.user;" 2>/dev/null

echo "=== terminal_db 表数 ==="
mysql -uroot -p"$MYSQL_ROOT_PW" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='terminal_db';" 2>/dev/null

echo "=== PG 库 ==="
sudo -u postgres psql -tAc "SELECT datname FROM pg_database;" 2>/dev/null

echo "=== 内存 TOP 12（RSS） ==="
ps aux --sort=-rss | head -13 | awk '{printf "%-9s %5d MB  %s\n", $1, $6/1024, $11}'

echo "=== Nacos Derby data 大小 ==="
du -sh /opt/nacos/data 2>/dev/null

echo "=== 应用 systemd unit（backend 示例） ==="
cat /etc/systemd/system/aismp-backend.service 2>/dev/null | grep -E "ExecStart|EnvironmentFile|User="