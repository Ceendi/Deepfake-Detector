-- Deliberately no FK: deletion work must outlive the deleted analysis.
CREATE TABLE artifact_cleanup (
    object_key TEXT PRIMARY KEY,
    analysis_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_token UUID,
    lease_until TIMESTAMPTZ
);
CREATE INDEX idx_artifact_cleanup_due ON artifact_cleanup (next_attempt_at, created_at);

-- One bounded ListObjectsV2 page per scan; resume after process restarts.
CREATE TABLE artifact_cleanup_scan (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    continuation_token TEXT,
    lease_token UUID,
    lease_until TIMESTAMPTZ
);
INSERT INTO artifact_cleanup_scan (id) VALUES (1);

-- Every cleanup claim protects references across all analyses; avoid per-key full-table scans.
CREATE INDEX idx_analysis_video_gradcam_keys ON analysis USING GIN ((video_details->'gradcamKeys') jsonb_path_ops);
CREATE INDEX idx_analysis_audio_gradcam_keys ON analysis USING GIN ((audio_details->'gradcamKeys') jsonb_path_ops);
