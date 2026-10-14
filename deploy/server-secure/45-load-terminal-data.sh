#!/bin/bash
# 45-load-terminal-data.sh — 导入终端业务数据（M1）
#
# 前置：40-terminal-schema.sql 已执行；local_data/terminal-data.sql.gz 已上传到 /tmp
# 用法：python ssh_run_script.py 45-load-terminal-data.sh --root
set -uo pipefail

CRED="${1:-/opt/agent-platform-secure/credentials.env}"
[ -f "${CRED}" ] || { echo "缺少凭据文件：${CRED}"; exit 1; }
set -a; . "${CRED}"; set +a

M="mysql -uroot -p${MYSQL_ROOT_PW}"

echo "########## 导入终端业务数据 $(date '+%F %T') ##########"

echo
echo "--- [1/4] 建表（terminal-schema.sql）---"
# 注意：schema 用 ssh_upload.py 上传为 /tmp/terminal-schema.sql
# 不要用 ssh_run_script.py 传 SQL —— 那个通道会拿 bash 去执行 .sql 文件，必然报语法错
SCHEMA=/tmp/terminal-schema.sql
if [ -f "${SCHEMA}" ]; then
  ${M} < "${SCHEMA}" 2>&1 | grep -v "Using a password" | tail -5
  echo "  表已重建"
else
  echo "  !! 未找到 ${SCHEMA}"
  exit 1
fi

echo
echo "--- [2/4] 导入数据（terminal-data.sql.gz）---"
if [ -f /tmp/terminal-data.sql.gz ]; then
  gunzip -c /tmp/terminal-data.sql.gz | ${M} api_db 2>&1 | grep -v "Using a password" | tail -5
else
  echo "  !! 未找到 /tmp/terminal-data.sql.gz"
  exit 1
fi

echo
echo "--- [3/4] 校验 ---"
printf '  terminal  : '; ${M} -N -e "SELECT COUNT(*) FROM api_db.terminal;" 2>/dev/null
printf '  warehouse : '; ${M} -N -e "SELECT COUNT(*) FROM api_db.warehouse;" 2>/dev/null
echo "  --- 仓位 ---"
${M} -e "SELECT warehouseCode, name, level, parentCode FROM api_db.warehouse;" 2>/dev/null | sed 's/^/    /'
echo "  --- 终端大类分布 ---"
${M} -e "SELECT bigType, COUNT(*) AS cnt FROM api_db.terminal GROUP BY bigType ORDER BY cnt DESC;" 2>/dev/null | sed 's/^/    /'
echo "  --- 型号 TOP8 ---"
${M} -e "SELECT model, COUNT(*) AS cnt FROM api_db.terminal GROUP BY model ORDER BY cnt DESC LIMIT 8;" 2>/dev/null | sed 's/^/    /'
echo "  --- 库龄分布 ---"
${M} -e "SELECT CASE WHEN agingDays IS NULL THEN 'NULL' WHEN agingDays<30 THEN '<30' WHEN agingDays<90 THEN '30-90' WHEN agingDays<180 THEN '90-180' ELSE '>=180' END AS band, COUNT(*) c FROM api_db.terminal GROUP BY band ORDER BY c DESC;" 2>/dev/null | sed 's/^/    /'
echo "  --- 日期清洗抽检（应为真实日期，不是 46197 这种序列号）---"
${M} -e "SELECT serialNo, statusChangedAt, firstInboundAt, agingDays FROM api_db.terminal LIMIT 3;" 2>/dev/null | sed 's/^/    /'

echo
echo "--- [4/4] 应用账号可见性 ---"
mysql -h 127.0.0.1 -u"${MYSQL_APP_USER}" -p"${MYSQL_APP_PW}" -N -e \
  "SELECT CONCAT('    api_user 可见 terminal 行数 = ', COUNT(*)) FROM api_db.terminal;" 2>/dev/null

echo
echo "########## 完成 $(date '+%F %T') ##########"
echo "LOAD-TERMINAL-DONE"
