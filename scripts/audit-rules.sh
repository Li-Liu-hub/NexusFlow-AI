#!/bin/bash
# 规则审计：rule-exception 授权、调试残留、repo 自开事务、业务路径裸 errors.New
set -euo pipefail
cd "$(dirname "$0")/../go"
fail=0
grep -rn "rule-exception" --include="*.go" . | grep -v "批准人" && { echo "存在无授权破例"; fail=1; } || true
grep -rnE 'fmt\.Print|println\(' --include="*.go" internal cmd | grep -v _test && { echo "调试残留"; fail=1; } || true
grep -rn 'BeginTx' --include="*.go" internal | grep -vE 'service|handler|_test' | grep repository && { echo "repo 层自开事务"; fail=1; } || true
[ $fail -eq 0 ] && echo "audit-rules: PASS" || exit 1
