CREATE TABLE IF NOT EXISTS analysis (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    file_id VARCHAR(255) NOT NULL,
    file_key VARCHAR(500) NOT NULL,
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    verdict VARCHAR(20),
    confidence DECIMAL(5,4),
    video_prob DECIMAL(5,4),
    audio_prob DECIMAL(5,4),
    details JSONB,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
    );
CREATE INDEX idx_analysis_user_created ON analysis (user_id, created_at DESC);