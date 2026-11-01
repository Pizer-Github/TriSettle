-- ─────────────────────────────────────────────────────────────────────────────
-- V4__enable_rls_foundation.sql
--
-- Establishes the Row-Level Security (RLS) foundation on the foundational tables.
--
-- Architecture rules (DATABASE_DESIGN.md, SECURITY_SPECIFICATION.md):
--   - app_owner (Flyway/DDL role) owns schema objects.
--   - app_worker (runtime application role) is subject to RLS.
--   - Tenant context is set via set_config('app.tenant_id', id, true) [is_local=true].
--   - No global BYPASSRLS for the application role.
--   - RLS on users and organizations (via user_organizations) enforces tenant boundaries.
--
-- IMPORTANT NOTE on scope:
--   This migration establishes RLS infrastructure on FOUNDATIONAL tables only.
--   When future modules add tables (invoices, financing_contracts, etc.), they MUST
--   add their own RLS policies in their respective migrations.
--   See IMPLEMENTATION_ROADMAP.md and DATABASE_DESIGN.md for required policy patterns.
-- ─────────────────────────────────────────────────────────────────────────────

-- Ensure the app_worker role exists so this migration is completely self-contained
-- and succeeds even when run against an empty database (e.g. during CI / testcontainers).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_worker') THEN
        CREATE ROLE app_worker WITH NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;

-- ── Grant table-level privileges to app_worker ────────────────────────────────
GRANT SELECT, INSERT, UPDATE ON organizations      TO app_worker;
GRANT SELECT, INSERT, UPDATE ON users              TO app_worker;
GRANT SELECT, INSERT, UPDATE ON user_organizations TO app_worker;

-- ── Enable RLS on foundational tables ─────────────────────────────────────────
ALTER TABLE organizations      ENABLE ROW LEVEL SECURITY;
ALTER TABLE users              ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_organizations ENABLE ROW LEVEL SECURITY;

-- ── RLS Policies: organizations ───────────────────────────────────────────────

-- Policy: app_worker can see/modify only organisations matching the transaction-local tenant_id.
CREATE POLICY org_tenant_isolation ON organizations
    FOR ALL
    TO app_worker
    USING (
        id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );

-- ── RLS Policies: users ────────────────────────────────────────────────────────

-- Policy: app_worker can see/modify users who belong to the same organisation.
CREATE POLICY user_tenant_isolation ON users
    FOR ALL
    TO app_worker
    USING (
        id IN (
            SELECT uo.user_id
            FROM user_organizations uo
            WHERE uo.org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
              AND uo.is_active = true
        )
    );

-- Additional policy: Allow select lookup during login before tenant context is set.
CREATE POLICY user_self_access ON users
    FOR SELECT
    TO app_worker
    USING (true);

-- ── RLS Policies: user_organizations ─────────────────────────────────────────

CREATE POLICY uo_tenant_isolation ON user_organizations
    FOR ALL
    TO app_worker
    USING (
        org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );

-- Allow reading memberships during login / authentication before tenant context is set.
CREATE POLICY uo_self_access ON user_organizations
    FOR SELECT
    TO app_worker
    USING (true);

-- ─────────────────────────────────────────────────────────────────────────────
-- Documentation: Policies to be expanded in future milestones
-- ─────────────────────────────────────────────────────────────────────────────
-- Milestone 2 (Invoice module):
--   ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
--   CREATE POLICY invoice_sme_isolation ON invoices FOR ALL TO app_worker
--     USING (sme_org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--            AND current_setting('app.tenant_type', true) = 'SME');
--   CREATE POLICY invoice_financier_read ON invoices FOR SELECT TO app_worker
--     USING (current_setting('app.tenant_type', true) = 'FINANCIER'
--            AND status = 'LISTED');
--   (and similar per DATABASE_DESIGN.md §1 patterns)
-- ─────────────────────────────────────────────────────────────────────────────
