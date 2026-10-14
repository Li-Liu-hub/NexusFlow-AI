#!/bin/bash
# 90-harden.sh — 把默认弱密码全换成强密码，并补上纵深防御
#
# 背景：2026-09-30 这台机器被打穿 —— MySQL root/123456 + 公网开放被删库勒索，
# Redis 无密码被写入 crontab 后门（指向 kiss.a-dog.top）。本脚本是重装后的加固层。
#
# 用法（在服务器上，先跑完 deploy/server/01..06 装好中间件再跑本脚本）：
#   sudo bash 90-harden.sh /opt/agent-platform-secure/credentials.env
#
# 设计原则：
#   - 数据库端口是否对外由 ALLOW_DB_PUBLIC 决定。默认 no：ufw 只放行 22 与应用端口，
#     数据库走 localhost。这不需要动腾讯云控制台，是本机层面的收紧。
#   - 所有密码从 credentials.env 读，脚本本身不含任何硬编码凭据。
set -uo pipefail

CRED="${1:-/opt/agent-platform-secure/credentials.env}"
[ -f "${CRED}" ] || { echo "缺少凭据文件：${CRED}"; exit 1; }

# shellcheck disable=SC1090
set -a; . "${CRED}"; set +a

# ---- 旧密码（deploy/server/01..06 装的默认值），可通过环境变量覆盖 ----
# 注意：03-mysql.sh 里写的是 ROOTPW=1234（不是 123456），这里必须对齐，
# 否则 MySQL 段会静默连不上、加固不生效（2026-10-01 踩过）。
OLD_MYSQL_ROOT_PW="${OLD_MYSQL_ROOT_PW:-1234}"
PSQL="sudo -u postgres psql"

# 是否让数据库端口对公网开放。默认 no（只走 localhost）。
ALLOW_DB_PUBLIC="${ALLOW_DB_PUBLIC:-no}"

# 对外暴露的端口：只留 8000（nginx 静态托管 + 反向代理）
# 其余服务全部走 localhost，由 nginx 反代转发——攻击面从「8 个服务」收缩到「1 个 nginx」。
# 需要临时调试时用 SSH 隧道，不要在这里加端口。
APP_PORTS=(8000)
DB_PORTS=(3306 5432 6379 5672 15672 8848 9848 9849)

echo "############ 加固开始 $(date '+%F %T') ############"
echo "数据库端口对外公开：${ALLOW_DB_PUBLIC}（no = 只走 localhost）"

############################################
echo; echo "########## [1/7] MySQL ##########"
############################################
# 目标：root 收回本机、删掉 root@'%'、应用改用最小权限的 api_user
mysql -uroot -p"${OLD_MYSQL_ROOT_PW}" 2>/dev/null <<EOSQL
ALTER USER 'root'@'localhost' IDENTIFIED WITH caching_sha2_password BY '${MYSQL_ROOT_PW}';
DROP USER IF EXISTS 'root'@'%';
CREATE USER IF NOT EXISTS '${MYSQL_APP_USER}'@'%' IDENTIFIED WITH caching_sha2_password BY '${MYSQL_APP_PW}';
ALTER USER '${MYSQL_APP_USER}'@'%' IDENTIFIED WITH caching_sha2_password BY '${MYSQL_APP_PW}';
GRANT ALL PRIVILEGES ON api_db.* TO '${MYSQL_APP_USER}'@'%';
CREATE DATABASE IF NOT EXISTS api_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
FLUSH PRIVILEGES;
EOSQL
if [ $? -eq 0 ]; then
  echo "  root@localhost 已换密、root@'%' 已删除、${MYSQL_APP_USER} 已建（仅 api_db 权限）"
else
  echo "  !! MySQL 加固失败 —— 确认旧密码是否为 ${OLD_MYSQL_ROOT_PW}"
fi
echo "  --- 账号现状 ---"
mysql -uroot -p"${MYSQL_ROOT_PW}" -N -e "SELECT CONCAT(user,'@',host) FROM mysql.user ORDER BY user;" 2>/dev/null | sed 's/^/    /'
echo "  --- 应用账号连通性（localhost）---"
mysql -h 127.0.0.1 -u"${MYSQL_APP_USER}" -p"${MYSQL_APP_PW}" -N -e "SELECT 'api_user ok';" 2>/dev/null | sed 's/^/    /' || echo "    !! api_user 连接失败"

