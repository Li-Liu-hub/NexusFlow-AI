#!/bin/bash
# 03-mysql.sh — MySQL 8, remote root, databases api_db / aismp / my_db
set -uo pipefail
echo "########## [MYSQL] ##########"

MYCNF=/etc/mysql/mysql.conf.d/mysqld.cnf
ROOTPW=1234

[ -f "${MYCNF}.orig" ] || cp "${MYCNF}" "${MYCNF}.orig"

# --- 1) listen on all interfaces ---
if grep -qE "^[[:space:]]*#?[[:space:]]*bind-address" "${MYCNF}"; then
  sed -i -E "s|^[[:space:]]*#?[[:space:]]*bind-address[[:space:]]*=.*|bind-address = 0.0.0.0|" "${MYCNF}"
else
  printf "\nbind-address = 0.0.0.0\n" >> "${MYCNF}"
fi
sed -i -E "s|^[[:space:]]*#?[[:space:]]*mysqlx-bind-address[[:space:]]*=.*|mysqlx-bind-address = 0.0.0.0|" "${MYCNF}"

echo "--- mysqld.cnf ---"
grep -nE "bind-address" "${MYCNF}"

systemctl restart mysql
sleep 6
echo -n "mysql active: "; systemctl is-active mysql

# --- 2) set root password (Ubuntu's root@localhost defaults to auth_socket) ---
mysql -e "ALTER USER 'root'@'localhost' IDENTIFIED WITH caching_sha2_password BY '${ROOTPW}';" \
  && echo "root@localhost password set" \
  || echo "root@localhost already has a password"

# --- 3) allow remote root ---
mysql -uroot -p"${ROOTPW}" <<EOSQL
CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED WITH caching_sha2_password BY '${ROOTPW}';
ALTER USER 'root'@'%' IDENTIFIED WITH caching_sha2_password BY '${ROOTPW}';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;
FLUSH PRIVILEGES;
EOSQL

# --- 4) databases ---
mysql -uroot -p"${ROOTPW}" <<'EOSQL'
CREATE DATABASE IF NOT EXISTS api_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS aismp  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS my_db  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
EOSQL

echo "--- databases ---"
mysql -uroot -p"${ROOTPW}" -N -e "SHOW DATABASES;"
echo "--- accounts ---"
mysql -uroot -p"${ROOTPW}" -N -e "SELECT user,host,plugin FROM mysql.user;"

# --- 5) TCP smoke test ---
mysql -h 127.0.0.1 -P 3306 -uroot -p"${ROOTPW}" -e "SELECT VERSION() AS version, USER() AS who;" 2>&1 | grep -v "Using a password"

echo "MYSQL-DONE"
