-- ============================================================
-- 数字员工内核（aismp-digital-human :8126）网关接入登记
-- ============================================================
-- 背景：网关的 CustomGlobalFilter 按 (请求路径, method) 查 interface_info，
-- 查不到直接 403。数字员工内核此前是「直连 8126」，绕开了网关 ——
-- 等于绕开了平台的鉴权、限流与计量（M4 的 token 统计也收不到数字员工的消耗）。
--
-- 关键点（已核实）：
--   1. 鉴权过滤器 getOrder() = -1，先于 StripPrefix 执行，
--      所以这里登记的是**带 /digitalHuman 前缀的网关侧路径**，不是后端真实路径。
--   2. 数字员工内核接口大量使用路径变量（/agent/{code}/chat），
--      而登记表原先只做精确匹配 —— 因此配套改造了 InnerInterfaceInfoServiceImpl：
--      **精确优先、通配兜底**（AntPathMatcher），这里的 */** 才会生效。
--
-- 幂等：按 (url, method) 判重。
-- ============================================================
USE api_db;

INSERT INTO interface_info (name, description, url, requestParams, requestHeader, responseHeader, status, method, userId, createTime, updateTime, isDelete)
SELECT * FROM (
  -- ===== 运行面（编排层 / 用户端调用）=====
  SELECT '数字员工-对话' AS name,
         '数字员工内核：按 agentCode 装配后对话（LLM 自主调用查库工具）' AS description,
         '/digitalHuman/agent/*/chat' AS url,
         '{"message":"字符串","conversationId":"可选","userId":"可选"}' AS requestParams,
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}' AS requestHeader,
         '{"Content-Type":"application/json"}' AS responseHeader,
         1 AS status, 'POST' AS method, 1 AS userId, NOW() AS createTime, NOW() AS updateTime, 0 AS isDelete
  UNION ALL SELECT '数字员工-运行时自省', '查看数字员工此刻装配了什么：技能 / 工具 / 完整提示词 / 配置版本',
         '/digitalHuman/agent/*/introspect', '{}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'GET', 1, NOW(), NOW(), 0

  -- ===== 配置面（管理端调用）=====
  UNION ALL SELECT '数字员工-配置查询', '数字员工列表 / 详情（人设+技能+工具）/ 资源目录',
         '/digitalHuman/admin/agent/**', '{}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '数字员工-创建', '新建数字员工',
         '/digitalHuman/admin/agent/**', '{"agentCode":"","agentName":"","systemPrompt":"","modelName":""}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT '数字员工-修改', '改数字员工配置 / 挂载技能与工具（挂载会自增 version 触发运行时热更新）',
         '/digitalHuman/admin/agent/**', '{"agentName":"","systemPrompt":"","temperature":0.7}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'PUT', 1, NOW(), NOW(), 0
  UNION ALL SELECT '数字员工-删除', '删数字员工 / 卸载挂载关系',
         '/digitalHuman/admin/agent/**', '{}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'DELETE', 1, NOW(), NOW(), 0

  UNION ALL SELECT '工具-配置查询', '查库工具列表 / 详情 / SQL 模板样例 / 可查库表结构',
         '/digitalHuman/admin/tool/**', '{}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'GET', 1, NOW(), NOW(), 0
  UNION ALL SELECT '工具-创建', '动态创建一个查库工具（含静态校验 + 试运行）—— 插一行即得到工具，不写 Java 代码',
         '/digitalHuman/admin/tool/**', '{"toolName":"","toolDesc":"","inputSchema":"{}","sqlTemplate":"SELECT ..."}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'POST', 1, NOW(), NOW(), 0
  UNION ALL SELECT '工具-修改', '改工具定义（会 bump 挂载该工具的数字员工 version）',
         '/digitalHuman/admin/tool/**', '{"id":1,"toolName":"","sqlTemplate":""}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'PUT', 1, NOW(), NOW(), 0
  UNION ALL SELECT '工具-删除', '删工具（先摘掉挂载关系，避免悬空引用）',
         '/digitalHuman/admin/tool/**', '{}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'DELETE', 1, NOW(), NOW(), 0

  UNION ALL SELECT '审计-查询', '工具调用明细（含入参/耗时/结果数）与按工具聚合统计',
         '/digitalHuman/admin/audit/**', '{"limit":20}',
         '{"accessKey":"","nonce":"","timestamp":"","sign":""}', '{"Content-Type":"application/json"}',
         1, 'GET', 1, NOW(), NOW(), 0
) AS v
WHERE NOT EXISTS (SELECT 1 FROM interface_info i WHERE i.url = v.url AND i.method = v.method AND i.isDelete = 0);

-- 给默认用户(id=1) 开通上述接口的调用额度
INSERT INTO user_interface_info (userId, interfaceInfoId, totalNum, leftNum, status, createTime, updateTime, isDelete)
SELECT 1, i.id, 100000, 100000, 0, NOW(), NOW(), 0
FROM interface_info i
WHERE i.isDelete = 0
  AND i.url LIKE '/digitalHuman/%'
  AND NOT EXISTS (SELECT 1 FROM user_interface_info u WHERE u.userId = 1 AND u.interfaceInfoId = i.id);

-- 校验
SELECT method, url FROM interface_info WHERE url LIKE '/digitalHuman/%' AND isDelete = 0 ORDER BY method, url;
