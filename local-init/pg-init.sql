-- ============================================================
-- 本机部署用 PostgreSQL 初始化（库 terminal_kb）
-- 补齐 4 个查询工具依赖的业务表 + Spring AI 的 chat_memory 表
-- 原库在阿里云 RDS，仓库未带 DDL，这里按工具里的 SQL 反推字段并造少量演示数据
-- 表：base_user / tm_area / tm_role / tm_user_role / tm_role_job / tm_work_inst / tm_order_track / chat_memory
-- vector_store 表由 PgVectorStore 的 initialize-schema=true 自动创建
-- ============================================================

-- ---------- 地市 ----------
CREATE TABLE IF NOT EXISTS tm_area (
    area_code varchar(20)  PRIMARY KEY,
    area_name varchar(100) NOT NULL
);

-- ---------- 用户 ----------
CREATE TABLE IF NOT EXISTS base_user (
    user_id    varchar(40) PRIMARY KEY,
    name       varchar(100) NOT NULL,
    area_code  varchar(20),
    del_flag   smallint DEFAULT 0
);

-- ---------- 角色 ----------
CREATE TABLE IF NOT EXISTS tm_role (
    role_id   varchar(40) PRIMARY KEY,
    role_name varchar(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS tm_user_role (
    user_id varchar(40) NOT NULL,
    role_id varchar(40) NOT NULL
);

-- 角色的环节授权：某角色在某个流程的某个环节有审批权
CREATE TABLE IF NOT EXISTS tm_role_job (
    role_id varchar(40) NOT NULL,
    flow_id varchar(40) NOT NULL,
    job_id  varchar(40) NOT NULL
);

-- ---------- 工单实例 ----------
CREATE TABLE IF NOT EXISTS tm_work_inst (
    order_num   varchar(64) PRIMARY KEY,
    submit_user varchar(40) NOT NULL,
    status      varchar(30),
    create_time timestamp DEFAULT now()
);

-- ---------- 工单流转轨迹 ----------
CREATE TABLE IF NOT EXISTS tm_order_track (
    id          bigserial PRIMARY KEY,
    order_num   varchar(64) NOT NULL,
    job_id      varchar(40) NOT NULL,
    oper_type   varchar(40) NOT NULL,
    oper_time   timestamp   NOT NULL DEFAULT now(),
    oper_remark text
);

-- ---------- Spring AI JDBC 对话记忆 ----------
-- 表名/字段严格取自 spring-ai-model-chat-memory-repository-jdbc-1.0.3 的
-- schema-postgresql.sql 与 PostgresChatMemoryRepositoryDialect（手工构建 repository 不会自动建表）
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content         TEXT        NOT NULL,
    type            VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP   NOT NULL
);
CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");

-- ============================================================
-- 演示数据
-- ============================================================
INSERT INTO tm_area (area_code, area_name) VALUES
 ('JL_CC', '长春'), ('JL_JL', '吉林'), ('JL_SP', '四平')
ON CONFLICT (area_code) DO NOTHING;

INSERT INTO base_user (user_id, name, area_code, del_flag) VALUES
 ('U1001', '张伟',   'JL_CC', 0),   -- 演示登录用户（申请人）
 ('U1002', '李娜',   'JL_JL', 0),
 ('U2001', '王强',   'JL_CC', 0),   -- 长春审批人
 ('U2002', '董博',   'JL_CC', 0),   -- 长春审批人
 ('U3001', '陈晓',   'JL_CC', 0)    -- 长春审批人
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO tm_role (role_id, role_name) VALUES
 ('R_APPLICANT', '业务申请人'),
 ('R_APPROVER',  '环节审批人'),
 ('R_ADMIN',     '系统管理员')
ON CONFLICT (role_id) DO NOTHING;

INSERT INTO tm_user_role (user_id, role_id) VALUES
 ('U1001', 'R_APPLICANT'),
 ('U2001', 'R_APPROVER'),
 ('U2002', 'R_APPROVER'),
 ('U3001', 'R_APPROVER')
ON CONFLICT DO NOTHING;

-- 长春的审批人负责 FLOW_ZC（资源审批流程）的 JOB_REVIEW（审批环节）与 JOB_CHECK（核查环节）
INSERT INTO tm_role_job (role_id, flow_id, job_id) VALUES
 ('R_APPROVER', 'FLOW_ZC', 'JOB_REVIEW'),
 ('R_APPROVER', 'FLOW_ZC', 'JOB_CHECK'),
 ('R_APPROVER', 'FLOW_DF', 'JOB_CHECK')
ON CONFLICT DO NOTHING;

-- 演示工单：U1001 提交的资源审批工单
INSERT INTO tm_work_inst (order_num, submit_user, status, create_time) VALUES
 ('AQLY-ZC-ZCSHXQ-202609-001', 'U1001', '审批中', now() - interval '3 day')
ON CONFLICT (order_num) DO NOTHING;

INSERT INTO tm_order_track (order_num, job_id, oper_type, oper_time, oper_remark)
SELECT * FROM (VALUES
 ('AQLY-ZC-ZCSHXQ-202609-001', 'JOB_SUBMIT', '提交',   now() - interval '3 day', '用户提交资源申请'),
 ('AQLY-ZC-ZCSHXQ-202609-001', 'JOB_REVIEW', '审批通过', now() - interval '2 day', '资料齐全，同意'),
 ('AQLY-ZC-ZCSHXQ-202609-001', 'JOB_CHECK',  '处理中',  now() - interval '1 day', '等待核查人员处理')
) AS v(order_num, job_id, oper_type, oper_time, oper_remark)
WHERE NOT EXISTS (SELECT 1 FROM tm_order_track t WHERE t.order_num = 'AQLY-ZC-ZCSHXQ-202609-001');
