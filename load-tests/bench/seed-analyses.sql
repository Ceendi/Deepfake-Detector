-- MO benchmark seed: ~100k COMPLETED analyses for one user so the per-user history query
-- (idx_analysis_user_created) is measurable. Re-runnable: clears prior bench rows first.
DELETE FROM analysis WHERE user_id = 'bench-user';

INSERT INTO analysis (id, user_id, file_id, file_key, type, status, verdict, confidence, created_at, updated_at)
SELECT gen_random_uuid(),
       'bench-user',
       'file-' || g,
       'bench/' || g || '.mp4',
       'FULL',
       'COMPLETED',
       CASE WHEN g % 2 = 0 THEN 'REAL' ELSE 'FAKE' END,
       0.9000,
       NOW() - (g * interval '1 minute'),
       NOW() - (g * interval '1 minute')
FROM generate_series(1, 100000) AS g;

ANALYZE analysis;  -- refresh planner stats so it picks the index
SELECT count(*) AS bench_rows FROM analysis WHERE user_id = 'bench-user';
