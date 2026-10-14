#!/bin/bash
# ============================================================================
# load-all-images.sh —— 新机器侧：从离线包恢复全部镜像并交回 compose
#
# 用法：sudo bash load-all-images.sh [备份目录]
#   缺省目录 = /opt/agent-platform/backup/images-<最新一个>
#
# 换机完整恢复清单（镜像之外的三样，本脚本只负责①）：
#   ① 镜像           ← 本脚本（docker load）
#   ② 运行时数据      /opt/agent-platform/data/{mysql,pg,redis,rabbitmq,nacos,kb-uploads}
#                      + 数据库 SQL 备份 /opt/agent-platform/backup/*.sql.gz
#   ③ 配置与编排      /opt/agent-platform/{compose,nginx,init,web} + /opt/app/*/.env
#                      compose 变量文件 compose/.env（含 REGISTRY/TAG/PG_PW/密钥）
# ============================================================================
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "请用 root 运行"; exit 1; }

DIR="${1:-$(ls -d /opt/agent-platform/backup/images-* 2>/dev/null | sort | tail -1)}"
[ -n "$DIR" ] && [ -f "$DIR/all-images.tar.gz" ] || { echo "找不到备份包：$DIR/all-images.tar.gz"; exit 1; }

echo "=== 1. 完整性校验 ==="
(cd "$DIR" && md5sum -c all-images.tar.gz.md5)

echo "=== 2. docker load ==="
docker load < "$DIR/all-images.tar.gz"

echo "=== 3. 恢复后的镜像清单 ==="
docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | sort | head -30

echo "=== 4. 下一步（人工执行）==="
cat << 'EOF'
  a) 恢复数据卷：/opt/agent-platform/data/（从旧机同步或从 *.sql.gz 导入）
  b) 恢复配置：/opt/agent-platform/{compose,nginx,init,web} + /opt/app/*/.env
  c) 起服务：cd /opt/agent-platform/compose && docker compose up -d
  d) 验证：curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/portal.html
  ⚠️ compose 里 orchestrator 的 image 已固定 tag（20261010-canvas），
     其余 4 应用用 ${REGISTRY}/yp-*:${TAG}（compose/.env 的 TAG 需与备份一致）
EOF
