-- ============================================================
-- 74-portal-route-registry.sql — 门户通道（前端经网关）接入登记
-- ============================================================
-- 背景：两个前端（用户端 5173 / 管理台 5174）原先 vite 代理**直连内核 8126**，
-- 绕开了平台的鉴权/限流/计量。按用户决策"收敛到网关"：
--   /api  →  网关 8090  →  lb://aismp-digital-human（StripPrefix=1）
--
-- 网关 CustomGlobalFilter 按 (请求路径, method) 查 interface_info，查不到直接 403，
-- 所以这里要登记门户通道的两个前缀（**网关侧路径**，带 /api 前缀）。
--
-- 认证方式（见 CustomGlobalFilter"三协议通道"）：
--   /api/auth/**   → 匿名放行（登录握手；登录成功时网关把 token→ak 写进 Redis）
--   /api/agent/**、/api/admin/** → Bearer → Redis(portal:token:) → ak
-- （所以 /api/auth/** 不需要登记：匿名分支根本不查 interface_info）
--
-- 幂等：按 (url, method) 判重。
-- ============================================================
USE api_db;

INSERT INTO interface_info (name, description, url, requestParams, requestHeader, responseHeader, status, method, userId, createTime, updateTime, isDelete)
SELECT * FROM (
  SELECT '门户-运行面查询' AS name,
         '数字员工对话/会话列表/自省/审批查询（用户端门户经网关）' AS description,
         '/api/agent/**' AS url,
         '{"message":"可选","conversationId":"可选","userId":"可选"}' AS requestParams,
         '{"Authorization":"Bearer 内核令牌"}' AS requestHeader,
         '{"Content-Type":"application/json"}' AS responseHeader,
         1 AS status, 'GET' AS method, 1 AS userId, NOW() AS createTime, NOW() AS updateTime, 0 AS isDelete
  UNION ALL SELECT '门户-运行面写入', '数字员工对话 / SSE 流式 / 审批放行 / 会话归档',
         '/api/agent/**', '{"message":"","conversationId":"可选","approve":true}',
         '{"Authorization":"Bearer 内核令牌"}', '{"Content-Type":"application/json"}',
         1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT '门户-配置面查询', '管理台查看数字员工/工具/审计/token 用量',
         '/api/admin/**', '{}',
         '{"Authorization":"Bearer 内核令牌（ADMIN 角色）"}', '{"Content-Type":"application/json"}',
         1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '门户-配置面变更', '管理台新建数字员工/挂载工具/改配额等',
         '/api/admin/**', '{"agentCode":"","agentName":"","systemPrompt":""}',
         '{"Authorization":"Bearer 内核令牌（ADMIN 角色）"}', '{"Content-Type":"application/json"}',
         1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT '门户-配置面修改', '管理台修改数字员工/工具/配额',
         '/api/admin/**', '{}',
         '{"Authorization":"Bearer 内核令牌（ADMIN 角色）"}', '{"Content-Type":"application/json"}',
         1, 'PUT', 1, NOW(), NOW(), 0
  UNION ALL SELECT '门户-配置面删除', '管理台删除数字员工/工具/配额/卸挂载',
         '/api/admin/**', '{}',
         '{"Authorization":"Bearer 内核令牌（ADMIN 角色）"}', '{"Content-Type":"application/json"}',
         1, 'DELETE', 1, NOW(), NOW(), 0
) AS v
WHERE NOT EXISTS (SELECT 1 FROM interface_info i WHERE i.url = v.url AND i.method = v.method AND i.isDelete = 0);

-- 给默认用户(id=1) 开通门户接口的调用额度（门户流量按平台默认账号计量）
INSERT INTO user_interface_info (userId, interfaceInfoId, totalNum, leftNum, status, createTime, updateTime, isDelete)
SELECT 1, i.id, 100000, 100000, 0, NOW(), NOW(), 0
FROM interface_info i
WHERE i.isDelete = 0
  AND (i.url LIKE '/api/agent/%' OR i.url LIKE '/api/admin/%')
  AND NOT EXISTS (SELECT 1 FROM user_interface_info u WHERE u.userId = 1 AND u.interfaceInfoId = i.id);

-- 校验
SELECT method, url FROM interface_info
 WHERE (url LIKE '/api/agent/%' OR url LIKE '/api/admin/%') AND isDelete = 0
 ORDER BY method, url;