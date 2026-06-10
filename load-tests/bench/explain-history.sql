-- History query as issued by findByUserId(userId, Pageable sort=createdAt DESC, size=20).
-- Run after seed-analyses.sql.

\echo === WITH index idx_analysis_user_created (expect Index Only Scan) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, file_id, type, status, verdict, confidence, created_at, updated_at
FROM analysis WHERE user_id = 'bench-user'
ORDER BY created_at DESC
LIMIT 20;

\echo === WITHOUT index (dropped inside a tx, rolled back so the schema is restored) ===
BEGIN;
DROP INDEX idx_analysis_user_created;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, file_id, type, status, verdict, confidence, created_at, updated_at
FROM analysis WHERE user_id = 'bench-user'
ORDER BY created_at DESC
LIMIT 20;
ROLLBACK;
