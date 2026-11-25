-- ============================================================
-- V7 — Phase 5: test intelligence, security analysis, GitHub App automation
-- ============================================================
-- All rows are scoped by repository_id (directly or through analysis_id / report_id).
-- ============================================================

-- ---- Quality reports uploaded per commit (CI artefacts) ------------------------
CREATE TABLE quality_reports (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id   UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha      VARCHAR(64) NOT NULL,
    report_type     VARCHAR(30) NOT NULL,       -- JACOCO, JUNIT, PIT, DEPENDENCY_CHECK
    file_name       VARCHAR(255),
    uploaded_by     UUID REFERENCES users(id) ON DELETE SET NULL,
    summary         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_quality_report_type CHECK (report_type IN ('JACOCO', 'JUNIT', 'PIT', 'DEPENDENCY_CHECK'))
);
CREATE INDEX idx_quality_reports_lookup ON quality_reports(repository_id, commit_sha, report_type, created_at DESC);

-- JaCoCo line coverage per source file (package path + file name, e.g. com/x/Foo.java)
CREATE TABLE coverage_files (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    report_id       UUID NOT NULL REFERENCES quality_reports(id) ON DELETE CASCADE,
    source_path     VARCHAR(1024) NOT NULL,
    covered_lines   INT[] NOT NULL,
    missed_lines    INT[] NOT NULL
);
CREATE INDEX idx_coverage_files_report ON coverage_files(report_id);

