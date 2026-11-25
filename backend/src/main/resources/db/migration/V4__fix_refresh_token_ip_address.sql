-- ============================================================
-- V4 — Fix Refresh Token IP Address Column Type
-- ============================================================
-- Changes ip_address from INET to VARCHAR(45) so Hibernate can
-- bind Java String parameters without requiring PostgreSQL-specific
-- PGobject or inet cast expressions. 45 characters accommodates
-- full IPv6 representation (e.g. 0000:0000:0000:0000:0000:0000:0000:0001).
-- ============================================================

ALTER TABLE refresh_tokens ALTER COLUMN ip_address TYPE VARCHAR(45);

