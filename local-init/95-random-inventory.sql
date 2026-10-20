-- ============================================================================
-- 95：随机库存数据（覆盖式重建）
-- ============================================================================
-- 目的：把仓库里的现有数据整体换成**随机生成**的演示数据。
--   原有 3 万余行是真实导入的库存（串码、采购单号都是真实痕迹），
--   比赛演示不需要、也不适合带着真实业务数据对外展示。
--
-- 覆盖范围
--   api_db.terminal             终端库存（整体重建）
--   api_db.transfer_order{,_item} 演示调拨单（串码会被换掉，旧单会悬空 → 一起清掉）
--   api_db.notice               仅清掉引用上述单据的通知
-- 不动
--   warehouse（仓位定义）、user/a_user（账号）、interface_info（接口登记）——
--   这些是契约不是数据；算法与页面还引用着型号/物料编码，也一并沿用。
--
-- 生成规则（保证演示链路仍然跑得通）
--   1. 六个仓：9B00 地市(level2) / 9BZA 区县(level3) / 9BZA01~04 营业厅(level4)
--   2. 调拨只允许「上级→下级」且要求两边有**共有物料编码**，所以：
--        9B00        覆盖全部型号，量 40~160（地市富余）
--        9BZA        40% 型号富余(80~260)、30% 型号短缺(1~15)、其余不覆盖
--        9BZA01~04   只覆盖 9BZA 富余的那些型号，量 1~8（下级短缺 → 可从 9BZA 调入）
--      于是 9B00→9BZA 与 9BZA→营业厅 两条调拨建议都能成立
--   3. 数量、串码、采购单号、库龄、日期、单价抖动全部重新生成；
--      型号 / 物料编码 / 名称沿用目录（被算法、页面与文档引用）
--   4. 个人信息字段（电话 / 收件人 / 收件人号 / 箱号）一律置 NULL —— 演示数据不需要个人信息
--   5. 伪随机 = CRC32(内容 | 种子)：想要可复现就 SET @seed = 20261020；
--      想换一批就再跑一次（不设 @seed 时用当前时间戳）
--
-- 幂等：可重复执行（先清后建）。执行期间业务查询会看到短暂空窗，请避开演示时段。
-- ============================================================================
SET NAMES utf8mb4;
SET @seed = IFNULL(@seed, UNIX_TIMESTAMP());

-- 0) 清理引用旧串码的演示单据（无外键约束，但避免悬空引用）
CREATE TEMPORARY TABLE tmp_old_orders AS
  SELECT orderNo FROM api_db.transfer_order WHERE orderNo IS NOT NULL;
DELETE FROM api_db.notice WHERE orderNo IN (SELECT orderNo FROM tmp_old_orders);
DELETE FROM api_db.transfer_order_item;
DELETE FROM api_db.transfer_order;
DROP TEMPORARY TABLE tmp_old_orders;

-- 1) 仓位（幂等补齐；名称沿用现有设定，不改其它仓的语义）
INSERT INTO api_db.warehouse
  (warehouseCode, name, level, parentCode, province, city, district, createdAt) VALUES
  ('9B00','东宁市分公司',2,NULL,'省公司','东宁市分公司',NULL,NOW()),
  ('9BZA','东宁市新区分公司',3,'9B00','省公司','东宁市分公司','东宁市新区分公司',NOW()),
  ('9BZA01','新区·中心营业厅',4,'9BZA','省公司','东宁市分公司','东宁市新区分公司',NOW()),
  ('9BZA02','新区·北环营业厅',4,'9BZA','省公司','东宁市分公司','东宁市新区分公司',NOW()),
  ('9BZA03','新区·滨河营业厅',4,'9BZA','省公司','东宁市分公司','东宁市新区分公司',NOW()),
  ('9BZA04','新区·科技园营业厅',4,'9BZA','省公司','东宁市分公司','东宁市新区分公司',NOW())
