#!/bin/bash
# 08-load-sql.sh — load the project's schema/seed SQL into MySQL
set -uo pipefail
echo "########## [LOAD PROJECT SQL] ##########"
D=/tmp/projsql
M="mysql -uroot -p1234"

# ddl.sql 建的是 my_db，但后端 datasource 指向 api_db -> 重映射到 api_db
sed -e 's/\bmy_db\b/api_db/g' "${D}/ddl.sql" > "${D}/ddl_api_db.sql"

echo "--- [1/4] ddl (my_db -> api_db) ---"
${M} < "${D}/ddl_api_db.sql" 2>&1 | grep -v "Using a password" || true

echo "--- [2/4] db.sql (interface_info + 20 seed rows) ---"
${M} < "${D}/db.sql" 2>&1 | grep -v "Using a password" || true

# 后端 datasource 是 api_db，user_interface_info 也要在 api_db 里
echo "--- [3/4] mirror user_interface_info into api_db ---"
${M} -e "CREATE TABLE IF NOT EXISTS api_db.user_interface_info LIKE aismp.user_interface_info;" 2>&1 | grep -v "Using a password" || true

echo "--- [4/4] merge_agent.sql against api_db ---"
${M} api_db < "${D}/merge_agent.sql" 2>&1 | grep -v "Using a password" || true

echo
echo "--- tables per schema ---"
${M} -N -e "SELECT table_schema, table_name FROM information_schema.tables WHERE table_schema IN ('api_db','aismp') ORDER BY 1,2;" 2>/dev/null

echo
echo "--- row counts ---"
for t in api_db.user api_db.post api_db.interface_info api_db.user_interface_info aismp.user_interface_info; do
  printf '%-34s ' "$t"
  ${M} -N -e "SELECT COUNT(*) FROM ${t};" 2>/dev/null || echo "(n/a)"
done

echo "--- sample of seeded interfaces ---"
${M} -e "SELECT id, name, url, method, status FROM api_db.interface_info ORDER BY id LIMIT 6;" 2>/dev/null

echo "SQL-DONE"
