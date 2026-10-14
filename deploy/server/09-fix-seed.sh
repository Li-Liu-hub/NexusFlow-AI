#!/bin/bash
# 09-fix-seed.sh — make the 20-row seed insert work, then load it
set -uo pipefail
echo "########## [FIX SEED DATA] ##########"
M="mysql -uroot -p1234"

# db.sql 的 INSERT 不含 requestParams（NOT NULL 无默认值），严格模式下整条回滚
echo "--- give requestParams a default ---"
${M} -e "ALTER TABLE api_db.interface_info MODIFY COLUMN requestParams TEXT NOT NULL DEFAULT ('');" 2>&1 | grep -v "Using a password" || true

echo "--- remove any previously loaded api.aismp.com rows (idempotent) ---"
${M} -e "DELETE FROM api_db.interface_info WHERE url LIKE 'http://api.aismp.com/%';" 2>&1 | grep -v "Using a password" || true

echo "--- re-run db.sql ---"
${M} < /tmp/projsql/db.sql 2>&1 | grep -v "Using a password" || true

echo
echo "--- row counts ---"
${M} -N -e "
SELECT 'api_db.user',                  COUNT(*) FROM api_db.user
UNION ALL SELECT 'api_db.post',        COUNT(*) FROM api_db.post
UNION ALL SELECT 'api_db.interface_info',        COUNT(*) FROM api_db.interface_info
UNION ALL SELECT 'api_db.user_interface_info',   COUNT(*) FROM api_db.user_interface_info
UNION ALL SELECT 'aismp.user_interface_info',    COUNT(*) FROM aismp.user_interface_info;" 2>/dev/null

echo "--- interfaces ---"
${M} -e "SELECT id,name,url,method,status FROM api_db.interface_info ORDER BY id;" 2>/dev/null

echo "SEED-DONE"
