#!/bin/bash
# 11-align-local-init.sh — align the server with agent-platform/local-init/*
set -uo pipefail
echo "########## [ALIGN TO local-init] ##########"

SRC=/tmp/localinit
DST=/opt/agent-platform-init

# --- 0) keep a permanent copy of the init kit on the server ---
mkdir -p "$DST"
cp -f "$SRC"/mysql-init.sql "$SRC"/mysql-seed.sql "$SRC"/pg-init.sql "$SRC"/build-backend.sh "$DST"/ 2>/dev/null || true
chmod 0644 "$DST"/*.sql 2>/dev/null || true
chmod 0755 "$DST"/build-backend.sh 2>/dev/null || true
echo "--- init kit stored at ${DST} ---"
ls -l "$DST"

# --- 1) MySQL root password 1234 -> 123456 (项目约定值) ---
echo
echo "--- [1] MySQL root password ---"
if mysql -uroot -p1234 -e "SELECT 1" >/dev/null 2>&1; then
  mysql -uroot -p1234 <<'EOSQL' 2>&1 | grep -v "Using a password" || true
ALTER USER 'root'@'localhost' IDENTIFIED WITH caching_sha2_password BY '123456';
ALTER USER 'root'@'%'         IDENTIFIED WITH caching_sha2_password BY '123456';
FLUSH PRIVILEGES;
EOSQL
  echo "changed 1234 -> 123456"
elif mysql -uroot -p123456 -e "SELECT 1" >/dev/null 2>&1; then
  echo "already 123456"
else
  echo "WARN: could not log in as root with 1234 or 123456"
fi

M="mysql -uroot -p123456"
$M -e "SELECT 'mysql login ok' AS s;" 2>&1 | grep -v "Using a password"

# --- 2) rebuild api_db exactly as local-init defines it ---
echo
echo "--- [2] rebuild api_db / drop legacy my_db,aismp ---"
$M -e "DROP DATABASE IF EXISTS api_db; DROP DATABASE IF EXISTS my_db; DROP DATABASE IF EXISTS aismp;" 2>&1 | grep -v "Using a password" || true
$M < "${SRC}/mysql-init.sql" 2>&1 | grep -v "Using a password" || true
$M < "${SRC}/mysql-seed.sql" 2>&1 | grep -v "Using a password" || true

echo "--- databases now ---"
$M -N -e "SHOW DATABASES;" 2>/dev/null

echo "--- api_db tables ---"
$M -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='api_db' ORDER BY table_name;" 2>/dev/null

echo "--- api_db row counts ---"
for t in user post interface_info user_interface_info; do
  printf '  %-22s ' "$t"
  $M -N -e "SELECT COUNT(*) FROM api_db.\`${t}\`;" 2>/dev/null || echo "(n/a)"
done

echo "--- user table columns (accessKey/secretKey 应存在) ---"
$M -N -e "SELECT column_name FROM information_schema.columns WHERE table_schema='api_db' AND table_name='user' ORDER BY ordinal_position;" 2>/dev/null | paste -sd, -

echo "--- seeded accounts ---"
$M -e "SELECT id, userAccount, userRole, accessKey, secretKey FROM api_db.user;" 2>/dev/null

echo "--- interfaces ---"
$M -e "SELECT id, name, url, method, status FROM api_db.interface_info ORDER BY id;" 2>/dev/null

# --- 3) PostgreSQL: apply pg-init.sql (业务表 + chat_memory) ---
echo
echo "--- [3] PostgreSQL terminal_kb schema ---"
sudo -u postgres psql -d terminal_kb -v ON_ERROR_STOP=1 -f "${SRC}/pg-init.sql" 2>&1 | tail -25

echo "--- PG tables ---"
sudo -u postgres psql -d terminal_kb -tAc \
  "SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name;"

echo "--- PG row counts ---"
for t in tm_area base_user tm_role tm_user_role tm_role_job tm_work_inst tm_order_track chat_memory; do
  printf '  %-18s ' "$t"
  sudo -u postgres psql -d terminal_kb -tAc "SELECT COUNT(*) FROM ${t};" 2>/dev/null || echo "(n/a)"
done

echo "--- app-user TCP check ---"
PGPASSWORD=123456 psql -h 127.0.0.1 -U my_user -d terminal_kb -tAc \
  "SELECT 'ok: '||current_user||' @ '||current_database();" 2>&1 | head -2

echo "ALIGN-DONE"
