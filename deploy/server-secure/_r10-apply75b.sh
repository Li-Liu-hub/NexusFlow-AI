#!/bin/bash
# R10: 远端应用 75b（transfer_order 工单流程列 + status 扩宽）
set -uo pipefail
. /opt/agent-platform-secure/credentials.env

mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PW" < /tmp/_deploy_75b-transfer-workflow-columns.sql
echo "exit=$?"
echo "R10-DONE"