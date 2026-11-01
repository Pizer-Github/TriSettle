-- ─────────────────────────────────────────────────────────────────────────────
-- V1__create_organizations.sql
-- Creates the organizations table.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE organizations (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    org_type    VARCHAR(20)  NOT NULL CHECK (org_type IN ('SME', 'FINANCIER', 'BUYER', 'PLATFORM')),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_organizations PRIMARY KEY (id)
);

CREATE INDEX idx_organizations_org_type ON organizations (org_type);
CREATE INDEX idx_organizations_is_active ON organizations (is_active) WHERE is_active = TRUE;

COMMENT ON TABLE organizations IS 'Tenant organisations: SMEs, Financiers, Buyers, and the Platform itself.';
COMMENT ON COLUMN organizations.org_type IS 'SME | FINANCIER | BUYER | PLATFORM';
