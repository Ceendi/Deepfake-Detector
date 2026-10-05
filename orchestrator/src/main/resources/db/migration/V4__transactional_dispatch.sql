CREATE TABLE analysis_task_outbox (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES analysis(id) ON DELETE CASCADE,
    source VARCHAR(5) NOT NULL CHECK (source IN ('audio', 'video')),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    sent_at TIMESTAMPTZ,
    UNIQUE (analysis_id, source)
);
CREATE INDEX idx_task_outbox_due ON analysis_task_outbox(next_attempt_at, created_at)
    WHERE sent_at IS NULL;
-- Legacy PENDING rows intentionally have no replay tasks: their audio mode was not persisted.
