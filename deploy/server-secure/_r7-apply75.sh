#!/bin/bash
# R7: 远端应用 75 号迁移（账管/仓管账号 + 工单流程列 + status 扩宽）
set -uo pipefail
. /opt/agent-platform-secure/credentials.env

mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PW" < /tmp/_deploy_75-role-users-and-workflow.sql 2>/dev/null | tail -14
echo "R7-DONE"