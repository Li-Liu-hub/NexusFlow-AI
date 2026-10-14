#!/bin/bash
# R1: 远端升级前备份（2026-10-06）
#   - 老 Nacos 2.4.3 目录改名保留（不做 tar，642M 的 Derby data 留着可直接回滚）
#   - 应用 jar 改名保留（.20261001.bak）
#   - systemd unit 备份到 /opt/backup/units-20261006/
set -uo pipefail

echo "=== 磁盘空间（升级前） ==="
df -h / | tail -1

mkdir -p /opt/backup/units-20261006

echo "=== 备份 systemd unit ==="
for u in nacos aismp-interface aismp-backend aismp-gateway aismp-agent agent-orchestrator; do
  if [ -f "/etc/systemd/system/${u}.service" ]; then
    cp -a "/etc/systemd/system/${u}.service" /opt/backup/units-20261006/
    echo "  已备份 ${u}.service"
  fi
done

echo "=== 备份应用 jar（原地改名，保留可回滚） ==="
for a in backend gateway agent interface; do
  if [ -f "/opt/app/${a}/app.jar" ] && [ ! -f "/opt/app/${a}/app.jar.20261001.bak" ]; then
    mv "/opt/app/${a}/app.jar" "/opt/app/${a}/app.jar.20261001.bak"
    echo "  ${a}: app.jar → app.jar.20261001.bak"
  else
    echo "  ${a}: 跳过（已备份或不存在）"
  fi
done

echo "=== 备份凭据文件 ==="
cp -a /opt/agent-platform-secure/credentials.env /opt/backup/credentials.env.20261006

echo "=== 当前目录状态 ==="
ls -lh /opt/app/*/ 2>/dev/null | grep -E "app.jar|app.jar.bak" 
echo "=== 老 Nacos 目录（升级时改名为 -backup） ==="
du -sh /opt/nacos 2>/dev/null

echo "R1-BACKUP-DONE"