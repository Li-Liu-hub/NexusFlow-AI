#!/bin/bash
# 05-rabbitmq.sh — RabbitMQ 3.12, remote access, guest/guest allowed off-loopback
set -uo pipefail
echo "########## [RABBITMQ] ##########"

[ -f /etc/rabbitmq/rabbitmq.conf ] || cp /etc/rabbitmq/rabbitmq.conf /etc/rabbitmq/rabbitmq.conf.orig 2>/dev/null || true

cat > /etc/rabbitmq/rabbitmq.conf <<'EOF'
# ==== added by deploy ====
listeners.tcp.default = 5672
management.tcp.port = 15672
# 项目配置使用 guest/guest，放行非本机登录
loopback_users.guest = false
vm_memory_high_watermark.relative = 0.4
EOF

echo "--- rabbitmq.conf ---"
cat /etc/rabbitmq/rabbitmq.conf

systemctl restart rabbitmq-server
sleep 12
echo -n "rabbitmq active: "; systemctl is-active rabbitmq-server

rabbitmq-plugins enable rabbitmq_management 2>&1 | tail -6
rabbitmqctl await_startup 2>/dev/null || true
sleep 5

echo "--- users ---"
rabbitmqctl list_users 2>&1 | tail -5

echo "--- listening ---"
ss -tlnp 2>/dev/null | grep -E "5672|15672" || echo "(ports not shown)"

echo "--- management API smoke (guest/guest) ---"
curl -s -m 10 -u guest:guest http://127.0.0.1:15672/api/overview | head -c 300; echo

echo "--- amqp tcp smoke ---"
(exec 3<>/dev/tcp/127.0.0.1/5672 && head -c 8 <&3 | od -c | head -2) 2>&1 || echo "(tcp probe failed)"

echo "RABBITMQ-DONE"
