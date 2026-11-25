-- Full-text ranking for code search: words of the symbol's name weigh more than words in its body (weight A vs D),
-- and identifiers are split at camelCase and punctuation (findByLastName -> find by last name, Owner#addPet -> owner add pet).
-- Measured on spring-petclinic identifier lookups: Hit@5 0.50 -> 1.00 (docs/AI_REVIEW.md).
DROP INDEX IF EXISTS idx_code_chunks_tsv;
ALTER TABLE code_chunks DROP COLUMN tsv;
ALTER TABLE code_chunks ADD COLUMN tsv TSVECTOR GENERATED ALWAYS AS (
    setweight(to_tsvector('simple', regexp_replace(regexp_replace(qualified_name, '([a-z0-9])([A-Z])', '\1 \2', 'g'),
                                                   '[^A-Za-z0-9]+', ' ', 'g')), 'A')
    || setweight(to_tsvector('simple', regexp_replace(regexp_replace(content, '([a-z0-9])([A-Z])', '\1 \2', 'g'),
                                                      '[^A-Za-z0-9]+', ' ', 'g')), 'D')) STORED;
CREATE INDEX idx_code_chunks_tsv ON code_chunks USING GIN (tsv);
