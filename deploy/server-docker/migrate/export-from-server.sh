#!/bin/bash
# ============================================================================
# 在「旧服务器」上执行：导出全部业务数据到 /home/ubuntu/migrate/
# 产物：api_db.sql.gz（平台+终端业务）、terminal_kb.sql.gz（向量+记忆）、
#       digital-human.sql.gz（本机生成的三个数字员工配置，随包携带）
# ============================================================================
set -euo pipefail
OUT=/home/ubuntu/migrate
mkdir -p "$OUT"
set -a; . /opt/agent-platform-secure/credentials.env; set +a

echo "[1/3] 导出 MySQL api_db（平台 + 终端业务 28354 条）..."
mysqldump -u"$MYSQL_APP_USER" -p"$MYSQL_APP_PW" \
  --single-transaction --routines --triggers --set-gtid-purged=OFF \
  api_db | gzip > "$OUT/api_db.sql.gz"

echo "[2/3] 导出 PostgreSQL terminal_kb（向量 + 会话记忆）..."
sudo -u postgres pg_dump --clean --if-exists terminal_kb | gzip > "$OUT/terminal_kb.sql.gz"

echo "[3/3] 完成。产物："
ls -lh "$OUT"
echo ""
echo "→ 用 scp 取回：scp ubuntu@<服务器>:/home/ubuntu/migrate/*.gz ./"
