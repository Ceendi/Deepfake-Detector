ALTER TABLE analysis
    ADD COLUMN video_started_at TIMESTAMPTZ,
    ADD COLUMN video_progress_at TIMESTAMPTZ,
    ADD COLUMN audio_started_at TIMESTAMPTZ,
    ADD COLUMN audio_progress_at TIMESTAMPTZ;

-- A single-source PROCESSING row proves pickup; FULL's old shared clock cannot identify it.
UPDATE analysis SET video_started_at = updated_at, video_progress_at = updated_at
WHERE status = 'PROCESSING' AND type = 'VIDEO' AND video_prob IS NULL;
UPDATE analysis SET audio_started_at = updated_at, audio_progress_at = updated_at
WHERE status = 'PROCESSING' AND type = 'AUDIO' AND audio_prob IS NULL AND audio_details IS NULL;
