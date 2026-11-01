-- ─────────────────────────────────────────────────────────────────────────────
-- V6__create_idempotency_keys.sql
-- Idempotency key tracking table with fencing tokens and tenant-scoped RLS
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE idempotency_keys (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL,
    user_id          UUID NOT NULL,
    endpoint         VARCHAR(255) NOT NULL,
    key              VARCHAR(255) NOT NULL,
    request_hash     CHAR(64) NOT NULL,
    status           VARCHAR(20) NOT NULL CHECK (status IN ('PROCESSING', 'SUCCESS', 'FAILED')),
    fencing_token    UUID NOT NULL DEFAULT gen_random_uuid(),
    response_code    INT,
    response_body    TEXT,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Composite FK: Guarantees user belongs to tenant organization at the database level
    CONSTRAINT fk_idempotency_user_org FOREIGN KEY (user_id, tenant_id)
        REFERENCES user_organizations (user_id, org_id) ON DELETE CASCADE,

    -- Strictly scoped by tenant, authenticated user, target endpoint/operation, and client key
    CONSTRAINT uq_idempotency_scoped_key UNIQUE (tenant_id, user_id, endpoint, key)
);

CREATE INDEX idx_idempotency_lookup ON idempotency_keys (tenant_id, user_id, endpoint, key, status);
CREATE INDEX idx_idempotency_lease_expiry ON idempotency_keys (lease_expires_at) WHERE status = 'PROCESSING';

-- ── RLS for idempotency_keys ─────────────────────────────────────────────────
-- Scoped strictly to the tenant; admins have no operational need to access transient client keys
ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON idempotency_keys TO app_worker;

CREATE POLICY idempotency_tenant_isolation ON idempotency_keys
    FOR ALL TO app_worker
    USING (
        tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );
