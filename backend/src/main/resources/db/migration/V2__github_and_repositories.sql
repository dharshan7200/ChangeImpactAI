-- ============================================================
-- V2 — GitHub Integration and Repository Management
-- ============================================================

-- ---- Connected GitHub accounts ----
-- Users can connect one or more GitHub accounts. OAuth tokens
-- are stored encrypted at the application layer (not plaintext).
CREATE TABLE connected_accounts (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider            VARCHAR(50) NOT NULL DEFAULT 'github',
    provider_account_id VARCHAR(255) NOT NULL,
    login               VARCHAR(255) NOT NULL,
    display_name        VARCHAR(255),
    avatar_url          VARCHAR(1024),
    email               VARCHAR(255),
    -- Access tokens are AES-encrypted at the application layer
    encrypted_access_token  TEXT,
    encrypted_refresh_token TEXT,
    token_expires_at    TIMESTAMPTZ,
    scopes              TEXT[],
    github_app_installation_id BIGINT,
    connected_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_refreshed_at   TIMESTAMPTZ,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (user_id, provider, provider_account_id)
);

CREATE INDEX idx_connected_accounts_user_id    ON connected_accounts(user_id);
CREATE INDEX idx_connected_accounts_provider   ON connected_accounts(provider, provider_account_id);
CREATE INDEX idx_connected_accounts_active     ON connected_accounts(user_id) WHERE is_active = TRUE;

-- ---- Repositories ----
CREATE TABLE repositories (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    connected_account_id UUID REFERENCES connected_accounts(id) ON DELETE SET NULL,
    -- GitHub identifiers
    github_repo_id      BIGINT,
    full_name           VARCHAR(512) NOT NULL,       -- e.g. "owner/repo-name"
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    clone_url           VARCHAR(2048) NOT NULL,
    html_url            VARCHAR(2048),
    default_branch      VARCHAR(255) NOT NULL DEFAULT 'main',
    -- Repository metadata
    language            VARCHAR(100),
    is_private          BOOLEAN NOT NULL DEFAULT FALSE,
    is_fork             BOOLEAN NOT NULL DEFAULT FALSE,
    fork_count          INT NOT NULL DEFAULT 0,
    star_count          INT NOT NULL DEFAULT 0,
    size_kb             BIGINT,
    -- Management
    indexing_status     VARCHAR(50) NOT NULL DEFAULT 'NOT_INDEXED',
                        -- NOT_INDEXED, INDEXING, INDEXED, FAILED, STALE
    last_indexed_at     TIMESTAMPTZ,
    last_indexed_commit VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Soft delete
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT chk_indexing_status CHECK (
        indexing_status IN ('NOT_INDEXED', 'INDEXING', 'INDEXED', 'FAILED', 'STALE')
    )
);

CREATE INDEX idx_repositories_owner_id ON repositories(owner_id);
CREATE INDEX idx_repositories_full_name ON repositories(full_name);
CREATE INDEX idx_repositories_indexing_status ON repositories(indexing_status);
CREATE INDEX idx_repositories_active ON repositories(owner_id) WHERE deleted_at IS NULL;

CREATE TRIGGER trg_repositories_updated_at
    BEFORE UPDATE ON repositories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---- Repository branches ----
CREATE TABLE repository_branches (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id   UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    commit_sha      VARCHAR(255),
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    is_protected    BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (repository_id, name)
);

CREATE INDEX idx_repository_branches_repo_id ON repository_branches(repository_id);

-- ---- Commits ----
CREATE TABLE commits (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id   UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    sha             VARCHAR(255) NOT NULL,
    short_sha       VARCHAR(10) GENERATED ALWAYS AS (LEFT(sha, 7)) STORED,
    message         TEXT,
    author_name     VARCHAR(255),
    author_email    VARCHAR(255),
    author_date     TIMESTAMPTZ,
    committer_name  VARCHAR(255),
    committer_email VARCHAR(255),
    commit_date     TIMESTAMPTZ,
    parent_shas     TEXT[],
    added_files     INT NOT NULL DEFAULT 0,
    modified_files  INT NOT NULL DEFAULT 0,
    deleted_files   INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (repository_id, sha)
);

CREATE INDEX idx_commits_repository_id ON commits(repository_id);
CREATE INDEX idx_commits_sha           ON commits(repository_id, sha);
CREATE INDEX idx_commits_author_date   ON commits(repository_id, author_date DESC);

-- ---- Repository indexes (indexing run metadata) ----
CREATE TABLE repository_indexes (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    branch_name         VARCHAR(255) NOT NULL,
    commit_sha          VARCHAR(255) NOT NULL,
    status              VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                        -- PENDING, RUNNING, COMPLETED, FAILED
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    duration_ms         BIGINT,
    total_files         INT,
    indexed_files       INT,
    failed_files        INT,
    total_symbols       INT,
    total_dependencies  INT,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_index_status CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')
    )
);

CREATE INDEX idx_repository_indexes_repo_id    ON repository_indexes(repository_id);
CREATE INDEX idx_repository_indexes_status     ON repository_indexes(status);
CREATE INDEX idx_repository_indexes_created_at ON repository_indexes(created_at DESC);

-- ---- Analysis jobs ----
CREATE TABLE analysis_jobs (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id   UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    owner_id        UUID NOT NULL REFERENCES users(id),
    job_type        VARCHAR(100) NOT NULL,
                    -- REPOSITORY_INDEX, CHANGE_ANALYSIS, PR_ANALYSIS
    status          VARCHAR(50) NOT NULL DEFAULT 'QUEUED',
                    -- QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    priority        INT NOT NULL DEFAULT 5,
    payload         JSONB,              -- job-specific parameters
    result          JSONB,              -- job output summary
    error_message   TEXT,
    retry_count     INT NOT NULL DEFAULT 0,
    max_retries     INT NOT NULL DEFAULT 3,
    queued_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    duration_ms     BIGINT,
    -- Deduplication key: prevents the same logical job from being queued twice
    idempotency_key VARCHAR(512) UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_job_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT chk_job_type CHECK (
        job_type IN ('REPOSITORY_INDEX', 'CHANGE_ANALYSIS', 'PR_ANALYSIS')
    )
);

CREATE INDEX idx_analysis_jobs_repository_id    ON analysis_jobs(repository_id);
CREATE INDEX idx_analysis_jobs_owner_id         ON analysis_jobs(owner_id);
CREATE INDEX idx_analysis_jobs_status           ON analysis_jobs(status);
CREATE INDEX idx_analysis_jobs_idempotency_key  ON analysis_jobs(idempotency_key);
CREATE INDEX idx_analysis_jobs_queued_at        ON analysis_jobs(queued_at DESC);

CREATE TRIGGER trg_analysis_jobs_updated_at
    BEFORE UPDATE ON analysis_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---- Webhook deliveries (for deduplication) ----
CREATE TABLE webhook_deliveries (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    delivery_id     VARCHAR(255) NOT NULL UNIQUE,  -- X-GitHub-Delivery header
    event_type      VARCHAR(100) NOT NULL,
    action          VARCHAR(100),
    repository_full_name VARCHAR(512),
    payload_hash    VARCHAR(255),                  -- SHA-256 of body for idempotency
    processed       BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at    TIMESTAMPTZ,
    error           TEXT,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_deliveries_delivery_id ON webhook_deliveries(delivery_id);
CREATE INDEX idx_webhook_deliveries_received_at ON webhook_deliveries(received_at DESC);

