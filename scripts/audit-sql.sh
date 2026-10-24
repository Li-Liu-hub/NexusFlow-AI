#!/bin/bash
# SQL 谓词审计：repo 文件中的业务 SELECT 必须含 isDelete/tenant/状态谓词（启发式，输出待人工复核清单）
set -euo pipefail
cd "$(dirname "$0")/../go"
fail=0
while IFS= read -r f; do
  # 多行 SQL 里的裸 SELECT（无 WHERE）粗筛
  grep -Pz "SELECT[^;]{40,}" "$f" 2>/dev/null | tr '\0' '\n' | grep -iE '^SELECT' | while IFS= read -r q; do
    case "$q" in
      *COUNT*|*information_schema*|*DATE_FORMAT*) ;;
      *WHERE*|*where*) ;;
      *) echo "审查: $f :: ${q:0:80}（业务查询缺 WHERE 谓词）" ;;
    esac
  done
done < <(find internal -name 'repository*.go' -o -name '*_repo.go' | grep -v _test)
echo "audit-sql: 完成（输出为人工复核清单，非硬失败）"
