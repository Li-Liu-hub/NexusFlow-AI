#!/bin/bash
# MySQL 首次初始化：建库 + 应用账号（密码来自 compose 注入的环境变量）
# 业务表结构与数据由迁移脚本（migrate/import-to-docker.sh）导入
set -e

mysql -u root -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS api_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS terminal_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 应用最小权限账号（只授这两个库；root 不做远程）
CREATE USER IF NOT EXISTS '${MYSQL_APP_USER}'@'%' IDENTIFIED BY '${MYSQL_APP_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER
  ON api_db.* TO '${MYSQL_APP_USER}'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER
  ON terminal_db.* TO '${MYSQL_APP_USER}'@'%';
FLUSH PRIVILEGES;
SQL

echo "mysql init done: api_db + terminal_db + app user"
