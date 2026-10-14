-- ============================================================
-- 79 · 编排层门户通道的网关登记（2026-10-07）
-- ============================================================
-- 背景：门户主对话的「智能路由」入口与通知面板的「扫描本仓」改走编排层
--   （agent-orchestrator :8125），经网关新路由 orchestrator_portal_route
--   （/api/orchestrator/** → StripPrefix=1 → /orchestrator/**）。
--
-- 网关 CustomGlobalFilter 对**门户 Bearer 通道同样做 interface_info 校验**
-- （第 4 步，两条通道共用）—— 未登记的 (路径, 方法) 一律 403，故补登记。
--
-- 约定同 72/74/78 号：鉴权过滤器 getOrder()=-1 先于 StripPrefix 执行，
-- 登记的是**带 /api 前缀的网关侧路径**；用 /** 覆盖整段前缀。
--
-- 覆盖的接口（编排层真实路径，StripPrefix=1 后）：
--   POST /orchestrator/v1/chat/stream  三条路线的对话 SSE（chart / replenish+order / kb）
--   POST /orchestrator/v1/alert/scan   库存告警手动巡检（编排层 scan→判定→notify）
--
-- 幂等：按 (url, method) 判重。
-- ============================================================
USE api_db;

INSERT INTO interface_info (name, description, url, requestParams, requestHeader, responseHeader, status, method, userId, createTime, updateTime, isDelete)
SELECT * FROM (
  SELECT '门户-编排对话' AS name,
         '编排层三条路线（生成图表/补货建议+建单/知识问答）SSE 对话；经网关鉴权+限流+计量' AS description,
         '/api/orchestrator/**' AS url,
         '{"query":"用户问题","user_id":"可选","suggestion_id":"可选（两段式建单凭据）"}' AS requestParams,
         '{"Authorization":"Bearer <门户登录令牌>"}' AS requestHeader,
         '{"Content-Type":"text/event-stream"}' AS responseHeader,
         1 AS status, 'POST' AS method, 1 AS userId, NOW() AS createTime, NOW() AS updateTime, 0 AS isDelete
  UNION ALL SELECT '门户-编排查询', '编排层健康检查等只读入口（预留）',
         '/api/orchestrator/**', '{}',
         '{"Authorization":"Bearer <门户登录令牌>"}', '{"Content-Type":"application/json"}',
         1, 'GET', 1, NOW(), NOW(), 0
) AS v
WHERE NOT EXISTS (SELECT 1 FROM interface_info i WHERE i.url = v.url AND i.method = v.method AND i.isDelete = 0);

-- 给默认用户(id=1) 开通编排层接口的调用额度（门户流量按平台默认账号计量）
INSERT INTO user_interface_info (userId, interfaceInfoId, totalNum, leftNum, status, createTime, updateTime, isDelete)
SELECT 1, i.id, 100000, 100000, 0, NOW(), NOW(), 0
FROM interface_info i
WHERE i.isDelete = 0 AND i.url = '/api/orchestrator/**'
  AND NOT EXISTS (SELECT 1 FROM user_interface_info u WHERE u.userId = 1 AND u.interfaceInfoId = i.id);

-- 校验
SELECT method, url FROM interface_info WHERE url LIKE '/api/orchestrator/%' AND isDelete = 0 ORDER BY method, url;