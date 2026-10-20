#!/usr/bin/env bash
# NexusFlow AI - one-shot test gate (no deploy).
#
# Runs every automated check we have, in dependency order, and stops with a
# non-zero exit if any of them fails. Intended to be run before any push.
#
#   1. Java orchestrator unit tests (graph topology / routing / kernel client / SSE frames)
#   2. Multi-tenant DB schema verification (idempotent migration + structure + seeds)
#   3. Admin frontend: tsc --noEmit + vitest
#   4. User  frontend: tsc --noEmit + vitest
#
# Usage:
#   bash scripts/test-all.sh              # full gate
#   bash scripts/test-all.sh --no-db      # skip the MySQL-dependent step
#   MYSQL_PASSWORD=xxx bash scripts/test-all.sh
#
# Notes (this machine):
#   - esbuild crashes when its binary sits under a non-ASCII path (winmm.dll not
#     found). We point ESBUILD_BINARY_PATH at an ASCII copy if one exists.
#   - Maven needs JDK 21: export JAVA_HOME=D:/env/jdk-21

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FAILED=0
SKIPPED=0
declare -a RESULTS=()

note_pass() { RESULTS+=("PASS  $1"); printf '  [PASS] %s\n' "$1"; }
note_fail() { RESULTS+=("FAIL  $1"); FAILED=$((FAILED + 1)); printf '  [FAIL] %s\n' "$1"; }
note_skip() { RESULTS+=("SKIP  $1"); SKIPPED=$((SKIPPED + 1)); printf '  [SKIP] %s\n' "$1"; }

run_step() { # name, command...
  local name="$1"; shift
  printf '\n== %s\n' "$name"
  if "$@"; then note_pass "$name"; else note_fail "$name"; fi
}

# vitest 在 Windows 上偶发 esbuild service 启动失败（The service was stopped），
# 与本机安全软件有关、与被测代码无关 —— 给一次重试，避免门禁假红。
run_vitest() { # app dir
  local app="$1"
  printf '\n== %s: vitest\n' "$app"
  if bash -c "cd '$app' && npx vitest run"; then
    note_pass "$app: vitest"
  elif bash -c "cd '$app' && npx vitest run"; then
    note_pass "$app: vitest (retry)"
  else
    note_fail "$app: vitest"
  fi
}

# --- environment -------------------------------------------------------------
# 本项目要 JDK 21（Spring Boot 4 + release 21）。
# 不能只在 JAVA_HOME 为空时才设置：本机 shell 里常年挂着 JDK 17，
# 直接沿用会让 surefire 拿 17 去跑 21 编译的 class（class file version 65 vs 61）。
JDK21="${NEXUSFLOW_JDK21:-D:/env/jdk-21}"
if [ -d "$JDK21" ]; then
  export JAVA_HOME="$JDK21"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [ -z "${ESBUILD_BINARY_PATH:-}" ] && [ -f "D:/env/esbuild/esbuild.exe" ]; then
  export ESBUILD_BINARY_PATH="D:/env/esbuild/esbuild.exe"
fi
export NODE_OPTIONS="${NODE_OPTIONS:-}"
unset SERVER_PORT SERVER__PORT

echo "repo:  $ROOT"
echo "java:  ${JAVA_HOME:-<unset>}"
echo "esbuild: ${ESBUILD_BINARY_PATH:-<bundled>}"

# --- 1. Java ----------------------------------------------------------------
run_step "java: aismp-orchestrator tests" \
  mvn -B -q -pl aismp-orchestrator test

# --- 2. Database ------------------------------------------------------------
if [ "${1:-}" = "--no-db" ]; then
  note_skip "db: multi-tenant schema verification (--no-db)"
else
  run_step "db: multi-tenant schema verification" \
    bash db/mysql/verify-tenant-schema.sh
fi

# --- 3/4. Frontends ---------------------------------------------------------
for app in aismp-frontend-admin aismp-frontend-user; do
  if [ ! -d "$app/node_modules" ]; then
    note_skip "$app (node_modules missing: run npm install --registry=https://registry.npmmirror.com)"
    continue
  fi
  run_step "$app: tsc --noEmit" bash -c "cd '$app' && npx tsc --noEmit"
  # 没有 test 脚本的工程（如只剩门户脚本的用户端）跳过 vitest，不算失败
  if grep -q '"test"' "$app/package.json"; then
    run_vitest "$app"
  else
    note_skip "$app: vitest (no test script)"
  fi
done

# --- summary ----------------------------------------------------------------
printf '\n================ SUMMARY ================\n'
for line in "${RESULTS[@]}"; do printf '%s\n' "$line"; done
printf 'failed=%d skipped=%d\n' "$FAILED" "$SKIPPED"

if [ "$FAILED" -ne 0 ]; then
  echo "RESULT: FAIL"
  exit 1
fi
echo "RESULT: PASS"
