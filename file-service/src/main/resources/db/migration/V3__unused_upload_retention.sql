ALTER TABLE file_metadata ADD COLUMN analyzed_at TIMESTAMPTZ;

-- Historical analysis references live in another service; preserve existing uploads conservatively.
UPDATE file_metadata SET analyzed_at = created_at;

CREATE INDEX idx_file_metadata_unused ON file_metadata (created_at)
    WHERE analyzed_at IS NULL AND deleted_at IS NULL;
