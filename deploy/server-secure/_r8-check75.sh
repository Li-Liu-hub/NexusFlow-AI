#!/bin/bash
# R8: 核对远端 75 迁移 + 新 jar 版本（只读）
set -uo pipefail
. /opt/agent-platform-secure/credentials.env

echo "=== 角色账号 ==="
mysql -uroot -p"$MYSQL_ROOT_PW" -N -e "SELECT user_code, role, warehouse_code, warehouse_name FROM terminal_db.a_user;" 2>/dev/null

echo "=== transfer_order 新列数（应 6） ==="
mysql -uroot -p"$MYSQL_ROOT_PW" -N -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name IN ('sentAt','accountConfirmedAt','keeperConfirmedAt','senderUser','accountConfirmer','keeperConfirmer');" 2>/dev/null

echo "=== status 列宽 ==="
mysql -uroot -p"$MYSQL_ROOT_PW" -N -e "SELECT column_type FROM information_schema.columns WHERE table_schema='api_db' AND table_name='transfer_order' AND column_name='status';" 2>/dev/null

echo "=== jar 时间/大小 ==="
ls -l --time-style=+%H:%M /opt/app/agent/app.jar /opt/app/gateway/app.jar

echo "R8-DONE"