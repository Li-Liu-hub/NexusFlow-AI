#!/bin/bash
# R9: 重跑 75 迁移（显示错误）
set -uo pipefail
. /opt/agent-platform-secure/credentials.env

echo "=== 文件检查 ==="
ls -l /tmp/_deploy_75-role-users-and-workflow.sql
file /tmp/_deploy_75-role-users-and-workflow.sql 2>/dev/null || true
head -3 /tmp/_deploy_75-role-users-and-workflow.sql | cat -A | head -3

echo "=== 执行（显示错误） ==="
mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PW" < /tmp/_deploy_75-role-users-and-workflow.sql
echo "exit=$?"
echo "R9-DONE"