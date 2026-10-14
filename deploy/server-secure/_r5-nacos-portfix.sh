#!/bin/bash
# R5: 修 Nacos 3.x 端口键名并重启
#   根因：3.x 主端口键是 nacos.server.main.port；沿用 2.x 的 server.port 会让
#   merged 部署里的**控制台上下文**也继承 8848 → 与主服务抢端口 → 控制台起不来
#   （现象：日志 "Port 8848 already in use" + "Nacos failed to start"，
#    但主服务其实活着、注册也能用 —— 只有控制台 8849 不监听）
set -uo pipefail
CONF=/opt/nacos-3.2.4/conf/application.properties

cp -a "$CONF" "$CONF.bak-20261006"

echo "=== 修改前 ==="
grep -E '^(nacos\.(console|server)\.|server\.port)' "$CONF"

# 删掉 2.x 风格的 server.port（3.x 由 nacos.server.main.port 管主端口）
sed -i '/^server\.port=/d' "$CONF"
grep -q '^nacos.server.main.port=' "$CONF" || sed -i '1i nacos.server.main.port=8848' "$CONF"
grep -q '^nacos.console.remote.server.context-path=' "$CONF" || echo 'nacos.console.remote.server.context-path=/nacos' >> "$CONF"

echo "=== 修改后 ==="
grep -E '^(nacos\.(console|server)\.|server\.port)' "$CONF"

echo "=== 重启 nacos ==="
systemctl restart nacos
ok=0
for i in $(seq 1 40); do
  sleep 3
  if ss -tln | grep -q ':8849 '; then ok=1; echo "  8849 已监听（$((i*3))s）"; break; fi
done
[ "$ok" -eq 1 ] || { echo "  !! 8849 仍未监听"; }
sleep 5
echo "=== 端口 ==="
ss -tln | grep -E ':(8848|8849|9848|9849) ' || echo "  !! 端口不全"
echo "=== 启动日志 ==="
grep -E "started successfully|failed to start" /opt/nacos-3.2.4/logs/nacos.log | tail -4
echo "=== 控制台连通性 ==="
curl -s -o /dev/null -w "  8849:%{http_code}\n" --noproxy '*' http://127.0.0.1:8849/ || true
curl -s -o /dev/null -w "  8848-nacos:%{http_code}\n" --noproxy '*' http://127.0.0.1:8848/nacos/ || true
echo "R5-DONE"