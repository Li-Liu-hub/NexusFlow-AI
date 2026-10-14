#!/bin/bash
# 30-init-data.sh — 初始化 api_db（平台自身的元数据层）
#
# 只建平台骨架：user / post / interface_info / user_interface_info + 账号 + 网关接口登记。
# 终端业务的 5 张业务表（terminal/warehouse/transfer_order/...）由 M1 阶段的 40-terminal-schema.sh 负责。
# **不加载 pg-init.sql** —— 那 8 张表属于已决定删除的 OA 客服场景。
#
# 前置：90-harden.sh 已跑（root 密码已是强密码，root@'%' 已删）
# 用法：python ssh_run_script.py 30-init-data.sh --root
set -uo pipefail

CRED="${1:-/opt/agent-platform-secure/credentials.env}"
[ -f "${CRED}" ] || { echo "缺少凭据文件：${CRED}"; exit 1; }
# shellcheck disable=SC1090
set -a; . "${CRED}"; set +a

SRC=/tmp/localinit
M="mysql -uroot -p${MYSQL_ROOT_PW}"

echo "########## 初始化 api_db（平台元数据层）$(date '+%F %T') ##########"

# 连接自检
if ! ${M} -e "SELECT 1" >/dev/null 2>&1; then
  echo "!! 无法以 root 登录 MySQL，请确认 credentials.env 的 MYSQL_ROOT_PW 与服务器一致"
  exit 1
fi

echo
echo "--- [1/4] schema（user / post / interface_info / user_interface_info）---"
${M} < "${SRC}/mysql-init.sql" 2>&1 | grep -v "Using a password" || true

echo
echo "--- [2/4] seed（默认账号 + 20 条 Mock 接口）---"
${M} < "${SRC}/mysql-seed.sql" 2>&1 | grep -v "Using a password" || true

echo
echo "--- [3/4] 网关接口登记（网关按 (url,method) 精确匹配，漏登记 = 403）---"
if [ -f "${SRC}/mysql-interface-registry.sql" ]; then
  ${M} < "${SRC}/mysql-interface-registry.sql" 2>&1 | grep -v "Using a password" || true
else
  echo "  （跳过：未找到 mysql-interface-registry.sql）"
fi

echo
echo "--- [4/4] 校验 ---"
echo "  数据库："
${M} -N -e "SHOW DATABASES;" 2>/dev/null | sed 's/^/    /'
echo "  api_db 表："
${M} -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='api_db' ORDER BY table_name;" 2>/dev/null | sed 's/^/    /'
echo "  行数："
for t in user post interface_info user_interface_info; do
  printf '    %-22s ' "$t"
  ${M} -N -e "SELECT COUNT(*) FROM api_db.\`${t}\`;" 2>/dev/null || echo "(n/a)"
done
echo "  账号："
${M} -e "SELECT id, userAccount, userRole, accessKey FROM api_db.user;" 2>/dev/null | sed 's/^/    /'
echo "  接口登记条数："
${M} -N -e "SELECT COUNT(*) FROM api_db.interface_info;" 2>/dev/null | sed 's/^/    /'

echo
echo "  应用账号 ${MYSQL_APP_USER} 连通性（走 TCP，模拟应用侧）："
mysql -h 127.0.0.1 -u"${MYSQL_APP_USER}" -p"${MYSQL_APP_PW}" -N -e "SELECT CONCAT('    ok: ', CURRENT_USER(), ' @ api_db');" api_db 2>/dev/null \
  || echo "    !! 应用账号连接失败"

echo
echo "########## 完成 $(date '+%F %T') ##########"
echo "INIT-DATA-DONE"
