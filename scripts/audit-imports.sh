#!/bin/bash
# 依赖方向检查：service 层禁 gin/net/http；cmd 不得写业务（启发：cmd 内禁 SQL 字符串）
set -euo pipefail
cd "$(dirname "$0")/../go"
fail=0
while IFS= read -r f; do
  grep -nE '"github.com/gin-gonic/gin"|"net/http"' "$f" | grep -v '^ *//' && { echo "违反分层: $f (service 禁 gin/net/http)"; fail=1; }
done < <(find internal -path '*service*' -name '*.go' 2>/dev/null; find internal -name 'service.go' 2>/dev/null)
while IFS= read -r f; do
  grep -nE 'SELECT |INSERT INTO|UPDATE .* SET' "$f" >/dev/null && { echo "cmd 内不得写 SQL: $f"; fail=1; }
done < <(find cmd -name '*.go' ! -name '*_test.go')
[ $fail -eq 0 ] && echo "audit-imports: PASS" || exit 1
