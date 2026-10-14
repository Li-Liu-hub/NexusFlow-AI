#!/bin/bash
# 07-firewall.sh — inspect host firewall; open service ports if ufw is in use
set -uo pipefail
echo "########## [FIREWALL] ##########"

echo "--- ufw status ---"
ufw status verbose 2>&1 | head -25

echo "--- iptables INPUT ---"
iptables -S INPUT 2>&1 | head -30

echo "--- nft ruleset (head) ---"
nft list ruleset 2>&1 | head -50

PORTS=(22 3306 6379 5672 15672 8848 9848 9849 5432 7529 8090 8123 8124 8125 8000)

UFW_STATE=$(ufw status 2>/dev/null | head -1 || true)
echo "--- ufw first line: ${UFW_STATE} ---"

if echo "${UFW_STATE}" | grep -qi "Status: active"; then
  echo "--- opening ports via ufw ---"
  for p in "${PORTS[@]}"; do
    ufw allow "${p}/tcp" >/dev/null 2>&1 && echo "  allowed ${p}/tcp"
  done
  ufw reload >/dev/null 2>&1 || true
  ufw status verbose 2>&1 | head -25
else
  echo "ufw is INACTIVE — no host-level rules to add."
fi

echo "--- final listening sockets ---"
ss -tlnp 2>/dev/null | sort -t: -k2 -n

echo "FIREWALL-DONE"
