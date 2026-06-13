-- The cleanup sweep scans only soft-deleted rows (deleted_at < retention cutoff); a partial
-- index keeps that scan off the table no matter how many active rows accumulate.
CREATE INDEX idx_file_metadata_purge
    ON file_metadata (deleted_at)
    WHERE deleted_at IS NOT NULL;
