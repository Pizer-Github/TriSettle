-- ─────────────────────────────────────────────────────────────────────────────
-- V3__create_user_organizations.sql
-- Links users to organisations with role assignments.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE user_organizations (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL,
    org_id      UUID        NOT NULL,
    role        VARCHAR(30) NOT NULL CHECK (role IN ('SME', 'FINANCIER', 'BUYER', 'ADMIN', 'PLATFORM_OPS')),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_user_organizations PRIMARY KEY (id),
    CONSTRAINT fk_uo_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_uo_org  FOREIGN KEY (org_id)  REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT uq_user_org UNIQUE (user_id, org_id)
);

CREATE INDEX idx_uo_user_id    ON user_organizations (user_id);
CREATE INDEX idx_uo_org_id     ON user_organizations (org_id);
CREATE INDEX idx_uo_active     ON user_organizations (user_id, is_active) WHERE is_active = TRUE;

COMMENT ON TABLE user_organizations IS
    'User-to-organisation membership with role. Drives RLS tenant context resolution.';
COMMENT ON COLUMN user_organizations.role IS
    'SME | FINANCIER | BUYER | ADMIN | PLATFORM_OPS — sourced from DB, never trusted from JWT alone.';
