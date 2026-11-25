-- Which version of the symbol/dependency extraction produced a commit's rows. Incremental analysis copies rows of
-- unchanged files from an already analysed commit, so it may only build on commits of the current version — otherwise
-- results of an older analyser (e.g. before the JavaParser overload fixes) would be carried forward. Rows that existed
-- before this column get 0 ("unknown"); they stay valid for the reports that reference them.
ALTER TABLE source_files ADD COLUMN analyzer_version INTEGER NOT NULL DEFAULT 0;
