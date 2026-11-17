-- ============================================================
-- V1 — Users, Roles and Security Foundation
-- ============================================================
-- This migration creates the authentication and authorisation
-- tables that underpin every other table in the system.
-- UUIDs are used as primary keys for:
--   1. Security: opaque identifiers prevent enumeration
--   2. Scalability: no central sequence contention
--   3. Eventual migration: safe for future multi-node deployments
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---- Roles ----
CREATE TABLE roles (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO roles (name, description) VALUES
    ('ROLE_USER',  'Standard authenticated user'),
    ('ROLE_ADMIN', 'Platform administrator with elevated privileges');

-- ---- Users ----
CREATE TABLE users (
    id                   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email                VARCHAR(255) NOT NULL UNIQUE,
    username             VARCHAR(100) NOT NULL UNIQUE,
    password_hash        VARCHAR(255) NOT NULL,
    display_name         VARCHAR(255),
    avatar_url           VARCHAR(1024),
    email_verified       BOOLEAN NOT NULL DEFAULT FALSE,
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    account_locked       BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts INT NOT NULL DEFAULT 0,
    last_login_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email    ON users(email);
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_enabled  ON users(enabled) WHERE enabled = TRUE;

-- ---- User-Role mapping ----
CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX idx_user_roles_role_id ON user_roles(role_id);

-- ---- Refresh tokens ----
-- Stored as hashed values; plaintext is never persisted.
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_agent  VARCHAR(512),
    ip_address  INET
);

CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_expires    ON refresh_tokens(expires_at) WHERE revoked = FALSE;

-- ---- Automatic updated_at trigger ----
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

