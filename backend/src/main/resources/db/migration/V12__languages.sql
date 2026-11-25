-- Multi-language analysis (P6-7): the language of every analysed source file, and which languages each commit was
-- analysed for (a commit analysed before a language was enabled is re-analysed instead of showing all of that
-- language's symbols as "added").
ALTER TABLE source_files ADD COLUMN language VARCHAR(20) NOT NULL DEFAULT 'java';

CREATE TABLE analysed_commit_languages (
    repository_id UUID         NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    commit_sha    VARCHAR(64)  NOT NULL,
    languages     VARCHAR(20)[] NOT NULL,
    analysed_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (repository_id, commit_sha)
);
