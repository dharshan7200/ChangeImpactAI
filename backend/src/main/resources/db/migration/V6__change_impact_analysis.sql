-- ============================================================
-- V6 — Phase 3: change detection, impact analysis, risk engine
-- ============================================================
-- 1. Symbol fingerprints needed for semantic (not line-based) change detection
-- 2. Commit history intelligence (per-commit file changes, revert / bug-fix flags)
-- 3. Change analyses with changed files, changed symbols, impact findings and
--    explainable risk assessments
-- Tenant isolation: every row is scoped by repository_id (repositories are owned by a user).
-- ============================================================

-- ---- 1. Symbol fingerprints ---------------------------------------------------
ALTER TABLE symbols ADD COLUMN IF NOT EXISTS content_hash    VARCHAR(64);
    -- SHA-256 of the declaration printed WITHOUT comments/formatting:
    --   methods/constructors: full declaration incl. body; fields: declaration incl. initializer;
    --   types: header only (modifiers, annotations, name, type parameters, supertypes)
ALTER TABLE symbols ADD COLUMN IF NOT EXISTS annotation_text TEXT;
    -- full annotation expressions (e.g. @GetMapping("/owners/{id}")), newline separated
ALTER TABLE symbols ADD COLUMN IF NOT EXISTS complexity      INT;
    -- cyclomatic complexity for methods/constructors (1 + decision points), NULL otherwise

-- ---- 2. Commit history intelligence ------------------------------------------
ALTER TABLE commits ADD COLUMN IF NOT EXISTS additions        INT NOT NULL DEFAULT 0;
ALTER TABLE commits ADD COLUMN IF NOT EXISTS deletions        INT NOT NULL DEFAULT 0;
ALTER TABLE commits ADD COLUMN IF NOT EXISTS is_merge         BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE commits ADD COLUMN IF NOT EXISTS is_revert        BOOLEAN NOT NULL DEFAULT FALSE;
    -- heuristic: message starts with 'Revert "' or contains 'This reverts commit'
ALTER TABLE commits ADD COLUMN IF NOT EXISTS is_bugfix        BOOLEAN NOT NULL DEFAULT FALSE;
    -- heuristic label from the commit message (fix/bug/hotfix/...); NOT verified ground truth
ALTER TABLE commits ADD COLUMN IF NOT EXISTS history_scanned  BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_commits_repo_date ON commits(repository_id, commit_date DESC);

CREATE TABLE commit_file_changes (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id   UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha      VARCHAR(64) NOT NULL,
    path            VARCHAR(1024) NOT NULL,     -- new path (old path for deletions)
    old_path        VARCHAR(1024),
    change_type     VARCHAR(20) NOT NULL,       -- ADDED, MODIFIED, DELETED, RENAMED, COPIED
    additions       INT NOT NULL DEFAULT 0,
    deletions       INT NOT NULL DEFAULT 0,
    UNIQUE (repository_id, commit_sha, path)
);
CREATE INDEX idx_commit_file_changes_path ON commit_file_changes(repository_id, path);

