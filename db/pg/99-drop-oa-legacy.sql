-- ============================================================
--  清理：删除已废弃的 OA 场景表（PostgreSQL / 向量库 terminal_kb）
-- ------------------------------------------------------------
--  背景：项目在 M3 阶段决定删除 OA 场景（FAQ + 工具类 + PG 表）。
--        这里给出可选的清理脚本 —— 若线上库中这批表仍存在，
--        执行本文件即可彻底移除。新环境按 01-schema 建库则无需执行。
--
--  ⚠️ 执行前请确认这些表不被任何在用代码引用（当前代码已全部移除引用）。
--  ⚠️ 建议先备份：pg_dump -d terminal_kb -t 'tm_*' -t base_user > oa-backup.sql
-- ============================================================

-- 依赖顺序：先删有外键的子表
DROP TABLE IF EXISTS public.tm_order_track CASCADE;
DROP TABLE IF EXISTS public.tm_work_inst   CASCADE;
DROP TABLE IF EXISTS public.tm_role_job    CASCADE;
DROP TABLE IF EXISTS public.tm_user_role   CASCADE;
DROP TABLE IF EXISTS public.tm_user        CASCADE;
DROP TABLE IF EXISTS public.tm_role        CASCADE;
DROP TABLE IF EXISTS public.tm_area        CASCADE;
DROP TABLE IF EXISTS public.base_user      CASCADE;

-- 序列
DROP SEQUENCE IF EXISTS public.tm_order_track_id_seq CASCADE;