ON DUPLICATE KEY UPDATE
  name = VALUES(name), level = VALUES(level), parentCode = VALUES(parentCode),
  province = VALUES(province), city = VALUES(city), district = VALUES(district);

-- 2) 清空终端库存
DELETE FROM api_db.terminal;

-- 3) 型号目录（沿用真实型号与物料编码；只取目录，不含个人/单据信息）
CREATE TEMPORARY TABLE tmp_catalog (
  model VARCHAR(128), materialCode VARCHAR(64), materialName VARCHAR(256),
  bigType VARCHAR(64), smallType VARCHAR(64), vendor VARCHAR(64), unitPrice DECIMAL(12,4)
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;  -- 与 api_db 表保持同一 collation，
-- 否则 JOIN 时报 "Illegal mix of collations"（临时表默认 collation 跟库不一致）
INSERT INTO tmp_catalog (model, materialCode, materialName, bigType, smallType, vendor, unitPrice) VALUES
  ('010W','4200601138','全4K智能机顶盒_诺基亚贝尔-全4K智能机顶盒-基础型-S-010W-AV2S','机顶盒','IPTV','诺基亚',132.5000),
  ('1900','7471500657','【Wi-Fi5|AC1900】FAC1900R','路由器','WiFi5','迅捷',0.0000),
  ('5101','4200601139','全4K智能机顶盒_新大陆-全4K智能机顶盒-基础型-NL-5101(RK3228','机顶盒','IPTV','新大陆',114.6000),
  ('B650','4299909560','华为_企业网关-B650-8E8P（含华为接入FTTx及铜线软件V2.00）','FTTO','FTTO从','华为',0.0000),
  ('B671','7450301908','【FTTO从】华为B671-L1','FTTO','FTTO从','华为',239.0000),
  ('B671','7450301910','【FTTO从】华为B671','FTTO','FTTO从','华为',147.2900),
  ('B671','7450302096','【FTTO从】华为B50-B671-S2','FTTO','FTTO从','华为',480.0000),
  ('B860AV','4200601133','中兴-全4K智能机顶盒-基础型-B860AV2.1U','机顶盒','IPTV','中兴',0.0000),
  ('B860AV','7402300889','中兴IPTV终端（型号ZXV10 B860A）','机顶盒','IPTV','中兴',0.0000),
  ('B860AV','7402301032','中兴ZXV10B860AV1.14K机顶盒','机顶盒','IPTV','中兴',0.0000),
  ('B860AV','7402301032','中兴ZXV10B860AV1.14K机顶盒','机顶盒','IPTV','中兴',185.0000),
  ('B860AV','7450200479','智能机顶盒_中兴-基础型1GB+8GB-ZXV10B860AV3.2-U（组合）','机顶盒','IPTV','中兴',79.5600),
  ('B860AV','7450200479','智能机顶盒_中兴-基础型1GB+8GB-ZXV10B860AV3.2-U（组合）','机顶盒','IPTV','中兴',89.6700),
  ('B860AV','7450200530','智能机顶盒_中兴-基础型1GB+8GB-ZXV10B860AV5.2-U（组合）','机顶盒','IPTV','中兴',89.6700),
  ('B860AV','9000000020','中兴IPTV智能网机顶盒（B860AV)','机顶盒','IPTV','中兴',0.0000),
  ('B860AV','9000000020','中兴IPTV智能网机顶盒（B860AV)','机顶盒','IPTV','中兴',180.0000),
  ('B860AV','9000000020','中兴IPTV智能网机顶盒（B860AV)','机顶盒','IPTV','中兴',185.0000),
  ('B866','7430103135','智能家居_荣耀路由CD16_荣耀_无线网络_白_无礼包111','FTTR','FTTR从','烽火',0.0000),
  ('B866','7450301902','【FTTO主】华为B50基础款-B866-L1-XGPON/10GEPON非对称','FTTR','FTTR从','烽火',368.0000),
  ('B866','7450302097','【FTTO主】华为B50尊享款非对称-B866-S2-5E4P5W1','FTTR','FTTR从','烽火',511.0000),
  ('D740','7402402297','无频GPON智能网关终端华盛代理-吉林-创维-GPON-无频-SK-D740','光猫','1G PON','创维',125.5000),
  ('D740','7402402297','无频GPON智能网关终端华盛代理-吉林-创维-GPON-无频-SK-D740','光猫','1G PON','创维',125.8000),
  ('D740','7450301767','智能网关终端_创维_1GPON无WiFi(带语音)_SK-D740(GPON)','光猫','1G PON','创维',65.2800),
  ('DT721','7402200680','Skyworth-GPON2+1+wifi单频-DT721-csf','光猫','1G PON','创维',121.6000),
  ('DT721','7402200680','Skyworth-GPON2+1+wifi单频-DT721-csf','光猫','1G PON','创维',123.0000),
  ('DT741','7402200683','Skyworth-GPON4+1+wifi单频-DT741-csf','光猫','1G PON','创维',103.0000),
  ('DT741','7402200683','Skyworth-GPON4+1+wifi单频-DT741-csf','光猫','1G PON','创维',103.7000),
  ('DT741','7402200683','Skyworth-GPON4+1+wifi单频-DT741-csf','光猫','1G PON','创维',112.5000),
  ('DT741','7402200683','Skyworth-GPON4+1+wifi单频-DT741-csf','光猫','1G PON','创维',122.5000),
  ('DT741','7402200683','Skyworth-GPON4+1+wifi单频-DT741-csf','光猫','1G PON','创维',123.8900),
  ('DT741','7402200683','Skyworth-GPON4+1+wifi单频-DT741-csf','光猫','1G PON','创维',125.0000),
  ('DT741','7402200683','Skyworth-GPON4+1+wifi单频-DT741-csf','光猫','1G PON','创维',125.5000),
  ('DT741','7402200683','Skyworth-GPON4+1+wifi单频-DT741-csf','光猫','1G PON','创维',125.8000),
  ('DT741','7402201972','华盛代理-吉林-创维-GPON-4+1+WIFI-单频-DT741-csf','光猫','1G PON','创维',114.3000),
  ('DT741','7402201972','华盛代理-吉林-创维-GPON-4+1+WIFI-单频-DT741-csf','光猫','1G PON','创维',122.5000),
  ('DT741','7402201972','华盛代理-吉林-创维-GPON-4+1+WIFI-单频-DT741-csf','光猫','1G PON','创维',125.0000),
  ('E2638','7471500671','【全新上架】中兴ZXHNE2638白（Wi-Fi6）|MESH组网|全屋WiFi','路由器','WiFi6','中兴',119.4700),
  ('E900','4200601120','全4K智能机顶盒_创维-全4K智能机顶盒-基础型-E900V21D','机顶盒','IPTV','创维',150.8700),
  ('EC6108','4200600825','华为EC6108V9机顶盒（含软件）','机顶盒','IPTV','华为',0.0000),
  ('EC6108','4200600825','华为EC6108V9机顶盒（含软件）','机顶盒','IPTV','华为',180.0000),
  ('EC6108','4200601421','华为超高清4K智能机顶盒','机顶盒','IPTV','华为',0.0000),
  ('EC6108','4200601421','华为超高清4K智能机顶盒','机顶盒','IPTV','华为',230.0000),
  ('EC6108','7402300649','华为高清IPTV智能网机顶盒(EC6108V9I)','机顶盒','IPTV','华为',0.0000),
  ('EC6108','7402300649','华为高清IPTV智能网机顶盒(EC6108V9I)','机顶盒','IPTV','华为',159.0000),
  ('EC6108','7402300649','华为高清IPTV智能网机顶盒(EC6108V9I)','机顶盒','IPTV','华为',230.0000),
  ('EC6108','7402300730','机顶盒-华为-EC6108V9','机顶盒','IPTV','华为',0.0000),
  ('EC6108','7402300730','机顶盒-华为-EC6108V9','机顶盒','IPTV','华为',159.0000),
  ('EC6108','9000000014','华为机顶盒_EC6106V6','机顶盒','IPTV','华为',0.0000),
  ('EC6108','9000000023','华为IPTV机顶盒_220_EC6108V9E(停采）','机顶盒','IPTV','华为',0.0000),
  ('EC6108','9000000023','华为IPTV机顶盒_220_EC6108V9E(停采）','机顶盒','IPTV','华为',159.0000),
  ('EC6109','4200600759','IPTV智能机顶盒EC6109-U','机顶盒','IPTV','华为',159.0000),
  ('EC6109','7402301556','华为高清IPTV智能机顶盒-EC6109-U【其中，包含“华为IPTV方案','机顶盒','IPTV','华为',159.0000),
  ('EC6109','7402301696','华为高清IPTV智能机顶盒-EC6109-U包含“华为融合视频方案','机顶盒','IPTV','华为',159.0000),
  ('F657','7402402299','无频GPON智能网关终端ZXHNF657GV9','光猫','1G PON','中兴',117.0000),
  ('F657','7402402299','无频GPON智能网关终端ZXHNF657GV9','光猫','1G PON','中兴',126.5000),
  ('F657','7450301775','智能网关终端中兴1GPON无WiFi带语音ZXHNF657GV9(GP','光猫','1G PON','中兴',55.0800),
  ('F657','7450301775','智能网关终端中兴1GPON无WiFi带语音ZXHNF657GV9(GP','光猫','1G PON','中兴',65.2800),
  ('F667','7402200674','中兴-GPON-4+1+WIFI单频-ZXHNF677','光猫','1G PON','中兴',137.0000),
  ('F667','7402200674','中兴-GPON-4+1+WIFI单频-ZXHNF677','光猫','1G PON','中兴',137.1795),
  ('F667','7402200674','中兴-GPON-4+1+WIFI单频-ZXHNF677','光猫','1G PON','中兴',138.0000),
  ('F677','7402200909','智能网关终端_中兴-GPON-4+1+WIFI-单频-ZXHN-F677V2','光猫','1G PON','中兴',111.5000),
  ('F677','7402200909','智能网关终端_中兴-GPON-4+1+WIFI-单频-ZXHN-F677V2','光猫','1G PON','中兴',137.0000),
  ('F677','7402201470','智能网关终端_中兴-GPON-4+1+WIFI-单频-ZXHN-F677V9','光猫','1G PON','中兴',107.0000),
  ('F677','7402201470','智能网关终端_中兴-GPON-4+1+WIFI-单频-ZXHN-F677V9','光猫','1G PON','中兴',110.0000),
  ('F677','7402201470','智能网关终端_中兴-GPON-4+1+WIFI-单频-ZXHN-F677V9','光猫','1G PON','中兴',119.5000),
  ('F7610','7450301777','智能网关终端_中兴_10GPONWiFi6(带语音)_ZXHNF7610U(XG','光猫','XG PON','中兴',0.0000),
  ('F7615','7450302217','智能网关终端_中兴_10G_PON_WiFi6(带语音、带2.5G口)_ZXHN','光猫','XG PON','中兴',147.2900),
  ('G100L','7471502305','【FTTO单品-主设备】中兴ZXENG100L（XGPON）白色','FTTO','FTTO主','中兴',389.0000),
  ('G1650','7471502303','【FTTO单品-从设备】中兴ZXENG1650白色','FTTO','FTTO从','中兴',300.0000),
  ('G60U','7471301528','【云台|400万】华为G60U（AI）','摄像头','室内机','华为',0.0000),
  ('G7','7471301526','【云台|400万】华为宝宝看护器G7 蓝色（AI）','摄像头','室内机','华为',0.0000),
  ('G7615','7471502015','【FTTR单品-主设备】中兴FTTR主网关ZXHNG7615V2(XGPON)白','FTTR','FTTR主','中兴',321.2300),
  ('HG3142','7450301829','【FTTR单品-主网关】烽火FTTR主网关HG3142F(ONU)(XG-PON','FTTR','FTTR主','烽火',276.0000),
  ('HG521','4200601285','智能融合终端_HG521G','光猫','融合网关','友华',197.3400),
  ('HG521','4200601285','智能融合终端_HG521G','光猫','融合网关','友华',205.3100),
  ('HG521','4200601584','IPTV三合一机顶盒_HG521G','光猫','融合网关','友华',150.4000),
  ('HG6022','7471502560','【FTTR单品-从设备】烽火FTTR子网关HG6022HT白','FTTR','FTTR从','烽火',0.0000),
  ('HG6371','7450301831','【FTTR单品-从网关】烽火FTTR从网关HG6371F白','FTTR','FTTR从','烽火',110.0000),
  ('HG6543','7402200906','智能网关终端_烽火-GPON-4+1+WIFI单频-HG6543C','光猫','1G PON','烽火',111.4000),
  ('HG6543','7402200906','智能网关终端_烽火-GPON-4+1+WIFI单频-HG6543C','光猫','1G PON','烽火',137.0000),
  ('HG8347','7402401302','华为PONFTTH终端HG8347RGPON4+1+WIFI','光猫','1G PON','华为',0.0000),
  ('HN8346','7450301737','智能网关终端华为10GPONWiFi6带语音HN8346X6-C(XG','光猫','XG PON','华为',0.0000),
  ('HN8346','7450302313','运营商心选【FTTR从】华为 FTTR从- K252-20 天鹅白','光猫','10G EPON','友华',0.0000),
  ('HN8346','7471501952','【FTTR单品-从设备】中兴FTTR从网关ZXHNG1612白','光猫','10G EPON','华为',144.5500),
  ('HS8346','7402200665','华为-GPON-4+1+WIFI-单频-HS8346R','光猫','1G PON','华为',0.0000),
  ('HS8346','7402200665','华为-GPON-4+1+WIFI-单频-HS8346R','光猫','1G PON','华为',112.0000),
  ('HS8346','7402200665','华为-GPON-4+1+WIFI-单频-HS8346R','光猫','1G PON','华为',136.0000),
  ('HS8346','7402200665','华为-GPON-4+1+WIFI-单频-HS8346R','光猫','1G PON','华为',143.9655),
  ('HS8346','7402201305','华为-EPON-4+1+WIFI-单频-HS8346R5（含华为接入ONT软件V','光猫','1G EPON','华为',0.0000),
  ('HS8346','7402201306','华为-GPON-4+1+WIFI-单频-HS8346R5（含华为接入ONT软件V','光猫','1G PON','华为',0.0000),
  ('HS8346','7402201306','华为-GPON-4+1+WIFI-单频-HS8346R5（含华为接入ONT软件V','光猫','1G PON','华为',99.0000),
  ('HS8346','7402201306','华为-GPON-4+1+WIFI-单频-HS8346R5（含华为接入ONT软件V','光猫','1G PON','华为',112.0000),
  ('HS8346','7402201306','华为-GPON-4+1+WIFI-单频-HS8346R5（含华为接入ONT软件V','光猫','1G PON','华为',143.9655),
  ('HS8346','7450301731','智能网关终端_华为_1G_PON_WiFi5(带语音)_HS8346V5-C(GPON)_华为_有线网络_白_无礼包','光猫','1G PON','华为',0.0000),
  ('HS8346','9010000020','华为GPONHG8346R（停产）','光猫','1G PON','华为',0.0000),
  ('IP108H','4200601124','全4K智能机顶盒_海信-全4K智能机顶盒-基础型-IP108H','机顶盒','IPTV','海信',81.7700),
  ('IP108H','4200601124','全4K智能机顶盒_海信-全4K智能机顶盒-基础型-IP108H','机顶盒','IPTV','海信',105.4800),
  ('IP108H','4200601215','全4K智能机顶盒_海信_全4K智能机顶盒-标准型-IP108H','机顶盒','IPTV','海信',127.0000),
  ('IP108H','4200601215','全4K智能机顶盒_海信_全4K智能机顶盒-标准型-IP108H','机顶盒','IPTV','海信',128.8300),
  ('IP108H','7450200350','全4K机顶盒_华盛-吉林-海信-基础型-IP108H_','机顶盒','IPTV','海信',81.7700),
  ('IP108H','7450200350','全4K机顶盒_华盛-吉林-海信-基础型-IP108H_','机顶盒','IPTV','海信',105.4800),
  ('IP108H','7450200350','全4K机顶盒_华盛-吉林-海信-基础型-IP108H_','机顶盒','IPTV','海信',128.5000),
  ('IP501H','7450200475','智能机顶盒_海信-基础型1GB+8GB-IP501H（组合）','机顶盒','IPTV','海信',76.0900),
  ('IP501H','7450200475','智能机顶盒_海信-基础型1GB+8GB-IP501H（组合）','机顶盒','IPTV','海信',85.5700),
  ('IP501H','7450200475','智能机顶盒_海信-基础型1GB+8GB-IP501H（组合）','机顶盒','IPTV','海信',181.0000),
  ('IP502H','7450200478','智能机顶盒_海信-标准型2GB+8GB-IP502H（组合）','机顶盒','IPTV','海信',96.6400),
  ('IP810N','7450200516','智能机顶盒_海信-基础型1GB+8GB-IP810N（组合）','机顶盒','IPTV','海信',85.5700),
  ('IP811N','7450200519','智能机顶盒_海信-创新型2GB+8GB-IP811N（组合）','机顶盒','宽视界','海信',96.6400),
  ('K155','7450301877','FTTR单品从华为FTTR青春款从网关K155-11白','FTTR','FTTR从','华为',153.0000),
  ('LTZX-IPTV','7402301456','IPTV机顶盒-运营商在线','机顶盒','IPTV','运营商在线',181.0000),
  ('LYR30','7471300432','【门铃|300万】岭雁LYR30','摄像头','室外机','岭雁科技',0.0000),
  ('P62','7471300559','【枪机|400万】视洞P62','摄像头','室外机','视洞',0.0000),
  ('PT1570','7450301745','智能网关友华10GPONWiFi6(带语音)PT1570-R10(XG-PON)','光猫','XG PON','友华',140.7300),
  ('PT1570','7450301745','智能网关友华10GPONWiFi6(带语音)PT1570-R10(XG-PON)','光猫','XG PON','友华',147.8100),
  ('PT1570','7450302227','智能网关终端_友华_10G_PON_WiFi6(带语音)_PT1570-R10(XG-PON)（赠机）_友华通信_有线网络_白_无礼包','光猫','XG PON','友华',0.0100),
  ('PT927','7402202181','智能网关终端_友华-GPON-4+1+WIFI5-单频-PT927G（C系统）','光猫','1G PON','友华',91.0000),
  ('PT928','7450301743','智能网关终端_友华_1GPON无WiFi(带语音)_PT928G(GPON)','光猫','1G PON','友华',55.7500),
  ('PT928','7450301743','智能网关终端_友华_1GPON无WiFi(带语音)_PT928G(GPON)','光猫','1G PON','友华',56.5700),
  ('PT928G','4201600978','GPON（4GE+1POTS）无频智能网关设备_PT928G','光猫','1G PON','友华',75.2000),
  ('PT928G','4201600978','GPON（4GE+1POTS）无频智能网关设备_PT928G','光猫','1G PON','友华',89.4000),
  ('PT928G','4201600978','GPON（4GE+1POTS）无频智能网关设备_PT928G','光猫','1G PON','友华',93.0000),
  ('PT928G','7402402300','无频GPON智能网关终端PT928G','光猫','1G PON','友华',56.5700),
  ('PT928G','7402402300','无频GPON智能网关终端PT928G','光猫','1G PON','友华',125.6600),
  ('PT9500','7450302259','智能网关终端_友华_1G PON 无WiFi(带语音)_PT9500-H10(GPON)','光猫','1G PON','友华',55.7500),
  ('PT952','7402200685','友华-GPON-4+1+WIFI-PT952G','光猫','1G PON','友华',104.4000),
  ('PT9570','7450301741','智能网关终端_友华_1G_PON_WiFi6(带语音)_PT9570-R10(GPON)_友华通信_有线网络_白_无礼包','光猫','1G PON','友华',124.7800),
  ('QJ82','7470708532','【枪机|400万】岭雁LY-QJ82（AI）','摄像头','室外机','岭雁',0.0000),
  ('US200','7402202262','华盛代理-吉林-兆能-GPON-4+1+WIFI-单频-US200','光猫','1G PON','兆能',115.0000),
  ('V175','7450301883','华为FTTR青春款主网关V175（XGPON）白色','FTTR','FTTR主','华为',339.0000),
  ('Y01','7471301518','【云台|400万】易莱孚Y01-U 室内云台机（AI）','摄像头','室内机','易莱孚',81.4200);

-- 4) 仓位参数：coverMode 决定覆盖与数量形态
--    ALL  全部型号都有量
--    MIX  同一型号在不同仓形态不同（富余 / 短缺 / 不覆盖）
--    FEW  只覆盖 MIX 里"富余"的那部分型号，量很小
CREATE TEMPORARY TABLE tmp_wh (
  warehouseCode VARCHAR(32), coverMode VARCHAR(8), qtyMin INT, qtyMax INT
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO tmp_wh (warehouseCode, coverMode, qtyMin, qtyMax) VALUES
  ('9B00','ALL',40,160),
  ('9BZA','MIX',80,260),
  ('9BZA01','FEW',1,8),
  ('9BZA02','FEW',1,8),
  ('9BZA03','FEW',1,8),
  ('9BZA04','FEW',1,8);

-- 4.5) 序号表 1..300（MySQL 不允许 WITH 出现在 INSERT 之前，改用临时表）
CREATE TEMPORARY TABLE tmp_seq (n INT);
INSERT INTO tmp_seq (n)
WITH RECURSIVE s2 (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM s2 WHERE n < 300)
SELECT n FROM s2;

