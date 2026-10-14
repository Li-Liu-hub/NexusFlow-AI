-- ============================================================================
-- 2026-10-13  标识符改名 zmbwl -> aismp 的【线上数据迁移】
--
-- 背景：项目标识符由五人团队首字母 zmbwl 改为产品英文缩写 aismp
--       （AI Smart Management Platform）。代码/镜像改完之后，**存量数据里
--       仍有旧标识**，不改会导致：① 平台账号登录失败（口令盐变了）
--       ② 网关 AK 对不上 ③ 数字员工工具找不到 agent 服务（Nacos 服务发现）
--
-- 适用范围：改造前就已经跑起来的库（线上 49.234.49.192 / 本机 dev 库）。
--          用 `db/` 或 `local-init/` 的种子从零建库的环境**不需要**跑本脚本。
--
-- 执行（宿主机容器里）：
--   docker exec -i ap-mysql mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PW" < 本文件
--   docker exec -i ap-postgres psql -U my_user -d terminal_kb -c "<PG 那两行>"
-- ============================================================================

-- ---------- MySQL ----------
-- ① 平台账号口令：UserServiceImpl 的 SALT 由 zmbwl-platform 改为 aismp-platform，
--    MD5(SALT+'123456') 随之变化：35d8b1ba82d832ae1ce7c400a4641e09 -> 436bf1095bd9baab1c387e7d8f072794
--    （门户/管理台登录走 a_user 的 SHA256 无盐，不受影响；这里只影响 user 表）
UPDATE `api_db`.`user`
   SET userPassword = '436bf1095bd9baab1c387e7d8f072794'
 WHERE userPassword = '35d8b1ba82d832ae1ce7c400a4641e09';

-- ② 网关 AK
UPDATE `api_db`.`user`
   SET accessKey = 'aismp-console'
 WHERE accessKey = 'zmbwl-console';

-- ③ 接口登记里的描述文案
UPDATE `api_db`.`interface_info`
   SET description = REPLACE(description, 'zmbwl', 'aismp')
 WHERE description LIKE '%zmbwl%';

-- ④ 数字员工工具：serviceName 走 Nacos 服务发现，必须跟着改名
--    （ApiCallToolCallback.discoveryClient.getInstances(serviceName)）
UPDATE `terminal_db`.`a_llm_tool`
   SET config_json = REPLACE(config_json, 'zmbwl', 'aismp'),
       remark      = REPLACE(remark,      'zmbwl', 'aismp')
 WHERE config_json LIKE '%zmbwl%' OR remark LIKE '%zmbwl%';

-- ---------- PostgreSQL（terminal_kb）----------
-- ⑤ 画布节点库的描述文案
-- UPDATE orch_node_def SET description = REPLACE(description, 'zmbwl', 'aismp')
--  WHERE description LIKE '%zmbwl%';

-- ---------- 迁移后自检（期望全是 0）----------
-- SELECT 'user.ak',   COUNT(*) FROM api_db.user        WHERE accessKey LIKE '%zmbwl%'
-- UNION ALL SELECT 'user.pw', COUNT(*) FROM api_db.user WHERE userPassword='35d8b1ba82d832ae1ce7c400a4641e09'
-- UNION ALL SELECT 'iface',   COUNT(*) FROM api_db.interface_info WHERE description LIKE '%zmbwl%'
-- UNION ALL SELECT 'tool.cfg',COUNT(*) FROM terminal_db.a_llm_tool WHERE config_json LIKE '%zmbwl%'
-- UNION ALL SELECT 'tool.rmk',COUNT(*) FROM terminal_db.a_llm_tool WHERE remark LIKE '%zmbwl%';
