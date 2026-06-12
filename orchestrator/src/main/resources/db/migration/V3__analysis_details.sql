-- Per-source detector result details (model_version, gradcam_keys, metadata).
-- Two disjoint columns, not one shared JSONB: video and audio results land concurrently
-- in a FULL analysis, and the aggregation safety relies on each source writing only its
-- own columns in an atomic UPDATE (see AnalysisRepository). A shared column would need a
-- read-merge-write and reintroduce the race the disjoint writes were built to avoid.
ALTER TABLE analysis
    ADD COLUMN video_details JSONB,
    ADD COLUMN audio_details JSONB;

-- V1 shipped a single `details` column that was never mapped or written; superseded by the
-- per-source pair above.
ALTER TABLE analysis
    DROP COLUMN details;
