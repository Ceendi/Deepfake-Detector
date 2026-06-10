-- Source of truth for fileId -> objectKey + owner + attributes. Without it the
-- {id}-based endpoints (metadata/presign/delete) cannot resolve the S3 key or check
-- ownership. TIMESTAMPTZ matches the orchestrator (both store true instants).
CREATE TABLE IF NOT EXISTS file_metadata (
    file_id          UUID PRIMARY KEY,
    object_key       VARCHAR(600) NOT NULL,
    user_id          VARCHAR(255) NOT NULL,
    original_name    VARCHAR(512),
    mimetype         VARCHAR(255) NOT NULL,
    size_bytes       BIGINT NOT NULL,
    duration_seconds DOUBLE PRECISION,            -- from ffprobe (commit 5); NULL for now
    deleted_at       TIMESTAMPTZ,                 -- NULL = active; soft delete in commit 8
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Lookups are by file_id (PK) or by user_id; the listing only cares about active rows.
CREATE INDEX idx_file_metadata_user_active
    ON file_metadata (user_id, created_at DESC)
    WHERE deleted_at IS NULL;