-- 5) 生成库存行
--    伪随机：p 决定型号在"富余/短缺/不覆盖"里的位置（与仓无关），
--            h 决定具体数量与日期（与仓有关）—— 同一型号在不同仓的形态由 p 保持一致，
--            这正是"上级富余、下级短缺"能成立的原因。
INSERT INTO api_db.terminal (
  serialNo, bigType, smallType, vendor, model, materialCode, materialName,
  purchaseNo, terminalStatus, inboundType, businessType, purchaseType, statusChangedAt,
  lv1Warehouse, lv2Warehouse, lv3Warehouse, lv4Warehouse, ownerWarehouse, warehouseCode,
  targetWarehouse, unitPrice, customerPhone, receiver, receiverNo, boxNo,
  firstInboundAt, agingDays, warrantyStart, warrantyEnd, operator, operatorNo,
  secondInboundAt, lostCompensation, createdAt, updatedAt
)
SELECT
  CONCAT(t.warehouseCode, LPAD(t.rn, 8, '0'))                       AS serialNo,
  t.bigType, t.smallType, t.vendor, t.model, t.materialCode, t.materialName,
  CONCAT('PO-', t.warehouseCode, '-',
         DATE_FORMAT(t.inboundAt, '%Y%m%d'), LPAD(t.rn, 6, '0'))      AS purchaseNo,
  IF(t.rn % 33 = 0, '在途', '可用')                                  AS terminalStatus,
  IF(MOD(t.hv, 5) = 0, '移/换机', '装机')                            AS inboundType,
  IF(MOD(t.hv, 7) = 0, NULL, '采购入库')                              AS businessType,
  IF(t.agingDays > 365, '旧机入库', '0元新机入库')                   AS purchaseType,
  NOW()                                                             AS statusChangedAt,
  '省公司', t.lv2Name, t.lv3Name, NULL, t.ownerName, t.warehouseCode,
  NULL,
  ROUND(t.unitPrice * (0.85 + MOD(t.hv, 30) / 100), 4)                AS unitPrice,
  NULL, NULL, NULL, NULL,                                           -- 电话/收件人/收件人号/箱号：演示数据不落个人信息
  t.inboundAt, t.agingDays, t.inboundAt,
  DATE_ADD(t.inboundAt, INTERVAL 3 YEAR),                           -- 保修 3 年
  NULL, NULL, NULL, 0.0000,
  t.inboundAt, NOW()
