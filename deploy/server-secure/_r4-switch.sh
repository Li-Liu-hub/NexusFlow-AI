#!/bin/bash
# R4: 远端切换（停旧 → 起新 Nacos 3.2.4 → 起新应用）
set -uo pipefail

echo "=== [0/4] jar 就位检查 ==="
ls -lh /opt/app/backend/app.jar /opt/app/gateway/app.jar /opt/app/agent/app.jar 2>/dev/null || { echo "!! jar 缺失"; exit 1; }

echo "=== [1/4] 停应用（编排层无状态，留运行） ==="
for svc in aismp-agent aismp-gateway aismp-backend; do
  systemctl stop "$svc" && echo "  stopped $svc"
done

echo "=== [2/4] 停旧 Nacos 2.4.3 并让位目录 ==="
systemctl stop nacos && echo "  stopped nacos(2.4.3)"
systemctl disable nacos >/dev/null 2>&1 || true
if [ -d /opt/nacos ] && [ ! -e /opt/nacos-2.4.3-backup ]; then
  mv /opt/nacos /opt/nacos-2.4.3-backup && echo "  /opt/nacos → /opt/nacos-2.4.3-backup"
fi

echo "=== [3/4] 起新 Nacos 3.2.4 ==="
systemctl daemon-reload
systemctl enable nacos >/dev/null 2>&1
systemctl start nacos
ok=0
for i in $(seq 1 40); do
  sleep 3
  if ss -tln | grep -q ':8848 '; then ok=1; echo "  8848 监听（$((i*3))s）"; break; fi
done
if [ "$ok" -ne 1 ]; then
  echo "  !! 8848 未监听；日志尾部："
  tail -25 /opt/nacos-3.2.4/logs/start.out 2>/dev/null
  tail -25 /opt/nacos-3.2.4/logs/nacos.log 2>/dev/null
  exit 1
fi
sleep 8
echo "  端口: $(ss -tln | grep -cE ':(8848|8849|9848) ')/3"
grep -E "started successfully|failed to start" /opt/nacos-3.2.4/logs/nacos.log 2>/dev/null | tail -3

echo "=== [4/4] 起新应用（Spring Boot 4 / JDK21） ==="
systemctl restart aismp-backend; sleep 2; echo -n "  backend: "; systemctl is-active aismp-backend
systemctl restart aismp-gateway; sleep 2; echo -n "  gateway: "; systemctl is-active aismp-gateway
systemctl restart aismp-agent;   sleep 2; echo -n "  agent: ";   systemctl is-active aismp-agent

echo "  等待启动完成（50s）..."
sleep 50

echo "=== 注册与启动日志 ==="
for s in backend gateway agent; do
  echo "--- aismp-$s ---"
  grep -E "nacos registry|Started .*Application|APPLICATION FAILED|Error" "/var/log/aismp/aismp-$s.log" 2>/dev/null | tail -3
done

echo "R4-SWITCH-DONE"