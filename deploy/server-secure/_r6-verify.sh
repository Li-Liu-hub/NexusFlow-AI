#!/bin/bash
# R6: 远端验收 —— Nacos 3.x + 新应用链路（网关鉴权 → backend 内部接口 → agent 业务路由）
set -uo pipefail

echo "=== [1/4] 服务状态 ==="
for s in nacos aismp-backend aismp-gateway aismp-agent aismp-interface agent-orchestrator; do
  printf "  %-20s %s\n" "$s" "$(systemctl is-active $s 2>/dev/null)"
done

echo "=== [2/4] 注册日志（三服务应各自 register finished） ==="
for s in backend gateway agent; do
  printf "  %-10s: %s\n" "$s" "$(grep -a 'nacos registry' /var/log/aismp/aismp-$s.log 2>/dev/null | tail -1 | cut -c1-120)"
done

echo "=== [3/4] 分通道连通性 ==="
printf "  gateway 8090 无凭证(应403)   : "
curl -s -o /dev/null -w "%{http_code}\n" -m 10 --noproxy '*' http://127.0.0.1:8090/api/terminal/stat/cards
printf "  backend 直连内部接口(应200)  : "
curl -s -o /dev/null -w "%{http_code}\n" -m 10 --noproxy '*' "http://127.0.0.1:7529/api/inner/user/byAccessKey?accessKey=aismp-console"
printf "  agent 直连 health/suggest    : "
curl -s -o /dev/null -w "%{http_code}\n" -m 10 --noproxy '*' "http://127.0.0.1:8124/terminal/health/suggest?warehouseCode=9BZA"

echo "=== [4/4] 签名经网关打业务路由（网关→backend鉴权→agent） ==="
SK="7c1f9a3e5d0b2846af31c7e9b0d45268"
TS=$(date +%s)
NONCE=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
SIGN=$(printf 'null.%s' "$SK" | sha256sum | cut -d' ' -f1)
printf "  /api/terminal/stat/cards  : "
curl -s -m 20 --noproxy '*' -H "accessKey: aismp-console" -H "nonce: $NONCE" -H "timestamp: $TS" -H "sign: $SIGN" \
  "http://127.0.0.1:8090/api/terminal/stat/cards" | head -c 160; echo
printf "  /api/terminal/warehouse/list : "
curl -s -m 20 --noproxy '*' -H "accessKey: aismp-console" -H "nonce: $NONCE" -H "timestamp: $TS" -H "sign: $SIGN" \
  "http://127.0.0.1:8090/api/terminal/warehouse/list" | head -c 200; echo

echo "=== 内存 ==="
free -m | head -3
echo "R6-VERIFY-DONE"