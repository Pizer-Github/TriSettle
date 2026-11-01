-- ─────────────────────────────────────────────────────────────────────────────
-- V5__seed_platform_admin.sql
--
-- Controlled Platform Administrator Provisioning.
--
-- Security rule (SECURITY_SPECIFICATION.md §2):
--   - Public self-service registration cannot provision PLATFORM or ADMIN roles.
--   - Administrative access is provisioned through controlled database migrations
--     or authenticated administrative workflows.
--
-- Note: Default development password is BCrypt hash of "Admin@Secure2026!"
-- In production, the password hash is updated through secret management workflows.
-- ─────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE
    v_platform_org_id UUID := '00000000-0000-0000-0000-000000000001'::uuid;
    v_admin_user_id   UUID := '00000000-0000-0000-0000-000000000002'::uuid;
    -- BCrypt hash of "Admin@Secure2026!" with 12 rounds
    v_password_hash   TEXT := '$2a$12$L7pY34Z21v7fNqZ0H5GZMeiJ.u8eZqvM2G7bW4DkK/zB9D4IuYnOa';
BEGIN
    -- Seed Platform Organization
    IF NOT EXISTS (SELECT 1 FROM organizations WHERE id = v_platform_org_id) THEN
        INSERT INTO organizations (id, name, org_type, is_active, created_at, updated_at)
        VALUES (
            v_platform_org_id,
            'Platform Administration',
            'PLATFORM',
            TRUE,
            NOW(),
            NOW()
        );
    END IF;

    -- Seed Platform Administrator User
    IF NOT EXISTS (SELECT 1 FROM users WHERE id = v_admin_user_id) THEN
        INSERT INTO users (id, email, password_hash, full_name, is_active, is_email_verified, created_at, updated_at)
        VALUES (
            v_admin_user_id,
            'admin@platform.local',
            v_password_hash,
            'System Administrator',
            TRUE,
            TRUE,
            NOW(),
            NOW()
        );
    END IF;

    -- Seed Admin Membership
    IF NOT EXISTS (SELECT 1 FROM user_organizations WHERE user_id = v_admin_user_id AND org_id = v_platform_org_id) THEN
        INSERT INTO user_organizations (id, user_id, org_id, role, is_active, created_at)
        VALUES (
            gen_random_uuid(),
            v_admin_user_id,
            v_platform_org_id,
            'ADMIN',
            TRUE,
            NOW()
        );
    END IF;
END $$;
