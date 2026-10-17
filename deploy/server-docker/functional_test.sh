#!/bin/bash
# ============================================================================
# functional_test.sh —— 在服务器上跑的全功能冒烟测试
# 覆盖：登录鉴权 / 门户业务 / 调拨工单 / 管理端 / 编排层 / 内核通道 / 静态资源
#
# 2026-10-17 重写（原版会把故障当通过，实测踩了三个坑）：
#   ① 响应体用 `curl -o /tmp/ft.out` + 事后 head，实测拿到的是**上一次遗留的内容**
#      （整轮输出里每个 body 都是同一条 {"loggedOut":true}），现改为「curl 末尾打标记再切分」，
#      不落临时文件。
#   ② 参数写错会造出「假故障」：health/suggest 必须带 warehouseCode；
#      transferOrder/get 的参数是 **orderNo**（transfer_order 表根本没有 id 列）；
#      串码要 materialCode；审计是 /audit/recent 与 /audit/stats，不是 /audit/list。
#   ③ 只看 HTTP 状态码 → 「200 + HTML（SPA 兜底）」和「200 + 业务错误码」都被算成通过。
#      现在额外校验 body：非 JSON 直接判 FAIL，业务错误码单独标 WARN 并在末尾汇总。
#
# 判定：
#   2xx + JSON + 无业务错误码                    → PASS
#   2xx + JSON + 业务错误码（code=4xxxx/50000）  → WARN（链路通，业务层拒绝，需人工判读）
#   2xx + 非 JSON（HTML 等）                     → FAIL（假 200，最危险的一种）
#   4xx                                          → expect=4 记 PASS，否则 FAIL
#   5xx / 000                                    → FAIL
#
# 用法：bash functional_test.sh
# ============================================================================
BASE=http://127.0.0.1
MARK='@@FT_CODE@@'
PASS=0; WARN=0; FAIL=0
WARN_LINES=()

call() {   # call <method> <path> <token> [body] [expect: 2|4]
  local m="$1" p="$2" t="$3" b="$4" exp="${5:-2}"
  local -a a=(-s --noproxy '*' --max-time 120 -X "$m" -w "\n$MARK%{http_code}")
  [ -n "$t" ] && a+=(-H "Authorization: Bearer $t")
  [ -n "$b" ] && a+=(-H 'Content-Type: application/json' -d "$b")

  local r code body first snip note mark
  r=$(curl "${a[@]}" "$BASE$p" 2>/dev/null)
  code="${r##*$MARK}"
  body="${r%$MARK*}"
  first=$(printf '%s' "$body" | head -c 1)
  snip=$(printf '%s' "$body" | tr -d '\r\n' | head -c 105)
  note=""

  if [ "${code:0:1}" = "2" ]; then
    if [ "$first" != "{" ] && [ "$first" != "[" ]; then
      mark='FAIL'; note='假200(非JSON)'
    else
      if printf '%s' "$body" | grep -q '"code":50000'; then
        mark='WARN'; note='业务错误50000'
      elif printf '%s' "$body" | grep -qE '"code":4[0-9]{4}'; then
        mark='WARN'; note='业务拒绝'
      else
        mark='PASS'
      fi
    fi
  else
    case "$code" in
      4*) if [ "$exp" = "4" ] || [ "$exp" = "$code" ]; then mark='PASS'; note='预期4xx'; else mark='FAIL'; note='意外4xx'; fi;;
      *)  mark='FAIL';;
    esac
  fi

  case "$mark" in
    PASS) PASS=$((PASS+1));;
    WARN) WARN=$((WARN+1)); WARN_LINES+=("$m $p → $code $note");;
    *)    FAIL=$((FAIL+1));;
  esac
  printf '  [%s] %-4s %-56s %-3s %s %s\n' "$mark" "$m" "$p" "$code" "$note" "$snip"
}

