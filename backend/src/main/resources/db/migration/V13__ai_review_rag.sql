-- Phase 4: retrieval-augmented AI review and repository assistant.

-- pgvector (shipped by the pgvector/pgvector image; on managed PostgreSQL the extension must be allowed)
CREATE EXTENSION IF NOT EXISTS vector;

-- Code chunks per analysed commit (one per method/constructor/function, one header chunk per type/module).
-- Embeddings are reused across commits and repositories by content hash (same text, same model → same vector).
CREATE TABLE code_chunks (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id   UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha      VARCHAR(64) NOT NULL,
    symbol_id       UUID REFERENCES symbols(id) ON DELETE SET NULL,
    qualified_name  VARCHAR(1024) NOT NULL,
    symbol_type     VARCHAR(50) NOT NULL,
    file_path       VARCHAR(1024) NOT NULL,
    start_line      INTEGER NOT NULL,
    end_line        INTEGER NOT NULL,
    content         TEXT NOT NULL,              -- secrets redacted before storage
    content_hash    VARCHAR(64) NOT NULL,
    -- full-text index over name + code with camelCase split ("processFindForm" → process find form)
    tsv             TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple',
                        regexp_replace(qualified_name || ' ' || content, '([a-z0-9])([A-Z])', '\1 \2', 'g'))) STORED,
    embedding       vector(768),
    embedding_model VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (repository_id, commit_sha, qualified_name, start_line)
);
CREATE INDEX idx_code_chunks_commit ON code_chunks(repository_id, commit_sha);
CREATE INDEX idx_code_chunks_hash ON code_chunks(content_hash, embedding_model);
CREATE INDEX idx_code_chunks_tsv ON code_chunks USING GIN (tsv);
CREATE INDEX idx_code_chunks_embedding ON code_chunks USING hnsw (embedding vector_cosine_ops);

-- One AI review per request (an analysis can be reviewed again, e.g. with another model).
CREATE TABLE ai_reviews (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    analysis_id     UUID NOT NULL REFERENCES change_analyses(id) ON DELETE CASCADE,
    job_id          UUID REFERENCES analysis_jobs(id) ON DELETE SET NULL,
    status          VARCHAR(20) NOT NULL,       -- QUEUED | RUNNING | COMPLETED | FAILED
    model           VARCHAR(100),
    prompt_version  VARCHAR(20),
    prompt_tokens   INTEGER,
    output_tokens   INTEGER,
    latency_ms      BIGINT,
    evidence        JSONB,                      -- the evidence items the model was given (id → reference)
    result          JSONB,                      -- validated review (findings with VERIFIED/UNVERIFIED)
    validation      JSONB,                      -- citation and mention checks
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);
CREATE INDEX idx_ai_reviews_analysis ON ai_reviews(analysis_id, created_at DESC);

-- Assistant questions and validated answers (for history and evaluation).
CREATE TABLE assistant_messages (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id   UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    commit_sha      VARCHAR(64),
    question        TEXT NOT NULL,
    answer          JSONB,
    model           VARCHAR(100),
    prompt_tokens   INTEGER,
    output_tokens   INTEGER,
    latency_ms      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_assistant_messages_repo ON assistant_messages(repository_id, user_id, created_at DESC);

ALTER TABLE analysis_jobs DROP CONSTRAINT chk_job_type;
ALTER TABLE analysis_jobs ADD CONSTRAINT chk_job_type
    CHECK (job_type IN ('REPOSITORY_INDEX', 'CHANGE_ANALYSIS', 'PR_ANALYSIS', 'ARCHITECTURE_HISTORY', 'DEFECT_MODEL',
                        'RAG_INDEX', 'AI_REVIEW'));
