-- 接口信息表
create table if not exists api_db.`interface_info`
(
    `id`           bigint       not null auto_increment comment '主键' primary key,
    `name`         varchar(256) not null comment '名称',
    `description`  varchar(256) null comment '描述',
    `url`          varchar(512) not null comment '接口地址',
    `requestParams`     text      not null comment '请求参数',
    `requestHeader` text null comment '请求头',
    `responseHeader` text null comment '响应头',
    `status`       int          default 0 not null comment '接口状态 (0-关闭，1-开启)',
    `method`       varchar(256) not null comment '请求类型',
    `userId`       bigint       not null comment '创建人',
    `createTime`   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    `updateTime`   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    `isDelete`     tinyint      default 0 not null comment '是否删除 (0-未删，1-已删)'
    ) comment '接口信息';


-- 用户调用接口
create table if not exists aismp.`user_interface_info`
(
    `id` bigint not null auto_increment comment '主键' primary key,
    `userId` bigint not null comment '调用用户id',
    `interfaceInfoId` bigint not null comment '接口id',
    `totalNum` int default 0 not null comment '总调用次数',
    `leftNum` int default 0 not null comment '剩余调用次数',
    `status` int default 0 not null comment '0-正常，1-禁用',
    `createTime` datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    `updateTime` datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    `isDelete` tinyint default 0 not null comment '是否删除(0-未删, 1-已删)'
) comment '用户调用接口关系';



-- 插入20条接口信息测试数据
INSERT INTO api_db.`interface_info` (
    `name`, `description`, `url`, `requestHeader`, `responseHeader`,
    `status`, `method`, `userId`, `createTime`, `updateTime`, `isDelete`
) VALUES
-- 第1条
('用户登录接口', '用户账号密码登录获取token', 'http://api.aismp.com/user/login',
 '{"Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'POST', 1, NOW(), NOW(), 0),
-- 第2条
('用户信息查询', '根据用户ID查询用户基本信息', 'http://api.aismp.com/user/info',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'GET', 1, NOW(), NOW(), 0),
-- 第3条
('商品列表查询', '分页查询商品列表', 'http://api.aismp.com/goods/list',
 '{"Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'GET', 2, NOW(), NOW(), 0),
-- 第4条
('商品详情查询', '根据商品ID查询商品详情', 'http://api.aismp.com/goods/detail',
 '{"Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'GET', 2, NOW(), NOW(), 0),
-- 第5条
('订单创建', '创建用户订单', 'http://api.aismp.com/order/create',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'POST', 3, NOW(), NOW(), 0),
-- 第6条
('订单状态查询', '根据订单ID查询订单状态', 'http://api.aismp.com/order/status',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'GET', 3, NOW(), NOW(), 0),
-- 第7条
('支付回调接口', '第三方支付平台回调通知', 'http://api.aismp.com/pay/callback',
 '{"Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'POST', 4, NOW(), NOW(), 0),
-- 第8条
('短信发送接口', '发送验证码短信', 'http://api.aismp.com/sms/send',
 '{"Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 0, 'POST', 4, NOW(), NOW(), 0),
-- 第9条
('文件上传接口', '上传图片文件', 'http://api.aismp.com/file/upload',
 '{"Content-Type":"multipart/form-data"}', '{"Content-Type":"application/json"}',
 1, 'POST', 5, NOW(), NOW(), 0),
-- 第10条
('文件下载接口', '下载文件', 'http://api.aismp.com/file/download',
 '{"Authorization":"Bearer {token}"}', '{"Content-Type":"application/octet-stream"}',
 1, 'GET', 5, NOW(), NOW(), 0),
-- 第11条
('评论提交接口', '提交商品评论', 'http://api.aismp.com/comment/add',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'POST', 6, NOW(), NOW(), 0),
-- 第12条
('评论列表查询', '分页查询商品评论', 'http://api.aismp.com/comment/list',
 '{"Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'GET', 6, NOW(), NOW(), 0),
-- 第13条
('购物车添加', '添加商品到购物车', 'http://api.aismp.com/cart/add',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 0, 'POST', 7, NOW(), NOW(), 0),
-- 第14条
('购物车删除', '删除购物车商品', 'http://api.aismp.com/cart/delete',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'DELETE', 7, NOW(), NOW(), 0),
-- 第15条
('地址添加接口', '添加用户收货地址', 'http://api.aismp.com/address/add',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'POST', 8, NOW(), NOW(), 0),
-- 第16条
('地址列表查询', '查询用户收货地址列表', 'http://api.aismp.com/address/list',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'GET', 8, NOW(), NOW(), 0),
-- 第17条
('积分查询接口', '查询用户积分余额', 'http://api.aismp.com/points/balance',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'GET', 9, NOW(), NOW(), 0),
-- 第18条
('积分兑换接口', '积分兑换商品', 'http://api.aismp.com/points/exchange',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 0, 'POST', 9, NOW(), NOW(), 0),
-- 第19条
('优惠券领取接口', '领取指定优惠券', 'http://api.aismp.com/coupon/receive',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'POST', 10, NOW(), NOW(), 0),
-- 第20条
('优惠券列表查询', '查询用户已领取优惠券', 'http://api.aismp.com/coupon/list',
 '{"Authorization":"Bearer {token}","Content-Type":"application/json"}', '{"Content-Type":"application/json"}',
 1, 'GET', 10, NOW(), NOW(), 0);