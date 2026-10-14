#!/bin/bash
# ============================================================================
# 在「新服务器」上执行：把迁移数据导入 Docker 容器
# 前置：docker compose up -d mysql postgres 已启动且 healthy
# ============================================================================
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
MIG=/home/ubuntu/migrate          # 迁移产物目录
cd "$DIR"

[ -f .env ] || { echo "缺少 .env（从 .env.example 复制填写）"; exit 1; }
set -a; . ./.env; set +a

echo "[1/3] 导入 MySQL api_db（含平台表 + 终端业务 + 数字员工表）..."
# api_db.sql.gz 来自旧服务器；digital-human-data.sql.gz 来自本机（64 脚本产出的三员工+配置）
if [ -f "$MIG/api_db.sql.gz" ]; then
  gunzip -c "$MIG/api_db.sql.gz" | docker exec -i ap-mysql mysql -u root -p"$MYSQL_ROOT_PW" api_db
fi
if [ -f "$MIG/digital-human-data.sql.gz" ]; then
  gunzip -c "$MIG/digital-human-data.sql.gz" | docker exec -i ap-mysql mysql -u root -p"$MYSQL_ROOT_PW" terminal_db
  # 数字员工表在 terminal_db，但 backend 读的是 api_db —— 把数字员工表同步建到 api_db 供 backend 代理场景
  gunzip -c "$MIG/digital-human-data.sql.gz" | docker exec -i ap-mysql mysql -u root -p"$MYSQL_ROOT_PW" api_db || true
fi

echo "[2/3] 导入 PostgreSQL terminal_kb（向量 + 会话记忆）..."
if [ -f "$MIG/terminal_kb.sql.gz" ]; then
  gunzip -c "$MIG/terminal_kb.sql.gz" | docker exec -i ap-postgres psql -U postgres -d terminal_kb
fi

echo "[3/3] 校验"
docker exec ap-mysql mysql -u root -p"$MYSQL_ROOT_PW" -N -e \
  "SELECT CONCAT(table_schema,'.',table_name,'=',table_rows) FROM information_schema.tables WHERE table_schema IN ('api_db','terminal_db') ORDER BY 1;" 2>/dev/null | head -20
docker exec ap-postgres psql -U postgres -d terminal_kb -t -c "SELECT 'vector_store='||COUNT(*) FROM vector_store;"

echo "导入完成。启动应用层：docker compose up -d"
