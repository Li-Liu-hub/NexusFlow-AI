-- ============================================================
-- 本机部署用种子数据：默认用户
-- ============================================================
USE api_db;

-- 默认用户（id=1，admin）。
-- 口令散列方案：MD5(SALT + 明文口令)，SALT 常量定义在 UserServiceImpl。
-- 注意：网关不校验口令，口令只用于前端登录；accessKey / secretKey 才是网关签名鉴权凭据。
-- 演示环境口令统一 123456，生产部署请务必更换。
INSERT IGNORE INTO `user` (id, userName, userAccount, userAvatar, gender, userRole, userPassword, accessKey, secretKey, isDelete)
VALUES (1, '管理员', 'admin', NULL, 0, 'admin', '436bf1095bd9baab1c387e7d8f072794',
        'aismp-console', '7c1f9a3e5d0b2846af31c7e9b0d45268', 0);

-- 普通用户（id=2）
INSERT IGNORE INTO `user` (id, userName, userAccount, userAvatar, gender, userRole, userPassword, accessKey, secretKey, isDelete)
VALUES (2, '普通用户', 'user', NULL, 1, 'user', '436bf1095bd9baab1c387e7d8f072794',
        'user', '7c1f9a3e5d0b2846af31c7e9b0d45268', 0);

-- 口令盐值已更换，存量行必须同步轮换哈希，否则无法登录
UPDATE `user` SET userPassword = '436bf1095bd9baab1c387e7d8f072794'
WHERE userAccount IN ('admin', 'user');
