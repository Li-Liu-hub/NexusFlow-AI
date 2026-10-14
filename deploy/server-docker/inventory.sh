#!/bin/bash
# 服务器数据盘点（在服务器上执行；需要 sudo 读 credentials.env）
#
# 用法（二选一，凭据不入库、不落盘）：
#   1) 交互式输入：  bash inventory.sh
#   2) 环境变量传入：SUDO_PW='xxx' bash inventory.sh
#   3) 已有免密 sudo：SUDO_PW='' bash inventory.sh
#
# 安全说明：本脚本原先硬编码了服务器 sudo 密码并已推送到远端，属凭据泄露。
#          现已改为运行时读取，请勿再写回明文。
set -euo pipefail

if [ -z "${SUDO_PW+x}" ]; then
  read -r -s -p "[sudo] password for $(whoami): " SUDO_PW
  echo ""
fi

if [ -n "${SUDO_PW}" ]; then
  echo "$SUDO_PW" | sudo -S -p '' bash -c '
set -a; . /opt/agent-platform-secure/credentials.env; set +a

echo "=== MySQL 库清单 ==="
mysql -u"$MYSQL_APP_USER" -p"$MYSQL_APP_PW" -N -e "SELECT CONCAT(table_schema,\" | \",COUNT(*),\" tables | \",ROUND(SUM(data_length+index_length)/1024/1024,1),\" MB\") FROM information_schema.tables GROUP BY table_schema;" 2>/dev/null

echo "=== api_db 表 ==="
mysql -u"$MYSQL_APP_USER" -p"$MYSQL_APP_PW" -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema=\"api_db\" ORDER BY table_name;" 2>/dev/null | tr "\n" " "
echo ""

echo "=== 关键数据量 ==="
mysql -u"$MYSQL_APP_USER" -p"$MYSQL_APP_PW" -N -e "SELECT CONCAT(\"terminal=\",COUNT(*)) FROM api_db.terminal; SELECT CONCAT(\"warehouse=\",COUNT(*)) FROM api_db.warehouse; SELECT CONCAT(\"interface_info=\",COUNT(*)) FROM api_db.interface_info; SELECT CONCAT(\"transfer_order=\",COUNT(*)) FROM api_db.transfer_order;" 2>/dev/null

echo "=== PG ==="
sudo -u postgres psql -d terminal_kb -t -c "SELECT \"vector_store=\"||COUNT(*) FROM vector_store;" 2>/dev/null
sudo -u postgres psql -t -c "SELECT pg_size_pretty(pg_database_size(\"terminal_kb\"));" 2>/dev/null

echo "=== Redis ==="
redis-cli -a "$REDIS_PW" --no-auth-warning DBSIZE 2>/dev/null

echo "=== Nacos 数据目录 ==="
du -sh /opt/nacos/data 2>/dev/null
'
else
  sudo bash -c '
set -a; . /opt/agent-platform-secure/credentials.env; set +a

echo "=== MySQL 库清单 ==="
mysql -u"$MYSQL_APP_USER" -p"$MYSQL_APP_PW" -N -e "SELECT CONCAT(table_schema,\" | \",COUNT(*),\" tables | \",ROUND(SUM(data_length+index_length)/1024/1024,1),\" MB\") FROM information_schema.tables GROUP BY table_schema;" 2>/dev/null

echo "=== api_db 表 ==="
mysql -u"$MYSQL_APP_USER" -p"$MYSQL_APP_PW" -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema=\"api_db\" ORDER BY table_name;" 2>/dev/null | tr "\n" " "
echo ""

echo "=== 关键数据量 ==="
mysql -u"$MYSQL_APP_USER" -p"$MYSQL_APP_PW" -N -e "SELECT CONCAT(\"terminal=\",COUNT(*)) FROM api_db.terminal; SELECT CONCAT(\"warehouse=\",COUNT(*)) FROM api_db.warehouse; SELECT CONCAT(\"interface_info=\",COUNT(*)) FROM api_db.interface_info; SELECT CONCAT(\"transfer_order=\",COUNT(*)) FROM api_db.transfer_order;" 2>/dev/null

echo "=== PG ==="
sudo -u postgres psql -d terminal_kb -t -c "SELECT \"vector_store=\"||COUNT(*) FROM vector_store;" 2>/dev/null
sudo -u postgres psql -t -c "SELECT pg_size_pretty(pg_database_size(\"terminal_kb\"));" 2>/dev/null

echo "=== Redis ==="
redis-cli -a "$REDIS_PW" --no-auth-warning DBSIZE 2>/dev/null

echo "=== Nacos 数据目录 ==="
du -sh /opt/nacos/data 2>/dev/null
'
fi
