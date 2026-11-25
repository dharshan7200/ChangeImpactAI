-- Historical defect-prediction prototype (P6-6): one model per repository, trained from Git history with SZZ labels.
-- The row stores the evaluation even when the model is not used (status <> 'ACCEPTED'); coefficients and scored
-- commits are only stored for accepted models.
CREATE TABLE defect_models (
    repository_id   UUID PRIMARY KEY REFERENCES repositories(id) ON DELETE CASCADE,
    status          VARCHAR(40)  NOT NULL,          -- ACCEPTED | INSUFFICIENT_LABELS | NOT_BETTER_THAN_BASELINE
    reason          TEXT         NOT NULL,
    head_commit     VARCHAR(64)  NOT NULL,
    model_version   INTEGER      NOT NULL,
    dataset         JSONB        NOT NULL,          -- commits, fixes, SZZ statistics, timings
    evaluation      JSONB        NOT NULL,          -- time-ordered split, metrics, baselines, calibration
    model           JSONB,                          -- coefficients + standardisation (accepted only)
    threshold       DOUBLE PRECISION,
    predictions     JSONB        NOT NULL DEFAULT '[]'::jsonb,  -- newest scored commits with drivers (accepted only)
    trained_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE analysis_jobs DROP CONSTRAINT chk_job_type;
ALTER TABLE analysis_jobs ADD CONSTRAINT chk_job_type
    CHECK (job_type IN ('REPOSITORY_INDEX', 'CHANGE_ANALYSIS', 'PR_ANALYSIS', 'ARCHITECTURE_HISTORY', 'DEFECT_MODEL'));
