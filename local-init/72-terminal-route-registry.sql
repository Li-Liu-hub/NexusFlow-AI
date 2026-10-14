-- ============================================================
-- 终端业务（已迁到 aismp-agent :8124）网关接入登记
-- ============================================================
-- 背景：阶段1 把终端库存/调拨业务从 aismp-backend 迁到 aismp-agent，
-- 阶段4 网关路由 terminal_biz_route 改指 lb://aismp-agent（StripPrefix=1，
-- 把网关侧的 /api/terminal/** 剥成 agent 真实的 /terminal/**）。
--
-- 但网关的 CustomGlobalFilter 按 (请求路径, method) 查 interface_info，
-- 查不到直接 403 —— 不登记的话，编排层经网关取数全部被拒。
--
-- 关键点（与 61 号数字员工登记同一套约定）：
--   1. 鉴权过滤器 getOrder() = -1，先于 StripPrefix 执行，
--      所以这里登记的是**带 /api 前缀的网关侧路径**，不是 agent 的真实路径。
--   2. 用 /** 通配覆盖整段前缀（匹配逻辑见 InnerInterfaceInfoServiceImpl：
--      精确优先、Ant 通配兜底）。
--
-- 幂等：按 (url, method) 判重。
-- ============================================================
USE api_db;

INSERT INTO interface_info (name, description, url, requestParams, requestHeader, responseHeader, status, method, userId, createTime, updateTime, isDelete)
SELECT * FROM (
  SELECT '终端库存-查询' AS name,
         '终端库存快照/聚合/库龄/按型号/统计卡片/仓位列表/串码（业务实现在 aismp-agent）' AS description,
         '/api/terminal/**' AS url,
         '{"warehouseCode":"可选","model":"可选","bigType":"可选"}' AS requestParams,
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}' AS requestHeader,
         '{"Content-Type":"application/json"}' AS responseHeader,
         1 AS status, 'GET' AS method, 1 AS userId, NOW() AS createTime, NOW() AS updateTime, 0 AS isDelete
  UNION ALL SELECT '调拨单-列表与详情', '调拨单分页列表 / 按单号查详情',
         '/api/transferOrder/**', '{"current":1,"pageSize":10,"status":"可选","orderNo":"可选"}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '调拨单-写操作', '创建调拨单 / 出库 / 入库 / 驳回（AI 只用到 create 一步）',
         '/api/transferOrder/**', '{"operationType":"","fromWarehouse":"","toWarehouse":"","items":[]}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'POST', 1, NOW(), NOW(), 0
) AS v
WHERE NOT EXISTS (SELECT 1 FROM interface_info i WHERE i.url = v.url AND i.method = v.method AND i.isDelete = 0);

-- 给默认用户(id=1) 开通上述接口的调用额度
INSERT INTO user_interface_info (userId, interfaceInfoId, totalNum, leftNum, status, createTime, updateTime, isDelete)
SELECT 1, i.id, 100000, 100000, 0, NOW(), NOW(), 0
FROM interface_info i
WHERE i.isDelete = 0
  AND (i.url LIKE '/api/terminal/%' OR i.url LIKE '/api/transferOrder/%')
  AND NOT EXISTS (SELECT 1 FROM user_interface_info u WHERE u.userId = 1 AND u.interfaceInfoId = i.id);

-- 校验
SELECT method, url FROM interface_info WHERE (url LIKE '/api/terminal/%' OR url LIKE '/api/transferOrder/%') AND isDelete = 0 ORDER BY method, url;