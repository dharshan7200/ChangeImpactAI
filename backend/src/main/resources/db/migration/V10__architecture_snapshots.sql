-- Architecture digital twin: one structural snapshot per analysed commit (production code only), computed from the
-- commit's symbols and dependencies and stored so trends and comparisons are cheap. A snapshot is rebuilt when the
-- commit is re-analysed (source_fingerprint) or the twin's algorithm changes (twin_version).
CREATE TABLE architecture_snapshots (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha          VARCHAR(64) NOT NULL,
    twin_version        INTEGER NOT NULL,
    source_fingerprint  VARCHAR(100) NOT NULL,   -- file count + newest row time of the commit's analysis
    commit_date         TIMESTAMPTZ,
    commit_message      TEXT,
    summary             JSONB NOT NULL,
    packages            JSONB NOT NULL,
    edges               JSONB NOT NULL,
    cycles              JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (repository_id, commit_sha)
);
CREATE INDEX idx_architecture_snapshots_repo ON architecture_snapshots(repository_id, commit_date);

-- background job that analyses evenly spaced past commits so the twin has a history (POST .../architecture/backfill)
ALTER TABLE analysis_jobs DROP CONSTRAINT chk_job_type;
ALTER TABLE analysis_jobs ADD CONSTRAINT chk_job_type
    CHECK (job_type IN ('REPOSITORY_INDEX', 'CHANGE_ANALYSIS', 'PR_ANALYSIS', 'ARCHITECTURE_HISTORY'));