############################################
echo; echo "########## [2/7] PostgreSQL ##########"
############################################
# 目标：换强密码；pg_hba 从「任意库」收紧到「只允许业务库」
PGCONF=/etc/postgresql/18/main/postgresql.conf
PGHBA=/etc/postgresql/18/main/pg_hba.conf
[ -f "${PGHBA}.orig" ] || cp "${PGHBA}" "${PGHBA}.orig"

$PSQL -qc "ALTER ROLE ${PG_USER} WITH LOGIN PASSWORD '${PG_PW}';" && echo "  ${PG_USER} 密码已更新"

# 删掉部署脚本加的那两行通配规则，换成「只允许业务库」
sed -i '/# ==== remote access (added by deploy) ====/d' "${PGHBA}"
sed -i '/^host[[:space:]]\+all[[:space:]]\+all[[:space:]]\+0\.0\.0\.0\/0/d' "${PGHBA}"
sed -i '/^host[[:space:]]\+all[[:space:]]\+all[[:space:]]\+::\/0/d' "${PGHBA}"
grep -q "terminal_kb.*0.0.0.0/0" "${PGHBA}" || cat >> "${PGHBA}" <<'EOF'

# ==== 加固后：只允许从外部连业务库，不允许连 postgres/template 库 ====
host    terminal_kb    all    0.0.0.0/0    scram-sha-256
host    terminal_kb    all    ::/0         scram-sha-256
EOF
systemctl reload postgresql
echo "  --- pg_hba 末尾规则 ---"
tail -4 "${PGHBA}" | sed 's/^/    /'
echo "  --- 新密码连通性 ---"
PGPASSWORD="${PG_PW}" psql -h 127.0.0.1 -U "${PG_USER}" -d terminal_kb -tAc "SELECT 'pg ok: '||current_user;" 2>/dev/null | sed 's/^/    /' || echo "    !! PG 连接失败"

############################################
echo; echo "########## [3/7] Redis ##########"
############################################
# 目标：加密码 + 禁用写文件/清库类危险命令
# rename-command CONFIG "" 是防「通过 Redis 写 crontab / 写 SSH key」的关键一步：
# 攻击者正是靠 CONFIG SET dir/dbfilename + SAVE 把文件写进 /var/spool/cron 的。
RCONF=/etc/redis/redis.conf
[ -f "${RCONF}.orig" ] || cp "${RCONF}" "${RCONF}.orig"

sed -i -E "s|^[[:space:]]*#?[[:space:]]*requirepass[[:space:]]+.*|requirepass ${REDIS_PW}|" "${RCONF}"
sed -i -E "s|^[[:space:]]*#?[[:space:]]*protected-mode[[:space:]]+.*|protected-mode yes|" "${RCONF}"

# 幂等：先删旧的 rename-command 段再追加
sed -i '/# ==== harden: disable dangerous commands ====/,$d' "${RCONF}"
cat >> "${RCONF}" <<'EOF'
# ==== harden: disable dangerous commands ====
rename-command CONFIG ""
rename-command FLUSHALL ""
rename-command FLUSHDB ""
rename-command SHUTDOWN ""
rename-command SLAVEOF ""
rename-command REPLICAOF ""
rename-command DEBUG ""
EOF

systemctl restart redis-server
sleep 3
echo -n "  PING（带密码）-> "; redis-cli -a "${REDIS_PW}" --no-auth-warning ping 2>/dev/null
echo -n "  PING（不带密码，应当被拒）-> "
redis-cli ping 2>&1 | head -1 | sed 's/^/    /'
echo -n "  CONFIG 命令（应当被封）-> "
redis-cli -a "${REDIS_PW}" --no-auth-warning config get dir 2>&1 | head -1 | sed 's/^/    /'
echo "  --- 残留 key ---"
redis-cli -a "${REDIS_PW}" --no-auth-warning keys '*' 2>/dev/null | sed 's/^/    /'

############################################
echo; echo "########## [4/7] RabbitMQ ##########"
############################################
# 目标：建专用用户、删掉 guest（guest 只能本机，且默认口令人人皆知）
rabbitmqctl await_startup 2>/dev/null || true
rabbitmqctl add_user "${RABBIT_USER}" "${RABBIT_PW}" 2>/dev/null || rabbitmqctl change_password "${RABBIT_USER}" "${RABBIT_PW}"
rabbitmqctl set_user_tags "${RABBIT_USER}" administrator
rabbitmqctl set_permissions -p / "${RABBIT_USER}" ".*" ".*" ".*"
rabbitmqctl delete_user guest 2>/dev/null && echo "  guest 用户已删除"
echo "  --- 用户列表 ---"
rabbitmqctl list_users 2>&1 | sed 's/^/    /'
echo "  --- 管理台连通性（新用户）---"
curl -s -m 10 -u "${RABBIT_USER}:${RABBIT_PW}" http://127.0.0.1:15672/api/overview \
  | head -c 120 | sed 's/^/    /'; echo

