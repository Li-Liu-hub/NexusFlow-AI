-- ============================================================
-- 项目合并 SQL：Agent 接口接入 API 开放平台
-- 执行前请确认当前数据库中的 interface_info 表数据
-- ============================================================

-- 1. 更新现有接口记录的 url 为相对路径（去掉 host 前缀）
-- 注意：根据实际数据调整，以下是示例
UPDATE interface_info SET url = REPLACE(url, 'http://localhost:8123', '') WHERE url LIKE 'http://localhost:8123%';

-- 2. 插入 Agent 接口记录（相对路径，和网关路由匹配）
INSERT INTO interface_info (name, description, url, requestParams, requestHeader, responseHeader, status, method, userId, createTime, updateTime, isDelete)
VALUES
(
    'OA 智能客服（同步）',
    '基于 RAG 知识库 + 工具调用的 OA 智能客服，ChatClient 模式，同步返回完整响应',
    '/oa/chat/sync',
    '{"message":"用户消息","conversationId":"会话ID"}',
    '{"accessKey":"用户AK","nonce":"随机数","timestamp":"时间戳","body":"请求体","sign":"签名","X-User-Id":"工号","X-Area-Code":"地市编码"}',
    '{"Content-Type":"application/json"}',
    1,
    'POST',
    1,
    NOW(),
    NOW(),
    0
),
(
    'OA 智能体（ReAct）',
    '基于 ReAct 四层架构的自主决策智能体，多步骤工具调用 + 多轮记忆',
    '/oa/agent/chat',
    '{"message":"用户消息","conversationId":"会话ID"}',
    '{"accessKey":"用户AK","nonce":"随机数","timestamp":"时间戳","body":"请求体","sign":"签名","X-User-Id":"工号","X-Area-Code":"地市编码"}',
    '{"Content-Type":"application/json"}',
    1,
    'POST',
    1,
    NOW(),
    NOW(),
    0
);

-- 3. 给默认用户（id=1）开通 Agent 接口调用权限
-- 注意：interfaceInfoId 需要替换为上面 INSERT 实际生成的自增 ID
INSERT INTO user_interface_info (userId, interfaceInfoId, totalNum, leftNum, status, createTime, updateTime, isDelete)
SELECT 1, id, 1000, 1000, 0, NOW(), NOW(), 0
FROM interface_info
WHERE url IN ('/oa/chat/sync', '/oa/agent/chat') AND isDelete = 0;
