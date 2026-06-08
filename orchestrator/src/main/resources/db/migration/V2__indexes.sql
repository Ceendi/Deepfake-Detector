-- Align timestamp columns to TIMESTAMPTZ, consistent with file-service and the JPA
-- Instant mapping (store true instants, not wall-clock). V1 used bare TIMESTAMP; Hibernate
-- wrote those values as UTC, so reinterpret them as UTC during the conversion. Done before
-- the index rebuild below, since created_at is an index key.
ALTER TABLE analysis
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

-- Covering index for the per-user history list (ORDER BY created_at DESC).
-- INCLUDE columns == the lightweight AnalysisSummary projection (commit 2), so the
-- paginated list() can go index-only on all-visible pages (no heap fetch). file_key
-- (VARCHAR 500) and error_message (TEXT) are deliberately excluded — they would bloat
-- the index without any read benefit for the list view.
DROP INDEX IF EXISTS idx_analysis_user_created;          -- from V1, replaced with a covering version
CREATE INDEX idx_analysis_user_created
    ON analysis (user_id, created_at DESC)
    INCLUDE (id, file_id, type, status, verdict, confidence, updated_at);

-- Partial index over "active" analyses — narrow and small (PENDING/PROCESSING only),
-- for @Scheduled stuck-job recovery (week 5) and the backpressure gauge (week 4).
CREATE INDEX idx_analysis_active
    ON analysis (status, updated_at)
    WHERE status IN ('PENDING', 'PROCESSING');
