-- ============================================================
-- V5 — Live Indexing Progress Tracking
-- ============================================================
-- Adds stage, user-friendly message, and progress percentage to
-- analysis_jobs to enable real-time UI progress tracking.
-- Also resets any stuck INDEXING repositories back to NOT_INDEXED.
-- ============================================================

ALTER TABLE analysis_jobs ADD COLUMN IF NOT EXISTS current_stage VARCHAR(100) DEFAULT 'QUEUED';
ALTER TABLE analysis_jobs ADD COLUMN IF NOT EXISTS current_message VARCHAR(255) DEFAULT 'Job queued in background...';
ALTER TABLE analysis_jobs ADD COLUMN IF NOT EXISTS progress_percent INT DEFAULT 0;

-- Reset any repositories currently stuck in INDEXING from previous runs
UPDATE repositories 
SET indexing_status = 'NOT_INDEXED' 
WHERE indexing_status = 'INDEXING';

