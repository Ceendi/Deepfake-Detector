CREATE INDEX idx_analysis_active_user
    ON analysis (user_id)
    WHERE status IN ('PENDING', 'PROCESSING');
