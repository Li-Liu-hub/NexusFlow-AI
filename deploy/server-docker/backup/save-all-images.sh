#!/bin/bash
# ============================================================================
# save-all-images.sh —— 服务器侧：把 compose 全部镜像（应用 + 中间件）打成可迁移备份
#
# 背景（2026-10-11 实测）：本服务器到 Docker Hub 的 IPv4/IPv6 均不可达，
#   镜像加速器只支持拉取 —— 一旦换机/重装，所有镜像都拉不回来。
#   因此必须提前 save 成离线包：**包含公共中间件镜像**（换机同样拉不到）。
#
# 用法（服务器上）：sudo bash save-all-images.sh
# 产出：/opt/agent-platform/backup/images-<日期>/
#         all-images.tar.gz      一次 save 全部镜像（共享层只存一份，体积最优）
#         all-images.tar.gz.md5  完整性校验
#         image-list.txt         镜像清单（含运行时 tag，与 compose 一致）
# 恢复：见 load-all-images.sh
# ============================================================================
set -euo pipefail

STAMP=$(date +%Y%m%d)
OUT=/opt/agent-platform/backup/images-$STAMP
COMPOSE_DIR=/opt/agent-platform/compose

[ "$(id -u)" -eq 0 ] || { echo "请用 root 运行"; exit 1; }
mkdir -p "$OUT"

cd "$COMPOSE_DIR"
# compose 解析出的全部镜像（应用 + 中间件，去重）——与运行时完全一致
docker compose config --images | sort -u > "$OUT/image-list.txt"

echo "=== 待备份镜像（$(wc -l < "$OUT/image-list.txt") 个）==="
cat "$OUT/image-list.txt"

echo "=== docker save 全部镜像 → gzip（共享层只存一份）==="
docker save $(cat "$OUT/image-list.txt") | gzip > "$OUT/all-images.tar.gz"

echo "=== 校验与清单 ==="
md5sum "$OUT/all-images.tar.gz" > "$OUT/all-images.tar.gz.md5"
cat "$OUT/all-images.tar.gz.md5"
ls -lh "$OUT/"
{
  echo "# 镜像备份 manifest（$(date -Is)）"
  echo "# 大小：$(du -h "$OUT/all-images.tar.gz" | cut -f1)"
  echo "# 校验：$(cat "$OUT/all-images.tar.gz.md5")"
  echo "# 恢复：docker load < all-images.tar.gz（详见 load-all-images.sh）"
  cat "$OUT/image-list.txt"
} > "$OUT/manifest.txt"

# 顺带写运行时数据备份提示（不含数据，仅清单）
echo "=== 注意：镜像不含数据。数据库备份见 backup/*.sql.gz；卷目录 /opt/agent-platform/data/ ==="
echo "SAVED: $OUT"
