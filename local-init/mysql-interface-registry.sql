-- ============================================================
-- 网关接口登记：CustomGlobalFilter 会用 (请求路径, 方法) 精确查 interface_info，
-- 查不到直接 403。所有要经网关访问的路径都必须在此登记。
-- 幂等：按 (url, method) 判重。
-- ============================================================
USE api_db;

INSERT INTO interface_info (name, description, url, requestParams, requestHeader, responseHeader, status, method, userId, createTime, updateTime, isDelete)
SELECT * FROM (
  SELECT 'Mock-获取名称(GET)'   AS name, 'Mock 服务：GET /name/get'      AS description, '/api/name/get'  AS url, '{"name":"字符串"}' AS requestParams, '{"accessKey":"","nonce":"","timestamp":"","sign":""}' AS requestHeader, '{"Content-Type":"application/json"}' AS responseHeader, 1 AS status, 'GET'  AS method, 1 AS userId, NOW() AS createTime, NOW() AS updateTime, 0 AS isDelete
  UNION ALL SELECT 'Mock-获取名称(POST)', 'Mock 服务：POST /name/post', '/api/name/post', '{"name":"字符串"}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT 'Mock-用户信息(POST)', 'Mock 服务：POST /name/user', '/api/name/user', '{"username":"字符串"}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT 'OA 智能客服(流式)',   'ChatClient + RAG + 工具，SSE 流式', '/oa/chat', '{"message":"","conversationId":""}', '{"accessKey":"","nonce":"","timestamp":"","sign":"","X-User-Id":"","X-Area-Code":""}', '{"Content-Type":"text/event-stream"}', 1, 'POST', 1, NOW(), NOW(), 0
  -- 2026-10-17 补：数字员工的知识检索工具 searchHandbook 调的就是这个同步端点。
  --   之前只登记了 /oa/chat（流式），漏了 /oa/chat/sync → 网关按「接口未登记」一律 403，
  --   症状是知识问答回答「知识手册检索服务暂时不可用（status=403）」，agent 侧连日志都没有。
  UNION ALL SELECT 'OA RAG 问答(同步)',   '知识检索工具 searchHandbook 的下游，一次性返回完整回答', '/oa/chat/sync', '{"message":"必填","conversationId":"必填"}', '{"accessKey":"","nonce":"","timestamp":"","sign":"","body":""}', '{"Content-Type":"application/json"}', 1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT 'OA 智能体(SSE)',      'ReAct 四层架构，SseEmitter 逐步推送', '/oa/agent/chat/sse', '{}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"text/event-stream"}', 1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT 'LangGraph 编排(SSE)', 'Python 编排层：supervise→rewrite→executor→critic→respond', '/orchestrator/v1/chat/stream', '{"query":"字符串"}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"text/event-stream"}', 1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT 'MCP SSE 通道',        'MCP Server SSE 建流入口（Bearer token 鉴权）', '/sse', '{}', '{"Authorization":"Bearer <token>"}', '{"Content-Type":"text/event-stream"}', 1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT 'MCP 消息通道',         'MCP Server 消息投递入口（Bearer token 鉴权）', '/mcp/message', '{}', '{"Authorization":"Bearer <token>"}', '{"Content-Type":"application/json"}', 1, 'POST', 1, NOW(), NOW(), 0
  -- ===== M1 终端业务（跑在 aismp-backend 7529，网关需加 terminal_biz_route 路由）=====
  UNION ALL SELECT '终端-库存聚合',   '按仓位×物料×型号聚合库存（补货算法取数）', '/api/terminal/inventory/aggregate', '{"warehouseCode":"","model":"","bigType":""}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '终端-库存快照',   '一次取全：库存聚合+库龄分布+按型号汇总（保证一致视图）', '/api/terminal/inventory/snapshot', '{"warehouseCode":"","model":""}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '终端-库龄分布',   '库龄分桶统计（长库龄盘活判据）', '/api/terminal/inventory/aging', '{"warehouseCode":""}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '终端-型号汇总',   '按型号跨仓位汇总（判断全省是否有货、是否转采购）', '/api/terminal/inventory/byModel', '{}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '终端-统计卡片',   '首页四个统计卡片数字', '/api/terminal/stat/cards', '{}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '终端-仓位列表',   '按层级列出全部仓位', '/api/terminal/warehouse/list', '{}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '终端-可用串码',   '列某仓位某物料的可用串码（出库选码/前端模板用，按库龄倒序）', '/api/terminal/inventory/serialNos', '{"warehouseCode":"","materialCode":"","limit":10}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '调拨-建单',       '创建调拨单（AI 与手工双入口，校验四条硬规则）', '/api/transferOrder/create', '{"operationType":"","fromWarehouse":"","toWarehouse":"","items":[]}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT '调拨-出库',       '按单导入串码出库（型号/数量/状态三重比对）', '/api/transferOrder/outbound', '{"orderNo":"","serialNos":[]}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT '调拨-入库',       '入库确认（串码换仓到调入方）', '/api/transferOrder/inbound', '{"orderNo":"","operator":""}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT '调拨-拒绝',       '拒绝调拨单（通知发起方）', '/api/transferOrder/reject', '{"orderNo":"","reason":""}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT '调拨-详情',       '调拨单详情（含明细与串码）', '/api/transferOrder/get', '{"orderNo":""}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '调拨-列表',       '调拨单分页列表（前端跟踪表用）', '/api/transferOrder/list/page', '{"current":1,"pageSize":10,"status":""}', '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}', 1, 'GET', 1, NOW(), NOW(), 0
) AS v
WHERE NOT EXISTS (SELECT 1 FROM interface_info i WHERE i.url = v.url AND i.method = v.method AND i.isDelete = 0);

-- 给默认用户(id=1) 开通上述全部接口的调用额度
INSERT INTO user_interface_info (userId, interfaceInfoId, totalNum, leftNum, status, createTime, updateTime, isDelete)
SELECT 1, i.id, 100000, 100000, 0, NOW(), NOW(), 0
FROM interface_info i
WHERE i.isDelete = 0
  AND NOT EXISTS (SELECT 1 FROM user_interface_info u WHERE u.userId = 1 AND u.interfaceInfoId = i.id);
