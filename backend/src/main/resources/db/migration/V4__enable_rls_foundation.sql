-- ─────────────────────────────────────────────────────────────────────────────
-- V4__enable_rls_foundation.sql
--
-- Establishes the Row-Level Security (RLS) foundation on the foundational tables.
--
-- Architecture rules (DATABASE_DESIGN.md, SECURITY_SPECIFICATION.md):
--   - app_owner (Flyway/DDL role) owns schema objects and DEFINER functions.
--   - app_worker (runtime application role) is strictly subject to RLS (NO BYPASSRLS).
--   - Tenant context is set via set_config('app.tenant_id', id, true) [is_local=true].
--   - Strict failure closed: missing app.tenant_id context returns 0 rows.
--   - No blanket USING (true) policies.
--   - Narrowly scoped SECURITY DEFINER functions handle pre-authentication lookups.
-- ─────────────────────────────────────────────────────────────────────────────

-- Ensure the app_worker role exists so this migration is completely self-contained
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_worker') THEN
        CREATE ROLE app_worker WITH NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END
$$;

-- ── Grant table-level DML privileges to app_worker ────────────────────────────
GRANT SELECT, INSERT, UPDATE ON organizations      TO app_worker;
GRANT SELECT, INSERT, UPDATE ON users              TO app_worker;
GRANT SELECT, INSERT, UPDATE ON user_organizations TO app_worker;

-- ── Enable RLS on foundational tables ─────────────────────────────────────────
ALTER TABLE organizations      ENABLE ROW LEVEL SECURITY;
ALTER TABLE users              ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_organizations ENABLE ROW LEVEL SECURITY;

-- ── RLS Policies: organizations ───────────────────────────────────────────────
-- Strictly scoped to the current tenant ID, or platform administrators.
CREATE POLICY org_tenant_isolation ON organizations
    FOR ALL
    TO app_worker
    USING (
        id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    );

-- ── RLS Policies: user_organizations ─────────────────────────────────────────
-- Scoped to the active tenant organisation or platform administrators.
CREATE POLICY uo_tenant_isolation ON user_organizations
    FOR ALL
    TO app_worker
    USING (
        org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    );

-- ── RLS Policies: users ────────────────────────────────────────────────────────
-- Scoped to users belonging to the active tenant organisation, or platform administrators.
CREATE POLICY user_tenant_isolation ON users
    FOR ALL
    TO app_worker
    USING (
        id IN (
            SELECT uo.user_id
            FROM user_organizations uo
            WHERE uo.org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
              AND uo.is_active = TRUE
        )
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    );

-- ── Controlled Authentication Lookups (SECURITY DEFINER) ─────────────────────
-- Solves the pre-authentication bootstrap problem without granting RLS bypass.
-- Functions execute with owner privileges, restrict search_path, and return only
-- the exact record matching the specific queried identifier.

-- 1. Lookup user by email for login credential verification
CREATE OR REPLACE FUNCTION auth_lookup_user_by_email(p_email VARCHAR)
RETURNS TABLE (
    user_id           UUID,
    email             VARCHAR,
    password_hash     TEXT,
    full_name         VARCHAR,
    is_active         BOOLEAN,
    is_email_verified BOOLEAN,
    org_id            UUID,
    org_name          VARCHAR,
    org_type          VARCHAR,
    role              VARCHAR
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN QUERY
    SELECT 
        u.id AS user_id,
        u.email,
        u.password_hash,
        u.full_name,
        u.is_active,
        u.is_email_verified,
        o.id AS org_id,
        o.name AS org_name,
        o.org_type::VARCHAR,
        uo.role
    FROM users u
    JOIN user_organizations uo ON uo.user_id = u.id AND uo.is_active = TRUE
    JOIN organizations o ON o.id = uo.org_id AND o.is_active = TRUE
    WHERE u.email = LOWER(TRIM(p_email))
      AND u.is_active = TRUE
    LIMIT 1;
END;
$$;

REVOKE ALL ON FUNCTION auth_lookup_user_by_email(VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_lookup_user_by_email(VARCHAR) TO app_worker;

-- 2. Lookup user by ID for JWT token re-validation in JwtAuthFilter
CREATE OR REPLACE FUNCTION auth_lookup_user_by_id(p_user_id UUID)
RETURNS TABLE (
    user_id           UUID,
    email             VARCHAR,
    password_hash     TEXT,
    full_name         VARCHAR,
    is_active         BOOLEAN,
    is_email_verified BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN QUERY
    SELECT 
        u.id AS user_id,
        u.email,
        u.password_hash,
        u.full_name,
        u.is_active,
        u.is_email_verified
    FROM users u
    WHERE u.id = p_user_id
      AND u.is_active = TRUE
    LIMIT 1;
END;
$$;

REVOKE ALL ON FUNCTION auth_lookup_user_by_id(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_lookup_user_by_id(UUID) TO app_worker;

-- 3. Controlled SME Self-Registration Function
-- Creates SME organization and user atomically without allowing role tampering.
CREATE OR REPLACE FUNCTION auth_register_sme(
    p_email         VARCHAR,
    p_password_hash TEXT,
    p_full_name     VARCHAR,
    p_org_name      VARCHAR
)
RETURNS TABLE (
    user_id UUID,
    org_id  UUID,
    role    VARCHAR
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_org_id  UUID := gen_random_uuid();
    v_user_id UUID := gen_random_uuid();
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE email = LOWER(TRIM(p_email))) THEN
        RAISE EXCEPTION 'USER_ALREADY_EXISTS';
    END IF;

    -- Enforce SME organization type
    INSERT INTO organizations (id, name, org_type, is_active, created_at, updated_at)
    VALUES (v_org_id, TRIM(p_org_name), 'SME', TRUE, NOW(), NOW());

    -- Create user
    INSERT INTO users (id, email, password_hash, full_name, is_active, is_email_verified, created_at, updated_at)
    VALUES (v_user_id, LOWER(TRIM(p_email)), p_password_hash, TRIM(p_full_name), TRUE, FALSE, NOW(), NOW());

    -- Enforce SME membership role
    INSERT INTO user_organizations (id, user_id, org_id, role, is_active, created_at)
    VALUES (gen_random_uuid(), v_user_id, v_org_id, 'SME', TRUE, NOW());

    RETURN QUERY SELECT v_user_id, v_org_id, 'SME'::VARCHAR;
END;
$$;

REVOKE ALL ON FUNCTION auth_register_sme(VARCHAR, TEXT, VARCHAR, VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_register_sme(VARCHAR, TEXT, VARCHAR, VARCHAR) TO app_worker;
