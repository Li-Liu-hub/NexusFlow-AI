#!/bin/bash
# ============================================================================
# functional_test.sh —— 在服务器上跑的全功能端到端测试
# 覆盖：登录鉴权 / 门户业务 / 调拨工单 / 管理端 / agent / 编排层 / 内核直连 / OA-MCP
# 依据：api_db.interface_info 的 68 条登记（已剔除 20 条指向未部署 Mock 服务的 api.aismp.com）
# ============================================================================
BASE=http://127.0.0.1
OUT=/tmp/ft.out
PASS=0; WARN=0; FAIL=0

login() {
  curl -s --noproxy '*' --max-time 15 -X POST -H 'Content-Type: application/json' \
    -d "{\"userCode\":\"$1\",\"password\":\"$2\"}" "$BASE/api/auth/login" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' | head -1
}

call() {   # call <method> <path> <token> [body]
  local m="$1" p="$2" t="$3" body="$4"
  local args=(-s -o "$OUT" -w '%{http_code}' --noproxy '*' --max-time 30 -X "$m")
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local code; code=$(curl "${args[@]}" "$BASE$p" 2>/dev/null)
  local txt; txt=$(head -c 100 "$OUT" 2>/dev/null | tr -d '\n')
  local mark
  case "$code" in
    2*) mark='✅'; PASS=$((PASS+1)) ;;
    4*) mark='⚠️'; WARN=$((WARN+1)) ;;
    5*) mark='❌'; FAIL=$((FAIL+1)) ;;
    *)  mark='??'; FAIL=$((FAIL+1)) ;;
  esac
  printf '  %s %-6s %-44s %-5s %s\n' "$mark" "$m" "$p" "$code" "$txt"
}

sec() { printf '\n\033[1;36m=== %s ===\033[0m\n' "$*"; }

# ---------------------------------------------------------------- 登录
sec "1) 登录鉴权（三个角色）"
for u in 'admin:123456:ADMIN' 'portal_user:123456:USER' 'svc_orchestrator:svc-orch-2026:SERVICE' 'dnop01:123456:OPS'; do
  IFS=: read -r user pw role <<< "$u"
  tk=$(login "$user" "$pw")
  if [ -n "$tk" ]; then
    printf '  ✅ %-18s 角色=%-8s token 长度=%s\n' "$user" "$role" "${#tk}"
    eval "TK_$role='$tk'"
  else
    printf '  ❌ %-18s 登录失败\n' "$user"
  fi
done
TK_ADMIN="${TK_ADMIN:-$TK_SERVICE}"   # 兜底

# ---------------------------------------------------------------- 门户业务
sec "2) 门户业务接口（agent，用 USER 令牌）"
for p in /api/terminal/inventory/snapshot /api/terminal/inventory/aging \
         /api/terminal/inventory/byModel /api/terminal/inventory/serialNos \
         /api/terminal/inventory/aggregate /api/terminal/stat/cards \
         /api/terminal/warehouse/list /api/terminal/health/suggest; do
  call GET "$p" "$TK_USER"
done

sec "3) 调拨工单（agent）"
call GET '/api/transferOrder/list/page?pageNo=1&pageSize=5' "$TK_USER"
call GET '/api/transferOrder/get?id=1' "$TK_USER"
call POST '/api/transferOrder/create' "$TK_USER" '{}'
call POST '/api/transferOrder/outbound' "$TK_USER" '{}'

sec "4) 管理端（gateway → lb://aismp-digital-human）"
for p in /api/admin/agent/list /api/admin/tool/list /api/admin/audit/list /api/admin/token/usage/monthly; do
  call GET "$p" "$TK_ADMIN"
done
call POST '/api/admin/agent/list' "$TK_ADMIN" '{}'

sec "5) agent 通道（/api/agent/**）"
call GET  '/api/agent/list' "$TK_ADMIN"
call POST '/api/agent/chat' "$TK_ADMIN" '{}'

sec "6) 编排层（gateway → 静态 uri http://localhost:8125 ← 已知有问题）"
call GET  '/api/orchestrator/v1/conversation/list' "$TK_USER"
call POST '/api/orchestrator/v1/replenish/preview' "$TK_USER" '{}'
call POST '/api/orchestrator/v1/chat/stream' "$TK_USER" '{}'
call GET  '/orchestrator/' "$TK_USER"

sec "7) 数字员工内核直连路径（gateway → lb://aismp-digital-human）"
call GET  '/digitalHuman/admin/agent/list' "$TK_ADMIN"
call GET  '/digitalHuman/admin/tool/list' "$TK_ADMIN"
call GET  '/digitalHuman/admin/audit/list' "$TK_ADMIN"
call GET  '/digitalHuman/agent/1/introspect' "$TK_ADMIN"
call POST '/digitalHuman/agent/1/chat' "$TK_ADMIN" '{}'

sec "8) OA / MCP 通道"
call POST '/oa/chat/sync' "$TK_ADMIN" '{}'
call POST '/oa/agent/chat' "$TK_ADMIN" '{}'
call GET  '/api/name/get' "$TK_ADMIN"

sec "9) 静态资源与登出"
for p in /portal.html /portal.css /portal.js /admin/ /admin/index.html; do
  call GET "$p" ''
done
call POST '/api/auth/logout' "$TK_USER" '{}'

# ---------------------------------------------------------------- 汇总
sec "汇总"
printf '  ✅ 通过(2xx)=%d   ⚠️ 4xx=%d   ❌ 5xx/异常=%d\n' "$PASS" "$WARN" "$FAIL"
echo
echo "  判读要点："
echo '    · 4xx 多数是【参数缺失 / 需管理员角色】，说明链路通、只是入参不对'
echo '    · 403 = 网关来源白名单或双通道签名问题；401 = 缺/无效 Bearer'
echo '    · 5xx 才是真故障'
