#!/usr/bin/env bash
# ============================================================
# 多租户 schema 的可重复验证（不需要部署、不需要起服务）
#
# 做四件事，任何一条不满足就 exit 1：
#   1. 在临时库里建基线（01-schema-api_db.sql，只改库名）
#   2. 连续执行 05-tenant-schema.sql 三次 —— 验证幂等（迁移必须能重复跑）
#   3. 断言结构：5 张新表 + a_user.ent_code + 索引 + 唯一键
#   4. 断言种子：两家公司、两条 TENANT 额度、16 条页面配置、九台有隐藏页、中文 UTF-8
# 最后删掉临时库（--keep 可保留现场排查）。
#
# 用法：
#   MYSQL_PASSWORD=xxx bash db/mysql/verify-tenant-schema.sh
#   （可选 MYSQL_HOST / MYSQL_PORT / MYSQL_USER；默认 127.0.0.1:3306 root）
#   mysql 客户端不在 PATH 时：MYSQL_BIN="/c/Program Files/MySQL/MySQL Server 8.0/bin"
# ============================================================
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCHEMA="$HERE/01-schema-api_db.sql"
TENANT="$HERE/05-tenant-schema.sql"
SCRATCH="nexusflow_tenant_verify"

HOST="${MYSQL_HOST:-127.0.0.1}"
PORT="${MYSQL_PORT:-3306}"
USER="${MYSQL_USER:-root}"
PASSWORD="${MYSQL_PASSWORD:-}"
MYSQL="${MYSQL_BIN:+$MYSQL_BIN/}mysql"
KEEP="${1:-}"

FAILED=0
pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s  [%s]\n' "$1" "${2:-}"; FAILED=$((FAILED + 1)); }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "期望 $3 实际 $2"; fi; }

MYSQL_ARGS=(-h "$HOST" -P "$PORT" -u "$USER" --default-character-set=utf8mb4)
[ -n "$PASSWORD" ] && MYSQL_ARGS+=("-p$PASSWORD")

sql() { "$MYSQL" "${MYSQL_ARGS[@]}" -N -B "$@" 2>/dev/null; }

echo "MySQL ${USER}@${HOST}:${PORT}  client=$(command -v "$MYSQL" || echo "$MYSQL")"

# 1) 临时库 + 基线结构（只替换库名，其余字节不动）
"$MYSQL" "${MYSQL_ARGS[@]}" -e \
  "DROP DATABASE IF EXISTS \`$SCRATCH\`; CREATE DATABASE \`$SCRATCH\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" 2>/dev/null || {
  echo "无法连接 MySQL（检查 MYSQL_PASSWORD / MYSQL_BIN）" >&2; exit 2; }

BASE_SQL="$(mktemp)"
sed "s/\`api_db\`/\`$SCRATCH\`/g" "$SCHEMA" > "$BASE_SQL"
"$MYSQL" "${MYSQL_ARGS[@]}" "$SCRATCH" < "$BASE_SQL" 2>/dev/null
rm -f "$BASE_SQL"

BASE_TABLES=$(sql -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SCRATCH';")
echo "  基线：${BASE_TABLES} 张表"

# 2) 迁移跑三次（幂等）
OK=1
for i in 1 2 3; do
  if ! "$MYSQL" "${MYSQL_ARGS[@]}" "$SCRATCH" < "$TENANT" 2>/dev/null; then
    OK=0; echo "  第 $i 次执行失败"; break
  fi
done
[ "$OK" = "1" ] && pass "05-tenant-schema.sql 可重复执行（连跑 3 次）" || fail "05-tenant-schema.sql 可重复执行（连跑 3 次）"

# 3) 结构断言
TABLES=$(sql -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SCRATCH';")
check "总表数 = 基线 + 5" "$TABLES" "$((BASE_TABLES + 5))"

for t in a_tenant a_tenant_feature a_tenant_page a_feature_ticket a_feature_ticket_log; do
  N=$(sql -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SCRATCH' AND table_name='$t';")
  check "表 $t 存在" "$N" "1"
done

N=$(sql -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$SCRATCH' AND table_name='a_user' AND column_name='ent_code';")
check "a_user.ent_code 存在" "$N" "1"

N=$(sql -e "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$SCRATCH' AND table_name='a_user' AND index_name='idx_user_ent';")
[ "$N" -ge 1 ] && pass "a_user.idx_user_ent 存在" || fail "a_user.idx_user_ent 存在" "$N"

N=$(sql -e "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$SCRATCH' AND table_name='a_tenant_page' AND index_name='uk_ent_page';")
[ "$N" -ge 1 ] && pass "a_tenant_page.uk_ent_page 唯一键存在" || fail "a_tenant_page.uk_ent_page 唯一键存在" "$N"

# 4) 种子断言
check "种子公司 2 家" "$(sql -e "SELECT COUNT(*) FROM \`$SCRATCH\`.a_tenant;")" "2"
check "TENANT 维度 token 额度 2 条" "$(sql -e "SELECT COUNT(*) FROM \`$SCRATCH\`.a_token_quota WHERE scope='TENANT';")" "2"
check "页面配置 16 条（两家公司各 8 页）" "$(sql -e "SELECT COUNT(*) FROM \`$SCRATCH\`.a_tenant_page;")" "16"

HIDDEN=$(sql -e "SELECT COUNT(*) FROM \`$SCRATCH\`.a_tenant_page WHERE ent_code='JLJT' AND visible=0;")
[ "$HIDDEN" -ge 1 ] && pass "九台有隐藏页（体现两家公司页面不同）" || fail "九台有隐藏页" "$HIDDEN"

# 中文必须按 UTF-8 落库（历史上踩过客户端编码导致乱码）
HEXNAME=$(sql -e "SELECT HEX(ent_name) FROM \`$SCRATCH\`.a_tenant WHERE ent_code='JLCC';")
EXPECTED=$(printf '联通长春分公司' | od -An -tx1 | tr -d ' \n' | tr 'a-f' 'A-F')
check "中文按 UTF-8 落库" "$HEXNAME" "$EXPECTED"

[ "$KEEP" = "--keep" ] || "$MYSQL" "${MYSQL_ARGS[@]}" -e "DROP DATABASE IF EXISTS \`$SCRATCH\`;" 2>/dev/null

echo
if [ "$FAILED" -ne 0 ]; then
  echo "FAILED：$FAILED 项"
  exit 1
fi
echo "ALL PASS"
