-- ============================================================
-- 80 · 知识库「切片参数」配置（2026-10-07）
-- ============================================================
-- 背景：知识库管理面补齐「切片策略」配置项 —— 知识库只配「嵌入模型 + 切片策略」，
--   其中嵌入模型是平台统一口径（bge-m3，见 aismp-agent 的 OaVectorStoreConfig），
--   管理台只读展示；切片长度按库可配，上传 / 重建索引时透传给
--   agent 的 POST /inner/kb/ingest（chunkSize，<100 用默认 800）。
--
-- 幂等：列已存在时跳过。
-- ============================================================
USE terminal_db;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = 'terminal_db'
               AND TABLE_NAME = 'a_knowledge_base'
               AND COLUMN_NAME = 'chunk_size');
SET @ddl := IF(@col = 0,
  'ALTER TABLE a_knowledge_base ADD COLUMN chunk_size int NOT NULL DEFAULT 800 COMMENT ''切片目标长度（字符），上传/重建索引时生效'' AFTER description',
  'SELECT ''chunk_size 已存在，跳过'' AS skip_note');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 校验
SELECT id, name, chunk_size FROM a_knowledge_base WHERE status <> 'deleted' ORDER BY id;