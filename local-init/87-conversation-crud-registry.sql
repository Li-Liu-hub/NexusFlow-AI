-- ============================================================
-- 87 · 会话 CRUD 的网关登记（2026-10-08）
-- ============================================================
-- 背景：门户「最近对话」新增重命名 / 删除能力，
--   编排层新增端点（agent-orchestrator server.py）：
--     PUT    /orchestrator/v1/conversation/{id}   重命名标题
--     DELETE /orchestrator/v1/conversation/{id}   删除会话及消息
--   经网关 orchestrator_portal_route（/api/orchestrator/** → StripPrefix=1）转发。
--
-- 网关 CustomGlobalFilter 对门户 Bearer 通道同样做 interface_info (url, method)
-- 校验 —— 79 号只登记了 GET/POST，PUT/DELETE 会 403，故补两条通配行。
--
-- 约定同 79 号：登记的是带 /api 前缀的网关侧路径，用 /** 覆盖整段前缀；
-- 幂等：按 (url, method) 判重。
-- ============================================================
USE api_db;

INSERT INTO interface_info (name, description, url, requestParams, requestHeader, responseHeader, status, method, userId, createTime, updateTime, isDelete)
SELECT * FROM (
  SELECT '门户-会话重命名' AS name,
         '编排层会话标题重命名（归属校验：仅本人会话可改，越权 404）' AS description,
         '/api/orchestrator/**' AS url,
         '{"title":"新标题"}' AS requestParams,
         '{"Authorization":"Bearer <门户登录令牌>"}' AS requestHeader,
         '{"Content-Type":"application/json"}' AS responseHeader,
         1 AS status, 'PUT' AS method, 1 AS userId, NOW() AS createTime, NOW() AS updateTime, 0 AS isDelete
  UNION ALL SELECT '门户-会话删除', '编排层会话删除（连消息一并删；归属校验同上）',
         '/api/orchestrator/**', '{}',
         '{"Authorization":"Bearer <门户登录令牌>"}', '{"Content-Type":"application/json"}',
         1, 'DELETE', 1, NOW(), NOW(), 0
) AS v
WHERE NOT EXISTS (SELECT 1 FROM interface_info i WHERE i.url = v.url AND i.method = v.method AND i.isDelete = 0);

-- 给默认用户(id=1) 开通调用额度（门户流量按平台默认账号计量，同 79 号）
INSERT INTO user_interface_info (userId, interfaceInfoId, totalNum, leftNum, status, createTime, updateTime, isDelete)
SELECT 1, i.id, 100000, 100000, 0, NOW(), NOW(), 0
FROM interface_info i
WHERE i.isDelete = 0 AND i.url = '/api/orchestrator/**'
  AND NOT EXISTS (SELECT 1 FROM user_interface_info u WHERE u.userId = 1 AND u.interfaceInfoId = i.id);

-- 校验
SELECT method, url FROM interface_info WHERE url LIKE '/api/orchestrator/%' AND isDelete = 0 ORDER BY method, url;
