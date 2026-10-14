#!/bin/bash
# 95-bind-localhost.sh — 让所有中间件只监听 127.0.0.1（深度防御的第二层）
#
# 为什么需要：ufw 已经是第一层，但「服务监听 0.0.0.0 + 只靠 ufw 挡」是单点依赖——
# 一旦 ufw 被误关、或哪天为了调试临时放行一个端口，服务就直接裸奔回被入侵前的状态。
# 应用与中间件同机部署（本次架构），所以服务只需要 localhost。
#
# 配套：需要从本机连库调试时用 SSH 隧道，不要改这里的监听地址。
#   ssh -L 13306:127.0.0.1:3306 -L 15432:127.0.0.1:5432 ubuntu@<host>
#
# 用法：python ssh_run_script.py 95-bind-localhost.sh --root
set -uo pipefail

echo "########## 中间件监听收口到 127.0.0.1 ##########"

# ---------- MySQL ----------
echo
echo "--- [1/5] MySQL（含 X 协议的 33060）---"
MYCNF=/etc/mysql/mysql.conf.d/mysqld.cnf
[ -f "${MYCNF}.pre-bind" ] || cp "${MYCNF}" "${MYCNF}.pre-bind"
sed -i -E "s|^[[:space:]]*#?[[:space:]]*bind-address[[:space:]]*=.*|bind-address = 127.0.0.1|" "${MYCNF}"
sed -i -E "s|^[[:space:]]*#?[[:space:]]*mysqlx-bind-address[[:space:]]*=.*|mysqlx-bind-address = 127.0.0.1|" "${MYCNF}"
grep -E "^[[:space:]]*(bind-address|mysqlx-bind-address)" "${MYCNF}" | sed 's/^/    /'
systemctl restart mysql
sleep 6
echo -n "    状态: "; systemctl is-active mysql

# ---------- PostgreSQL ----------
echo
echo "--- [2/5] PostgreSQL ---"
PGCONF=/etc/postgresql/18/main/postgresql.conf
[ -f "${PGCONF}.pre-bind" ] || cp "${PGCONF}" "${PGCONF}.pre-bind"
sed -i -E "s|^[[:space:]]*#?[[:space:]]*listen_addresses[[:space:]]*=.*|listen_addresses = 'localhost'|" "${PGCONF}"
grep -E "^[[:space:]]*listen_addresses" "${PGCONF}" | sed 's/^/    /'
systemctl restart postgresql
sleep 4
echo -n "    状态: "; systemctl is-active postgresql

# ---------- Redis ----------
echo
echo "--- [3/5] Redis ---"
RCONF=/etc/redis/redis.conf
[ -f "${RCONF}.pre-bind" ] || cp "${RCONF}" "${RCONF}.pre-bind"
sed -i -E "s|^[[:space:]]*bind[[:space:]].*|bind 127.0.0.1 -::1|" "${RCONF}"
grep -E "^[[:space:]]*bind" "${RCONF}" | sed 's/^/    /'
systemctl restart redis-server
sleep 3
echo -n "    状态: "; systemctl is-active redis-server

# ---------- RabbitMQ ----------
echo
echo "--- [4/5] RabbitMQ（AMQP + 管理台）---"
RC=/etc/rabbitmq/rabbitmq.conf
[ -f "${RC}.pre-bind" ] || cp "${RC}" "${RC}.pre-bind"
sed -i -E "s|^[[:space:]]*#?[[:space:]]*listeners\.tcp\.default[[:space:]]*=.*|listeners.tcp.default = 127.0.0.1:5672|" "${RC}"
# 管理台也收口（默认监听所有接口）
if grep -qE "^[[:space:]]*#?[[:space:]]*management\.tcp\.ip" "${RC}"; then
  sed -i -E "s|^[[:space:]]*#?[[:space:]]*management\.tcp\.ip[[:space:]]*=.*|management.tcp.ip = 127.0.0.1|" "${RC}"
else
  printf "management.tcp.ip = 127.0.0.1\n" >> "${RC}"
fi
grep -E "^[[:space:]]*(listeners\.tcp\.default|management\.tcp)" "${RC}" | sed 's/^/    /'
systemctl restart rabbitmq-server
sleep 12
echo -n "    状态: "; systemctl is-active rabbitmq-server

# ---------- 验证 ----------
echo
echo "--- [5/5] 验证：还有哪些监听在 0.0.0.0 ---"
echo "    中间件相关端口（应全部变成 127.0.0.1）："
for p in 3306 33060 5432 6379 5672 15672; do
  printf '      端口 %-6s : ' "$p"
  # 必须只取 Local Address 列（第 4 列）——ss 输出的 Peer 列本来就是 0.0.0.0:*，
  # 直接 grep 整行会把正常结果误判成"仍在监听所有接口"（第一版就踩了这个坑）
  addr=$(ss -tlnH "sport = :$p" 2>/dev/null | awk '{print $4}' | head -1)
  case "${addr}" in
    0.0.0.0:*|\[::\]:*|\*:*) echo "!! 仍在监听所有接口（${addr}）" ;;
    "")                      echo "（未监听）" ;;
    *)                       echo "OK 仅本机（${addr}）" ;;
  esac
done

echo
echo "    Nacos 8848/9848 无法在应用层限制 bind（2.4.x 不支持），由 ufw 兜底："
ss -tlnH 'sport = :8848' 2>/dev/null | sed 's/^/      /'

echo
echo "    --- 各服务本机连通性复验 ---"
set -a; . /opt/agent-platform-secure/credentials.env; set +a
printf '      mysql (api_user) : '; mysql -h 127.0.0.1 -u"${MYSQL_APP_USER}" -p"${MYSQL_APP_PW}" -N -e "select 'ok';" 2>/dev/null || echo FAIL
printf '      redis            : '; redis-cli -a "${REDIS_PW}" --no-auth-warning ping 2>/dev/null || echo FAIL
printf '      postgres         : '; PGPASSWORD="${PG_PW}" psql -h 127.0.0.1 -U "${PG_USER}" -d terminal_kb -tAc "select 'ok';" 2>/dev/null || echo FAIL
printf '      rabbitmq 管理台  : '; curl -s -o /dev/null -w '%{http_code}\n' -u "${RABBIT_USER}:${RABBIT_PW}" http://127.0.0.1:15672/api/overview || echo FAIL

echo
echo "########## 收口完成 ##########"
echo "BIND-LOCALHOST-DONE"
