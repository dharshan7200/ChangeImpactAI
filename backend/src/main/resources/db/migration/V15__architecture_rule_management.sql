-- Architecture rule management (API): repositories can switch global default rules off for themselves, and rules
-- record when they were last changed.
ALTER TABLE architecture_rules ADD COLUMN updated_at TIMESTAMPTZ;

CREATE TABLE architecture_rule_overrides (
    repository_id UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    rule_id       UUID NOT NULL REFERENCES architecture_rules(id) ON DELETE CASCADE,
    enabled       BOOLEAN NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (repository_id, rule_id)
);