-- ---- 3. Change analyses -------------------------------------------------------
CREATE TABLE change_analyses (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    owner_id            UUID NOT NULL REFERENCES users(id),
    job_id              UUID REFERENCES analysis_jobs(id) ON DELETE SET NULL,
    base_ref            VARCHAR(255) NOT NULL,   -- as requested (sha, branch or tag)
    head_ref            VARCHAR(255) NOT NULL,
    base_commit         VARCHAR(64),             -- resolved full SHAs
    head_commit         VARCHAR(64),
    status              VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    options             JSONB,                   -- maxDepth, relationship types, mergeBase
    summary             JSONB,                   -- aggregated counts for dashboards
    risk_score          NUMERIC(5,1),
    risk_level          VARCHAR(20),
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    CONSTRAINT chk_change_analysis_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_change_analysis_risk CHECK (risk_level IS NULL OR risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);
CREATE INDEX idx_change_analyses_repo ON change_analyses(repository_id, created_at DESC);

CREATE TABLE changed_files (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    analysis_id     UUID NOT NULL REFERENCES change_analyses(id) ON DELETE CASCADE,
    path            VARCHAR(1024) NOT NULL,
    old_path        VARCHAR(1024),
    change_type     VARCHAR(20) NOT NULL,        -- ADDED, MODIFIED, DELETED, RENAMED, COPIED
    file_category   VARCHAR(30) NOT NULL,        -- JAVA_SOURCE, JAVA_TEST, CONFIG, BUILD, RESOURCE, DOCS, OTHER
    additions       INT NOT NULL DEFAULT 0,
    deletions       INT NOT NULL DEFAULT 0,
    hunks           JSONB                        -- [{oldStart, oldEnd, newStart, newEnd}] (1-based, inclusive)
);
CREATE INDEX idx_changed_files_analysis ON changed_files(analysis_id);

CREATE TABLE changed_symbols (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    analysis_id         UUID NOT NULL REFERENCES change_analyses(id) ON DELETE CASCADE,
    qualified_name      VARCHAR(1024) NOT NULL,
    previous_qualified_name VARCHAR(1024),       -- for moved types / changed signatures
    simple_name         VARCHAR(255) NOT NULL,
    symbol_type         VARCHAR(50) NOT NULL,
    change_kinds        TEXT[] NOT NULL,         -- see ChangeKind enum
    breaking            BOOLEAN NOT NULL DEFAULT FALSE,
    base_symbol_id      UUID REFERENCES symbols(id) ON DELETE SET NULL,
    head_symbol_id      UUID REFERENCES symbols(id) ON DELETE SET NULL,
    file_path           VARCHAR(1024),
    start_line          INT,
    end_line            INT,
    is_test             BOOLEAN NOT NULL DEFAULT FALSE,
    details             JSONB,                   -- before/after values per change kind
    direct_dependents   INT NOT NULL DEFAULT 0,
    transitive_dependents INT NOT NULL DEFAULT 0,
    risk_score          NUMERIC(5,1),
    risk_level          VARCHAR(20)
);
CREATE INDEX idx_changed_symbols_analysis ON changed_symbols(analysis_id);

CREATE TABLE impact_findings (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    analysis_id         UUID NOT NULL REFERENCES change_analyses(id) ON DELETE CASCADE,
    changed_symbol_id   UUID NOT NULL REFERENCES changed_symbols(id) ON DELETE CASCADE,
    affected_symbol_id  UUID REFERENCES symbols(id) ON DELETE SET NULL,
    graph_commit        VARCHAR(64) NOT NULL,    -- commit whose graph produced the finding
    affected_qualified_name VARCHAR(1024) NOT NULL,
    affected_simple_name VARCHAR(255) NOT NULL,
    affected_symbol_type VARCHAR(50) NOT NULL,
    affected_owner_type VARCHAR(1024),           -- enclosing class of the affected symbol
    file_path           VARCHAR(1024),
    start_line          INT,
    package_name        VARCHAR(512),
    module_name         VARCHAR(512),
    depth               INT NOT NULL,
    strength            NUMERIC(6,4) NOT NULL,   -- 0..1, product of relationship weights and depth decay
    impact_type         VARCHAR(20) NOT NULL,    -- DIRECT, INDIRECT
    evidence            VARCHAR(40) NOT NULL,    -- VERIFIED_REFERENCE, TRANSITIVE_PROPAGATION
    is_test             BOOLEAN NOT NULL DEFAULT FALSE,
    also_changed        BOOLEAN NOT NULL DEFAULT FALSE,  -- affected symbol is itself modified in this change
    dependency_path     JSONB NOT NULL           -- [{qualifiedName, simpleName, symbolType, relationship[], line}]
);
CREATE INDEX idx_impact_findings_analysis ON impact_findings(analysis_id);
CREATE INDEX idx_impact_findings_changed ON impact_findings(changed_symbol_id);

CREATE TABLE risk_assessments (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    analysis_id         UUID NOT NULL REFERENCES change_analyses(id) ON DELETE CASCADE,
    changed_symbol_id   UUID REFERENCES changed_symbols(id) ON DELETE CASCADE,  -- NULL = whole change
    score               NUMERIC(5,1) NOT NULL,   -- 0..100 weighted heuristic, NOT a probability
    level               VARCHAR(20) NOT NULL,
    factors             JSONB NOT NULL,          -- [{key, label, raw, normalized, weight, contribution, explanation}]
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_risk_assessments_analysis ON risk_assessments(analysis_id);
