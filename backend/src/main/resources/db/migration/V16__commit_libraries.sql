-- Library classpath an analysed commit was parsed with (dependency jars from its Maven/Gradle build).
-- The fingerprint identifies the exact artifact set: incremental analysis only reuses rows from a commit with the
-- same fingerprint, because resolution of unchanged files depends on the classpath.
CREATE TABLE commit_libraries (
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha    VARCHAR(255) NOT NULL,
    status        VARCHAR(20) NOT NULL,      -- RESOLVED, PARTIAL, FAILED, NO_BUILD_FILE, DISABLED
    build_system  VARCHAR(20),               -- MAVEN, GRADLE
    fingerprint   VARCHAR(64) NOT NULL,
    jar_count     INTEGER NOT NULL DEFAULT 0,
    total_bytes   BIGINT NOT NULL DEFAULT 0,
    artifacts     JSONB NOT NULL DEFAULT '[]',
    message       TEXT,
    resolve_ms    BIGINT,
    resolved_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (repository_id, commit_sha)
);