############################################
echo; echo "########## [5/7] Nacos ##########"
############################################
# 目标：开启鉴权（无鉴权 = 任何人都能改注册表、拉配置）
APPPROP=/opt/nacos/conf/application.properties
[ -f "${APPPROP}.orig" ] || cp "${APPPROP}" "${APPPROP}.orig"

set_prop() {
  key="$1"; val="$2"; file="$3"
  if grep -qE "^[[:space:]]*#?[[:space:]]*${key}=" "${file}"; then
    sed -i -E "s|^[[:space:]]*#?[[:space:]]*${key}=.*|${key}=${val}|" "${file}"
  else
    printf "\n%s=%s\n" "${key}" "${val}" >> "${file}"
  fi
}
set_prop "nacos.core.auth.enabled" "true" "${APPPROP}"
set_prop "nacos.core.auth.system.type" "nacos" "${APPPROP}"
set_prop "nacos.core.auth.plugin.nacos.token.secret.key" "${NACOS_TOKEN_SECRET}" "${APPPROP}"
set_prop "nacos.core.auth.server.identity.key" "${NACOS_IDENTITY_KEY}" "${APPPROP}"
set_prop "nacos.core.auth.server.identity.value" "${NACOS_IDENTITY_VALUE}" "${APPPROP}"

echo "  --- 关键配置 ---"
grep -nE "^[[:space:]]*(nacos.core.auth.enabled|nacos.core.auth.system.type|nacos.core.auth.server.identity.key)=" "${APPPROP}" | sed 's/^/    /'

systemctl restart nacos
echo "  --- 等待 Nacos ---"
for i in $(seq 1 24); do
  sleep 5
  code=$(curl -s -o /dev/null -m 5 -w "%{http_code}" "http://127.0.0.1:8848/nacos/v1/console/health/readiness" || true)
  echo "    t=$((i*5))s http=$code"
  [ "$code" = "200" ] && break
done
echo -n "  未认证访问（应当 403）-> "
curl -s -o /dev/null -m 10 -w "%{http_code}\n" "http://127.0.0.1:8848/nacos/v1/console/namespaces" | sed 's/^/    /'

############################################
echo; echo "########## [6/7] fail2ban（防 SSH 爆破）##########"
############################################
# 实测这台机器已被尝试爆破 5594 次
if ! command -v fail2ban-client >/dev/null 2>&1; then
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq fail2ban >/dev/null 2>&1 && echo "  fail2ban 已安装"
fi
cat > /etc/fail2ban/jail.local <<'EOF'
[DEFAULT]
bantime  = 3600
findtime = 600
maxretry = 5
backend  = systemd

[sshd]
enabled = true
port    = ssh
EOF
systemctl enable fail2ban >/dev/null 2>&1
systemctl restart fail2ban
echo "  --- 状态 ---"
fail2ban-client status sshd 2>&1 | head -12 | sed 's/^/    /'

############################################
echo; echo "########## [7/7] ufw 本机防火墙 ##########"
############################################
# 云防火墙管的是「包能不能到这台机器」，ufw 管的是「到了之后要不要收」——两层不冲突。
# 用户要求「不改控制台」，所以收口全部在本机做。
if ! command -v ufw >/dev/null 2>&1; then
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ufw >/dev/null 2>&1
fi

ufw --force reset >/dev/null 2>&1
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp comment 'SSH' >/dev/null
for p in "${APP_PORTS[@]}"; do ufw allow "${p}/tcp" comment 'app' >/dev/null; done

if [ "${ALLOW_DB_PUBLIC}" = "yes" ]; then
  for p in "${DB_PORTS[@]}"; do ufw allow "${p}/tcp" comment 'db' >/dev/null; done
  echo "  !! 数据库端口已放行到公网（按你的要求），唯一防线是强密码"
else
  echo "  数据库端口未放行，只能从本机 localhost 访问"
fi

ufw --force enable >/dev/null
echo "  --- ufw 规则 ---"
ufw status numbered 2>/dev/null | sed 's/^/    /'

echo
echo "############ 加固完成 $(date '+%F %T') ############"
echo "凭据文件：${CRED}（权限 600，禁止提交 git）"
echo
echo "下一步：把凭据同步到应用配置（各 application.yml 的 host/password/username），"
echo "然后跑 30-init-data.sh 初始化 api_db 与终端表。"