FROM (
  SELECT d.*, s.n,
         ROW_NUMBER() OVER (PARTITION BY d.warehouseCode
                            ORDER BY d.materialCode, d.vendor, s.n) AS rn
  FROM (
    -- MySQL 不允许在同一条 SELECT 里引用刚定义的别名，所以分三层：
    --   z 层算伪随机量 → d 层按仓位形态换算成数量/库龄/入库日期
    SELECT z.*,
      CASE z.coverMode
        WHEN 'ALL' THEN z.qtyMin + MOD(z.hv, z.qtyMax - z.qtyMin + 1)
        WHEN 'MIX' THEN
          CASE WHEN z.prof < 4 THEN 80  + MOD(z.hv, 181)
               WHEN z.prof < 7 THEN 1   + MOD(z.hv, 15)
               ELSE 0 END
        WHEN 'FEW' THEN
          CASE WHEN z.prof < 4 THEN 1 + MOD(z.hv, 8) ELSE 0 END
      END                                                            AS qty,
      CASE WHEN MOD(z.hv, 10) < 7 THEN MOD(z.hv, 120)
           ELSE 120 + MOD(z.hv, 900) END                              AS agingDays,
      DATE_SUB(NOW(), INTERVAL (
        CASE WHEN MOD(z.hv, 10) < 7 THEN MOD(z.hv, 120)
             ELSE 120 + MOD(z.hv, 900) END
      ) DAY)                                                         AS inboundAt
    FROM (
      SELECT
        c.*, w.warehouseCode, w.coverMode, w.qtyMin, w.qtyMax, wh.name AS ownerName,
        (SELECT name FROM api_db.warehouse WHERE warehouseCode = '9BZA') AS lv3Name,
        '东宁市分公司'                                                 AS lv2Name,
        MOD(CRC32(c.materialCode), 10)                                  AS prof,
        MOD(CRC32(CONCAT(c.materialCode, '|', w.warehouseCode, '|', @seed)), 100000) AS hv
      FROM tmp_catalog c
      CROSS JOIN tmp_wh w
      JOIN api_db.warehouse wh ON wh.warehouseCode = w.warehouseCode
    ) z
  ) d
  JOIN tmp_seq s ON s.n <= d.qty
) t;

-- 6) 汇总（看一眼生成结果是否合理）
SELECT warehouseCode AS 仓位, COUNT(*) AS 终端数, COUNT(DISTINCT materialCode) AS 型号数
FROM api_db.terminal GROUP BY warehouseCode ORDER BY warehouseCode;

DROP TEMPORARY TABLE tmp_catalog;
DROP TEMPORARY TABLE tmp_wh;
DROP TEMPORARY TABLE tmp_seq;
