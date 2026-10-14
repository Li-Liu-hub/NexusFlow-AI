-- ============================================================
-- 78 · 库存告警巡检接口的网关登记（2026-10-07）
-- ============================================================
-- 背景：新增 POST /api/terminal/stockAlert/scan（手动触发库存告警巡检）。
-- 72 号只登记了 /api/terminal/** 的 GET —— 网关的 CustomGlobalFilter 对
-- **门户 Bearer 通道同样做 interface_info 校验**（第 4 步，两条通道共用），
-- 未登记的 (路径, 方法) 一律 403。故补一条 POST 通配。
--
-- 约定同 72 号：鉴权过滤器 getOrder()=-1 先于 StripPrefix 执行，
-- 登记的是**带 /api 前缀的网关侧路径**；用 /** 覆盖整段前缀。
--
-- 幂等：按 (url, method) 判重。
-- ============================================================
USE api_db;

INSERT INTO interface_info (name, description, url, requestParams, requestHeader, responseHeader, status, method, userId, createTime, updateTime, isDelete)
SELECT * FROM (
  SELECT '终端库存-写操作' AS name,
         '库存告警巡检手动触发（POST /api/terminal/stockAlert/scan；业务实现在 aismp-agent）' AS description,
         '/api/terminal/**' AS url,
         '{"warehouseCode":"可选，不传则扫描全部仓"}' AS requestParams,
         '{"Authorization":"Bearer <门户登录令牌>"}' AS requestHeader,
         '{"Content-Type":"application/json"}' AS responseHeader,
         1 AS status, 'POST' AS method, 1 AS userId, NOW() AS createTime, NOW() AS updateTime, 0 AS isDelete
) AS v
WHERE NOT EXISTS (SELECT 1 FROM interface_info i WHERE i.url = v.url AND i.method = v.method AND i.isDelete = 0);

-- 给默认用户(id=1) 开通该接口的调用额度
INSERT INTO user_interface_info (userId, interfaceInfoId, totalNum, leftNum, status, createTime, updateTime, isDelete)
SELECT 1, i.id, 100000, 100000, 0, NOW(), NOW(), 0
FROM interface_info i
WHERE i.isDelete = 0 AND i.url = '/api/terminal/**' AND i.method = 'POST'
  AND NOT EXISTS (SELECT 1 FROM user_interface_info u WHERE u.userId = 1 AND u.interfaceInfoId = i.id);

-- 校验
SELECT method, url FROM interface_info
 WHERE url LIKE '/api/terminal/%' AND isDelete = 0 ORDER BY method, url;