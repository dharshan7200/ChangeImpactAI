-- Phase 6 index review (pg_stat_user_indexes after indexing commons-lang, guava and petclinic, plus EXPLAIN of the
-- hot queries). Every dependency/symbol insert maintains each index, and `dependencies` is by far the largest table
-- (~1.4M rows here), so indexes that no query uses are pure write and storage cost.

-- duplicates of the indexes behind UNIQUE constraints (same leading columns)
DROP INDEX IF EXISTS idx_analysis_jobs_idempotency_key;   -- = analysis_jobs_idempotency_key_key
DROP INDEX IF EXISTS idx_source_files_repo_commit;        -- prefix of UNIQUE (repository_id, commit_sha, relative_path)

-- never used as a filter on their own: every query on these columns also filters by (repository_id, commit_sha),
-- which idx_deps_repo_commit / idx_symbols_repo_commit serve; 0–2 scans observed
DROP INDEX IF EXISTS idx_deps_target_name;
DROP INDEX IF EXISTS idx_deps_type;
DROP INDEX IF EXISTS idx_symbols_type;
