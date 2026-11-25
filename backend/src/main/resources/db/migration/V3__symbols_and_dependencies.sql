-- ============================================================
-- V3 — Symbols, Dependencies, and Architecture Rules
-- ============================================================
-- Provides granular relational storage for Abstract Syntax Tree (AST)
-- symbols and deterministic directed dependency graph edges.
-- ============================================================

-- ---- Source Files ----
CREATE TABLE source_files (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id   UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha      VARCHAR(255) NOT NULL,
    relative_path   VARCHAR(1024) NOT NULL,
    package_name    VARCHAR(512),
    lines_of_code   INT NOT NULL DEFAULT 0,
    size_bytes      BIGINT NOT NULL DEFAULT 0,
    is_test         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (repository_id, commit_sha, relative_path)
);

CREATE INDEX idx_source_files_repo_commit ON source_files(repository_id, commit_sha);
CREATE INDEX idx_source_files_path        ON source_files(relative_path);

-- ---- Symbols ----
-- Represents packages, classes, interfaces, records, enums, methods, constructors, fields.
CREATE TABLE symbols (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    source_file_id      UUID NOT NULL REFERENCES source_files(id) ON DELETE CASCADE,
    commit_sha          VARCHAR(255) NOT NULL,
    symbol_type         VARCHAR(50) NOT NULL,
                        -- PACKAGE, CLASS, INTERFACE, ENUM, RECORD, METHOD, CONSTRUCTOR, FIELD
    qualified_name      VARCHAR(1024) NOT NULL,
    simple_name         VARCHAR(255) NOT NULL,
    parent_symbol_id    UUID REFERENCES symbols(id) ON DELETE CASCADE,
    return_type         VARCHAR(512),
    parameters          TEXT,
    visibility          VARCHAR(20) NOT NULL DEFAULT 'PACKAGE_PRIVATE',
                        -- PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE
    is_static           BOOLEAN NOT NULL DEFAULT FALSE,
    is_abstract         BOOLEAN NOT NULL DEFAULT FALSE,
    is_final            BOOLEAN NOT NULL DEFAULT FALSE,
    annotations         TEXT[],
    start_line          INT NOT NULL,
    end_line            INT NOT NULL,
    start_column        INT,
    end_column          INT,
    resolution_status   VARCHAR(50) NOT NULL DEFAULT 'RESOLVED',
                        -- RESOLVED, PARTIAL, UNRESOLVED, EXTERNAL
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_symbols_repo_commit   ON symbols(repository_id, commit_sha);
CREATE INDEX idx_symbols_file          ON symbols(source_file_id);
CREATE INDEX idx_symbols_qualified     ON symbols(qualified_name);
CREATE INDEX idx_symbols_parent        ON symbols(parent_symbol_id);
CREATE INDEX idx_symbols_type          ON symbols(symbol_type);

-- ---- Symbol Dependencies ----
-- Directed relationship from source_symbol to target_symbol.
CREATE TABLE dependencies (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id           UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha              VARCHAR(255) NOT NULL,
    source_symbol_id        UUID NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,
    target_symbol_id        UUID REFERENCES symbols(id) ON DELETE SET NULL,
    target_qualified_name   VARCHAR(1024) NOT NULL,
    dependency_type         VARCHAR(50) NOT NULL,
                            -- EXTENDS, IMPLEMENTS, METHOD_CALL, CONSTRUCTOR_CALL,
                            -- FIELD_ACCESS, RETURN_TYPE, PARAMETER_TYPE, ANNOTATION
    is_external             BOOLEAN NOT NULL DEFAULT FALSE,
    source_location_line    INT,
    resolution_status       VARCHAR(50) NOT NULL DEFAULT 'RESOLVED',
                            -- RESOLVED, UNRESOLVED
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_deps_repo_commit   ON dependencies(repository_id, commit_sha);
CREATE INDEX idx_deps_source        ON dependencies(source_symbol_id);
CREATE INDEX idx_deps_target        ON dependencies(target_symbol_id);
CREATE INDEX idx_deps_target_name   ON dependencies(target_qualified_name);
CREATE INDEX idx_deps_type          ON dependencies(dependency_type);

-- ---- Architecture Rules ----
CREATE TABLE architecture_rules (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id   UUID REFERENCES repositories(id) ON DELETE CASCADE,
    rule_name       VARCHAR(255) NOT NULL,
    rule_type       VARCHAR(50) NOT NULL,
                    -- CIRCULAR_DEPENDENCY, LAYER_VIOLATION, RESTRICTED_PACKAGE, FORBIDDEN_DEPENDENCY
    description     TEXT,
    severity        VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
                    -- HIGH, MEDIUM, LOW
    configuration   JSONB,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_arch_rules_repo ON architecture_rules(repository_id);

-- Default system-wide architecture rules
INSERT INTO architecture_rules (id, repository_id, rule_name, rule_type, description, severity, configuration, enabled)
VALUES
    (uuid_generate_v4(), NULL, 'No Circular Package Dependencies', 'CIRCULAR_DEPENDENCY', 'Detects circular dependency loops between packages or classes', 'HIGH', '{}', TRUE),
    (uuid_generate_v4(), NULL, 'Controller to Repository Direct Access Violation', 'LAYER_VIOLATION', 'Controllers must communicate through Services and never directly call Repositories', 'HIGH', '{"sourcePattern": ".*Controller.*", "forbiddenTargetPattern": ".*Repository.*"}', TRUE),
    (uuid_generate_v4(), NULL, 'Domain to Web Layer Forbidden Coupling', 'LAYER_VIOLATION', 'Domain entities and repositories must not depend on web or presentation layers', 'HIGH', '{"sourcePattern": ".*entity.*", "forbiddenTargetPattern": ".*controller.*"}', TRUE);

-- ---- Architecture Violations ----
CREATE TABLE architecture_violations (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha          VARCHAR(255) NOT NULL,
    rule_id             UUID REFERENCES architecture_rules(id) ON DELETE SET NULL,
    rule_name           VARCHAR(255) NOT NULL,
    severity            VARCHAR(20) NOT NULL,
    source_symbol_id    UUID REFERENCES symbols(id) ON DELETE SET NULL,
    target_symbol_id    UUID REFERENCES symbols(id) ON DELETE SET NULL,
    description         TEXT NOT NULL,
    source_location     VARCHAR(1024),
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_arch_violations_repo_commit ON architecture_violations(repository_id, commit_sha);
CREATE INDEX idx_arch_violations_severity    ON architecture_violations(severity);