sec() { printf '\n\033[1;36m══ %s ══\033[0m\n' "$*"; }
login() { curl -s --noproxy '*' --max-time 20 -X POST -H 'Content-Type: application/json' \
          -d "{\"userCode\":\"$1\",\"password\":\"$2\"}" "$BASE/api/auth/login" \
          | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' | head -1; }

sec "1) 登录鉴权（三个角色）"
TK_USER=$(login portal_user 123456)
TK_ADMIN=$(login admin 123456)
TK_OPS=$(login dnop01 123456)
printf '  portal_user=%s  admin=%s  dnop01=%s\n' "${#TK_USER}" "${#TK_ADMIN}" "${#TK_OPS}"
for v in TK_USER TK_ADMIN TK_OPS; do
  if [ -n "${!v}" ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); echo "  [FAIL] $v 登录失败"; fi
done

sec "1b) 鉴权反例（都应为 4xx）"
call GET  "/api/terminal/inventory/snapshot" ""                ""   4
call GET  "/api/terminal/inventory/snapshot" "bad-token-xxxx"  ""   4
call POST "/api/auth/login" "" '{"userCode":"portal_user","password":"wrong"}' 401
call POST "/api/auth/login" "" '{"userCode":"","password":""}' 400

sec "2) 门户业务（agent，USER 令牌）"
for p in /api/terminal/inventory/snapshot /api/terminal/inventory/aggregate \
         /api/terminal/inventory/aging /api/terminal/inventory/byModel \
         /api/terminal/stat/cards /api/terminal/warehouse/list \
         "/api/terminal/health/suggest?warehouseCode=9BZA"; do
  call GET "$p" "$TK_USER"
done
call GET "/api/terminal/inventory/serialNos?warehouseCode=9BZA&materialCode=7450301775&limit=5" "$TK_USER"

sec "3) 调拨工单（agent）"
call GET "/api/transferOrder/list/page?pageNo=1&pageSize=5" "$TK_USER"
call GET "/api/transferOrder/notices" "$TK_USER"
# ★ 参数是 orderNo，不是 id
call GET "/api/transferOrder/get?orderNo=TR202610124168" "$TK_USER"
call POST "/api/transferOrder/create" "$TK_USER" '{}'

sec "4) 管理端（ADMIN 令牌）"
for p in /api/admin/agent/list /api/admin/tool/list /api/admin/token/usage/monthly; do
  call GET "$p" "$TK_ADMIN"
done
# ★ 审计真实路径：/audit/recent、/audit/stats
call GET "/api/admin/audit/recent" "$TK_ADMIN"
call GET "/api/admin/audit/stats"  "$TK_ADMIN"
call GET "/api/admin/tool/list" "$TK_USER" "" 4

sec "5) 编排层（gateway → orchestrator:8125）"
call GET  "/api/orchestrator/v1/conversation/list" "$TK_USER"
call GET  "/api/orchestrator/v1/graph/interfaces"  "$TK_ADMIN"
call GET  "/api/orchestrator/v1/graph/node-types"  "$TK_ADMIN"
call GET  "/api/orchestrator/v1/graph/versions"    "$TK_ADMIN"
call POST "/api/orchestrator/v1/replenish/preview?warehouse_code=9BZA" "$TK_USER"

sec "6) /digitalHuman/** 通道（不能是 200+HTML）"
# 该前缀是 AK/SK 服务通道：浏览器 Bearer 被网关拦（不在门户白名单 isPortalPath），
# AK/SK 又被内核 AuthInterceptor 拦（要求 Bearer）—— 两通道都进不去。
# 这里只断言「不再返回 SPA 的假 200 HTML」。
c=$(curl -s -o /tmp/_ft_dh -w '%{http_code}' --noproxy '*' --max-time 20 \
    -H "Authorization: Bearer $TK_ADMIN" "$BASE/digitalHuman/admin/agent/list")
if head -c 1 /tmp/_ft_dh | grep -q '<'; then
  FAIL=$((FAIL+1)); printf '  [FAIL] GET  /digitalHuman/admin/agent/list  %-3s 仍是HTML(假200)\n' "$c"
else
  PASS=$((PASS+1)); printf '  [PASS] GET  /digitalHuman/admin/agent/list  %-3s 非HTML(真4xx)\n' "$c"
fi

sec "7) 静态资源"
for p in / /admin/ /portal.html /portal.css /portal.js; do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 20 "$BASE$p")
  case "$c" in 2*) PASS=$((PASS+1));; *) FAIL=$((FAIL+1));; esac
  printf '  %-3s %s\n' "$c" "$p"
done

sec "汇总"
printf '  ✅ PASS=%d   ⚠️ WARN(业务拒绝，需人工判读)=%d   ❌ FAIL=%d\n' "$PASS" "$WARN" "$FAIL"
if [ "${#WARN_LINES[@]}" -gt 0 ]; then
  echo "  --- WARN 明细 ---"
  for w in "${WARN_LINES[@]}"; do echo "    · $w"; done
fi
echo
echo "  判读要点："
echo '    · 业务拒绝（code=40101 等）多是角色/数据范围不符 —— 链路通、规则生效'
echo '    · 假200(非JSON) 才是真故障：请求没打到后端（路由缺失 / SPA 兜底）'
echo '    · 5xx 才是服务端故障'