-- JaCoCo method counters
CREATE TABLE coverage_methods (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    report_id       UUID NOT NULL REFERENCES quality_reports(id) ON DELETE CASCADE,
    class_name      VARCHAR(1024) NOT NULL,     -- binary name with dots, nested classes with $
    method_name     VARCHAR(255) NOT NULL,
    method_desc     VARCHAR(1024),
    first_line      INT,
    lines_covered   INT NOT NULL DEFAULT 0,
    lines_missed    INT NOT NULL DEFAULT 0,
    branches_covered INT NOT NULL DEFAULT 0,
    branches_missed INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_coverage_methods_report ON coverage_methods(report_id, class_name);

-- JUnit / Surefire results: tests that were actually executed
CREATE TABLE test_executions (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    report_id       UUID NOT NULL REFERENCES quality_reports(id) ON DELETE CASCADE,
    class_name      VARCHAR(1024) NOT NULL,
    method_name     VARCHAR(512) NOT NULL,
    status          VARCHAR(20) NOT NULL,       -- PASSED, FAILED, ERROR, SKIPPED
    duration_ms     BIGINT,
    message         TEXT
);
CREATE INDEX idx_test_executions_report ON test_executions(report_id, class_name);

-- PIT mutation results
CREATE TABLE mutation_results (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    report_id       UUID NOT NULL REFERENCES quality_reports(id) ON DELETE CASCADE,
    class_name      VARCHAR(1024) NOT NULL,
    method_name     VARCHAR(255) NOT NULL,
    method_desc     VARCHAR(1024),
    line_number     INT,
    mutator         VARCHAR(255),
    status          VARCHAR(30) NOT NULL,       -- KILLED, SURVIVED, NO_COVERAGE, TIMED_OUT, MEMORY_ERROR, RUN_ERROR
    killing_test    TEXT,
    description     TEXT
);
CREATE INDEX idx_mutation_results_report ON mutation_results(report_id, class_name);

-- OWASP Dependency-Check vulnerabilities
CREATE TABLE dependency_vulnerabilities (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    report_id       UUID NOT NULL REFERENCES quality_reports(id) ON DELETE CASCADE,
    dependency      VARCHAR(512) NOT NULL,      -- file name reported by Dependency-Check
    package_id      VARCHAR(512),               -- e.g. pkg:maven/group/artifact@version
    vulnerability   VARCHAR(100) NOT NULL,      -- CVE / GHSA id
    severity        VARCHAR(20),
    cvss_score      NUMERIC(4,1),
    cwes            TEXT[],
    description     TEXT
);
CREATE INDEX idx_dependency_vulnerabilities_report ON dependency_vulnerabilities(report_id);

-- ---- Analysis enrichment ------------------------------------------------------
CREATE TABLE test_recommendations (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    analysis_id         UUID NOT NULL REFERENCES change_analyses(id) ON DELETE CASCADE,
    category            VARCHAR(20) NOT NULL,   -- DIRECT, INDIRECT, MISSING_TEST, SCENARIO
    priority            VARCHAR(10) NOT NULL,   -- HIGH, MEDIUM, LOW
    test_symbol_id      UUID REFERENCES symbols(id) ON DELETE SET NULL,
    test_class          VARCHAR(1024),
    test_method         VARCHAR(512),
    target_symbol       VARCHAR(1024),          -- changed symbol the recommendation is about
    reason              TEXT NOT NULL,
    execution_status    VARCHAR(20) NOT NULL,   -- PASSED, FAILED, SKIPPED, NOT_EXECUTED, NO_RESULTS, NOT_APPLICABLE
    depth               INT,
    evidence            JSONB
);
CREATE INDEX idx_test_recommendations_analysis ON test_recommendations(analysis_id);

CREATE TABLE security_findings (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    analysis_id         UUID NOT NULL REFERENCES change_analyses(id) ON DELETE CASCADE,
    source              VARCHAR(30) NOT NULL,   -- STATIC_RULES, SEMGREP, DEPENDENCY_CHECK, BUILD_DIFF
    category            VARCHAR(40) NOT NULL,   -- AUTHENTICATION, AUTHORIZATION, SENSITIVE_DATA, CRYPTOGRAPHY, SQL, INPUT_HANDLING, DEPENDENCY, CODE_PATTERN
    rule_id             VARCHAR(255),
    severity            VARCHAR(10) NOT NULL,   -- INFO, LOW, MEDIUM, HIGH, CRITICAL
    title               VARCHAR(512) NOT NULL,
    message             TEXT,
    file_path           VARCHAR(1024),
    start_line          INT,
    end_line            INT,
    symbol              VARCHAR(1024),
    relation            VARCHAR(20) NOT NULL,   -- CHANGED, IMPACTED, DEPENDENCY
    cwe                 VARCHAR(100),
    evidence            JSONB
);
CREATE INDEX idx_security_findings_analysis ON security_findings(analysis_id);

-- ---- GitHub App ----------------------------------------------------------------
CREATE TABLE github_app_installations (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    installation_id         BIGINT NOT NULL UNIQUE,
    account_login           VARCHAR(255) NOT NULL,
    account_type            VARCHAR(30),
    repository_selection    VARCHAR(20),        -- all, selected
    repositories            JSONB,              -- ["owner/repo", ...]
    suspended               BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- webhook_deliveries exists since V2 (delivery_id UNIQUE, event_type, action, repository_full_name,
-- payload_hash, processed, processed_at, error, received_at); extend it for processing results.
ALTER TABLE webhook_deliveries
    ADD COLUMN IF NOT EXISTS installation_id BIGINT,
    ADD COLUMN IF NOT EXISTS status          VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED',  -- ACCEPTED, IGNORED, FAILED
    ADD COLUMN IF NOT EXISTS detail          TEXT,
    ADD COLUMN IF NOT EXISTS analysis_ids    JSONB;
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_repo ON webhook_deliveries(repository_full_name, received_at DESC);

CREATE TABLE pull_requests (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    number              INT NOT NULL,
    title               VARCHAR(1024),
    author              VARCHAR(255),
    url                 VARCHAR(1024),
    state               VARCHAR(20) NOT NULL,           -- open, closed
    draft               BOOLEAN NOT NULL DEFAULT FALSE,
    base_ref            VARCHAR(255),
    base_sha            VARCHAR(64),
    head_ref            VARCHAR(255),
    head_sha            VARCHAR(64),
    last_analysis_id    UUID REFERENCES change_analyses(id) ON DELETE SET NULL,
    risk_level          VARCHAR(20),
    risk_score          NUMERIC(5,1),
    check_run_id        BIGINT,
    check_status        VARCHAR(40),                    -- NOT_CONFIGURED, QUEUED, IN_PROGRESS, COMPLETED, FAILED_TO_PUBLISH
    check_conclusion    VARCHAR(30),
    check_error         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (repository_id, number)
);

ALTER TABLE change_analyses ADD COLUMN IF NOT EXISTS trigger VARCHAR(20) NOT NULL DEFAULT 'MANUAL';  -- MANUAL, PULL_REQUEST
ALTER TABLE change_analyses ADD COLUMN IF NOT EXISTS pull_request_id UUID REFERENCES pull_requests(id) ON DELETE SET NULL;
