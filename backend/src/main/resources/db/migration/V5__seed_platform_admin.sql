-- ─────────────────────────────────────────────────────────────────────────────
-- V5__seed_platform_organization.sql
--
-- Seed the Platform Administration Organization.
--
-- Security rule (SECURITY_SPECIFICATION.md §2):
--   - No static, hard-coded, or predictable production administrator passwords in migrations.
--   - The platform organization is established here as an architectural tenant.
--   - The initial platform administrator account is provisioned via AdminBootstrapRunner
--     using credentials supplied from a secure environment variable or secrets manager.
-- ─────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE
    v_platform_org_id UUID := '00000000-0000-0000-0000-000000000001'::uuid;
BEGIN
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
END $$;
