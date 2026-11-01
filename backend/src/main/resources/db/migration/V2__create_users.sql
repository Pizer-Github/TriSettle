-- ─────────────────────────────────────────────────────────────────────────────
-- V2__create_users.sql
-- Creates the users table.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE users (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    email               VARCHAR(255) NOT NULL,
    password_hash       TEXT         NOT NULL,
    full_name           VARCHAR(255) NOT NULL,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    is_email_verified   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_is_active ON users (is_active) WHERE is_active = TRUE;

COMMENT ON TABLE users IS 'Platform user accounts. Password stored as BCrypt hash only.';
COMMENT ON COLUMN users.password_hash IS 'BCrypt hash of the user password. Never store raw passwords.';
