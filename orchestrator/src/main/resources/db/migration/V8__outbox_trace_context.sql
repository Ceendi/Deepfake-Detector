ALTER TABLE analysis_task_outbox ADD COLUMN traceparent VARCHAR(55);

-- Terminal tasks cannot be published and must leave the partial due index.
DELETE FROM analysis_task_outbox o USING analysis a
WHERE o.analysis_id = a.id AND o.sent_at IS NULL
    AND a.status IN ('COMPLETED', 'FAILED', 'CANCELLED');
