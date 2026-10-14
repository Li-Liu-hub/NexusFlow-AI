#!/bin/bash
# 04-redis.sh — Redis 7, listen on all interfaces, no password (matches project config)
set -uo pipefail
echo "########## [REDIS] ##########"

CONF=/etc/redis/redis.conf
[ -f "${CONF}.orig" ] || cp "${CONF}" "${CONF}.orig"

sed -i -E "s|^[[:space:]]*#?[[:space:]]*bind[[:space:]]+.*|bind 0.0.0.0 -::1|" "${CONF}"
sed -i -E "s|^[[:space:]]*#?[[:space:]]*protected-mode[[:space:]]+.*|protected-mode no|" "${CONF}"
sed -i -E "s|^[[:space:]]*requirepass[[:space:]]+.*|# requirepass foobared|" "${CONF}"

echo "--- redis.conf effective lines ---"
grep -nE "^[[:space:]]*(bind|protected-mode|requirepass)" "${CONF}"

systemctl restart redis-server
sleep 3
echo -n "redis active: "; systemctl is-active redis-server
echo -n "PING -> "; redis-cli ping

echo "--- listening ---"
ss -tlnp 2>/dev/null | grep 6379 || true

echo "--- set/get smoke ---"
redis-cli set __deploy_probe "ok" EX 30 >/dev/null && redis-cli get __deploy_probe
redis-cli del __deploy_probe >/dev/null

echo "REDIS-DONE"
