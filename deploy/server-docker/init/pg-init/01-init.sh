#!/bin/bash
# ============================================================================
# PostgreSQL 首次初始化
# ============================================================================
# ★ 2026-10-10 简化：只做官方 entrypoint 不会做的事。
#   库 terminal_kb / 用户 my_user / 其密码，已由 compose 里的
#   POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD 由官方 entrypoint 自动创建，
#   所以这里不再重复 CREATE USER —— 而且原来那句用的是 ${PG_PW}，
#   但容器环境里只有 POSTGRES_PASSWORD、没有 PG_PW，会拿到空密码建出一个登录不了的账号。
#
# 这里只保证 pgvector 扩展可用（镜像 pgvector/pgvector:pg18 已自带该扩展）。
# ============================================================================
set -e

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"

psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "SELECT extname, extversion FROM pg_extension WHERE extname='vector';"

echo "pg init done: pgvector ready on db=$POSTGRES_DB user=$POSTGRES_USER"
