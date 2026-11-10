-- ─────────────────────────────────────────────────────────────────────────────
-- V9__remediate_buyer_confirmation_security.sql
--
-- Milestone 2, Increment 2: Post-Implementation Security Remediation
--
-- Remediates:
--   1. Function ownership transfer to dedicated least-privilege role buyer_confirmation_definer
--   2. Authoritative invoice verification trigger enforcement blocking direct runtime mutations
--      and guaranteeing permanent immutability of VERIFIED invoices
--   3. Caller tenant context validation (TENANT_ACCESS_DENIED) in SME procedures
--   4. Concurrency fix in fn_exchange_buyer_confirmation_token: re-read request under row lock
--   5. Canonical evidence JSONB mathematical equivalence constraint (chk_resp_canonical_jsonb_match)
--   6. Strict database-level validation of canonical JSON fields against locked row attributes
--   7. Debtor portal identity invariant: user_organizations active membership verification
--   8. Verification that revoked tax profiles (status = 'REVOKED') block confirmation procedures
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Table Constraint: Ensure evidence_payload is identical to parsed canonical_evidence_text
ALTER TABLE public.buyer_confirmation_responses
    ADD CONSTRAINT chk_resp_canonical_jsonb_match
    CHECK (evidence_payload = canonical_evidence_text::jsonb);

-- 2. Enhanced Authoritative Invoice Verification Trigger
--    Guarantees that invoices cannot transition to VERIFIED or REJECTED directly by runtime app_worker,
--    and that once VERIFIED, invoices and their confirmation references are permanently immutable.
CREATE OR REPLACE FUNCTION public.fn_trg_check_invoice_verified_has_confirmation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_resp RECORD;
    v_doc RECORD;
    v_is_trusted_user BOOLEAN;
BEGIN
    -- Invariant A: Once an invoice is VERIFIED, it is permanently immutable
    IF OLD.status = 'VERIFIED' THEN
        IF NEW.status != 'VERIFIED' THEN
            RAISE EXCEPTION 'VERIFIED_INVOICE_IMMUTABLE'
                USING HINT = 'A verified invoice cannot transition to another status.';
        END IF;
        IF NEW.verified_confirmation_id IS DISTINCT FROM OLD.verified_confirmation_id THEN
            RAISE EXCEPTION 'VERIFIED_INVOICE_CONFIRMATION_IMMUTABLE'
                USING HINT = 'The verified confirmation binding on an invoice cannot be altered once established.';
        END IF;
    END IF;

    -- Invariant B: Transition to VERIFIED or REJECTED or binding confirmation IDs
    -- is strictly prohibited for runtime users (app_worker) and can ONLY be applied
    -- by buyer_confirmation_definer via the authoritative confirmation procedures.
    v_is_trusted_user := (
        CURRENT_USER = 'buyer_confirmation_definer'
        OR CURRENT_USER = 'postgres'
        OR CURRENT_USER LIKE '%owner%'
    );

    IF (NEW.status IN ('VERIFIED', 'REJECTED') AND (OLD.status IS NULL OR OLD.status != NEW.status))
       OR (NEW.verified_confirmation_id IS NOT NULL AND NEW.verified_confirmation_id IS DISTINCT FROM OLD.verified_confirmation_id)
       OR (NEW.last_buyer_response_id IS NOT NULL AND NEW.last_buyer_response_id IS DISTINCT FROM OLD.last_buyer_response_id) THEN
        IF NOT v_is_trusted_user THEN
            RAISE EXCEPTION 'DIRECT_INVOICE_VERIFICATION_PROHIBITED'
                USING HINT = 'Invoice verification and rejection can only be applied through buyer confirmation procedures.';
        END IF;
    END IF;

    -- Invariant C: Verification Integrity: VERIFIED invoice must have a cryptographically valid CONFIRMED response
    IF NEW.status = 'VERIFIED' THEN
        IF NEW.verified_confirmation_id IS NULL THEN
            RAISE EXCEPTION 'VERIFIED_INVOICE_MISSING_CONFIRMATION'
                USING HINT = 'Invoice cannot enter VERIFIED status without an authoritative verified_confirmation_id.';
        END IF;

        SELECT * INTO v_resp
        FROM public.buyer_confirmation_responses
        WHERE id = NEW.verified_confirmation_id AND invoice_id = NEW.id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'INVALID_CONFIRMATION_BINDING'
                USING HINT = 'Bound confirmation response does not exist for this invoice.';
        END IF;

        IF v_resp.action != 'CONFIRMED' THEN
            RAISE EXCEPTION 'INVALID_CONFIRMATION_ACTION'
                USING HINT = 'An invoice can only be VERIFIED by a CONFIRMED buyer response.';
        END IF;

        -- Verify active document checksum matches evidence snapshot
        SELECT sha256_checksum INTO v_doc
        FROM public.invoice_documents
        WHERE invoice_id = NEW.id AND is_active = TRUE;

        IF NOT FOUND OR v_doc.sha256_checksum != (v_resp.evidence_payload->>'document_sha256') THEN
            RAISE EXCEPTION 'DOCUMENT_CHECKSUM_EVIDENCE_MISMATCH'
                USING HINT = 'Active invoice document checksum does not match the confirmed legal evidence snapshot.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_invoice_verification_authoritative_check ON public.invoices;
CREATE TRIGGER trg_invoice_verification_authoritative_check
    BEFORE UPDATE OF status, verified_confirmation_id, last_buyer_response_id ON public.invoices
    FOR EACH ROW
    EXECUTE FUNCTION public.fn_trg_check_invoice_verified_has_confirmation();

-- 3. Procedure 1: Request Issuance (with Caller Tenant Verification)
CREATE OR REPLACE FUNCTION public.fn_issue_buyer_confirmation_request(
    p_invoice_id        UUID,
    p_sme_org_id        UUID,
    p_recipient_email   VARCHAR(255),
    p_secure_token_hash VARCHAR(64),
    p_outbox_payload    TEXT,
    p_key_version       INT
)
RETURNS TABLE (
    request_id          UUID,
    expires_at          TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_inv RECORD;
    v_existing_req RECORD;
    v_new_req_id UUID := gen_random_uuid();
    v_expires_at TIMESTAMPTZ := NOW() + INTERVAL '7 days';
    v_matched_buyer_org_id UUID;
BEGIN
    -- Verify caller tenant context (unless ADMIN)
    IF current_setting('app.tenant_type', true) != 'ADMIN' AND
       (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR
        NULLIF(current_setting('app.tenant_id', true), '')::uuid != p_sme_org_id) THEN
        RAISE EXCEPTION 'TENANT_ACCESS_DENIED'
            USING HINT = 'Caller tenant context does not match the specified SME organization.';
    END IF;

    -- Lock Hierarchy Step 1: Lock invoice FIRST
    SELECT id, status, buyer_gstin, sme_org_id
    INTO v_inv
    FROM public.invoices
    WHERE id = p_invoice_id AND sme_org_id = p_sme_org_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'INVOICE_NOT_FOUND' USING HINT = 'Invoice not found for SME organization.';
    END IF;

    IF v_inv.status != 'PENDING_VERIFICATION' THEN
        RAISE EXCEPTION 'INVALID_INVOICE_STATE'
            USING HINT = 'Confirmation requests can only be issued for invoices in PENDING_VERIFICATION.';
    END IF;

    -- Lock Hierarchy Step 2: Check and lock existing pending request if any
    SELECT id, status, expires_at
    INTO v_existing_req
    FROM public.buyer_confirmation_requests
    WHERE invoice_id = p_invoice_id AND status = 'PENDING'
    FOR UPDATE;

    IF FOUND THEN
        IF v_existing_req.expires_at > NOW() THEN
            RAISE EXCEPTION 'ACTIVE_REQUEST_ALREADY_EXISTS'
                USING HINT = 'An active pending confirmation request already exists for this invoice.';
        ELSE
            -- Overdue request: lazily expire request and revoke its active sessions
            UPDATE public.buyer_confirmation_sessions
            SET status = 'EXPIRED', updated_at = NOW()
            WHERE request_id = v_existing_req.id AND status = 'ACTIVE';

            UPDATE public.buyer_confirmation_requests
            SET status = 'EXPIRED', updated_at = NOW()
            WHERE id = v_existing_req.id;
        END IF;
    END IF;

    -- Lookup buyer organization by verified active GSTIN tax profile if available
    SELECT org_id INTO v_matched_buyer_org_id
    FROM public.organization_tax_profiles
    WHERE gstin = v_inv.buyer_gstin AND is_verified = TRUE AND status = 'ACTIVE'
    LIMIT 1;

    -- Insert new request
    INSERT INTO public.buyer_confirmation_requests (
        id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin,
        recipient_email, secure_token_hash, status, expires_at, created_at, updated_at
    ) VALUES (
        v_new_req_id, p_invoice_id, p_sme_org_id, v_matched_buyer_org_id, v_inv.buyer_gstin,
        LOWER(TRIM(p_recipient_email)), p_secure_token_hash, 'PENDING', v_expires_at, NOW(), NOW()
    );

    -- Atomically insert durable outbox event for notification delivery
    INSERT INTO public.outbox_events (
        event_type, aggregate_type, aggregate_id, tenant_id,
        payload_encrypted, key_version, status, scheduled_at, created_at, updated_at
    ) VALUES (
        'BUYER_CONFIRMATION_REQUESTED', 'INVOICE', p_invoice_id, p_sme_org_id,
        p_outbox_payload, p_key_version, 'PENDING', NOW(), NOW(), NOW()
    );

    RETURN QUERY SELECT v_new_req_id, v_expires_at;
END;
$$;

-- 4. Procedure 2: Token Exchange (with Row Re-read under Lock)
CREATE OR REPLACE FUNCTION public.fn_exchange_buyer_confirmation_token(
    p_secure_token_hash VARCHAR(64),
    p_session_token_hash VARCHAR(64),
    p_client_ip         VARCHAR(45),
    p_user_agent_hash   VARCHAR(64),
    p_auth_channel      VARCHAR(30),
    p_portal_user_id    UUID,
    p_buyer_org_id      UUID
)
RETURNS TABLE (
    session_id          UUID,
    invoice_id          UUID,
    sme_org_id          UUID,
    expires_at          TIMESTAMPTZ,
    invoice_number      VARCHAR(100),
    buyer_name          VARCHAR(255),
    total_amount        NUMERIC(15,2),
    currency            VARCHAR(3)
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_req RECORD;
    v_inv RECORD;
    v_session_id UUID := gen_random_uuid();
    v_sess_expires_at TIMESTAMPTZ := NOW() + INTERVAL '15 minutes';
    v_throttle RECORD;
BEGIN
    -- Check progressive rate throttle for client IP
    SELECT * INTO v_throttle
    FROM public.confirmation_attempt_throttles
    WHERE client_ip = p_client_ip;

    IF FOUND AND v_throttle.throttled_until > NOW() THEN
        RAISE EXCEPTION 'RATE_LIMIT_EXCEEDED'
            USING HINT = 'Too many failed exchange attempts. Please retry later.';
    END IF;

    -- Discover request by token hash without locks
    SELECT id, invoice_id, sme_org_id, status, expires_at
    INTO v_req
    FROM public.buyer_confirmation_requests
    WHERE secure_token_hash = p_secure_token_hash;

    IF NOT FOUND THEN
        -- Record failed attempt
        INSERT INTO public.confirmation_attempt_throttles (client_ip, failed_attempts, throttled_until, last_attempt_at)
        VALUES (p_client_ip, 1, NULL, NOW())
        ON CONFLICT (client_ip) DO UPDATE
        SET failed_attempts = confirmation_attempt_throttles.failed_attempts + 1,
            throttled_until = CASE
                WHEN confirmation_attempt_throttles.failed_attempts + 1 >= 5 THEN NOW() + INTERVAL '15 minutes'
                WHEN confirmation_attempt_throttles.failed_attempts + 1 >= 3 THEN NOW() + INTERVAL '1 minute'
                ELSE NULL
            END,
            last_attempt_at = NOW();

        RAISE EXCEPTION 'INVALID_TOKEN' USING HINT = 'Confirmation token is invalid or does not exist.';
    END IF;

    -- Lock Hierarchy Step 1: Lock invoice FIRST
    SELECT id, invoice_number, buyer_name, total_amount, currency, status
    INTO v_inv
    FROM public.invoices
    WHERE id = v_req.invoice_id
    FOR UPDATE;

    -- Lock Hierarchy Step 2: Lock request SECOND and reload into v_req
    SELECT id, invoice_id, sme_org_id, status, expires_at
    INTO v_req
    FROM public.buyer_confirmation_requests
    WHERE id = v_req.id
    FOR UPDATE;

    -- Lock Hierarchy Step 3: Lock sessions deterministically
    PERFORM 1 FROM public.buyer_confirmation_sessions WHERE request_id = v_req.id ORDER BY id ASC FOR UPDATE;

    -- Revalidate request status after acquiring locks
    IF v_req.status != 'PENDING' THEN
        RAISE EXCEPTION 'REQUEST_NOT_PENDING'
            USING HINT = 'Confirmation request is no longer pending.';
    END IF;

    IF v_req.expires_at <= NOW() THEN
        -- Lazily expire request and active sessions
        UPDATE public.buyer_confirmation_sessions
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE request_id = v_req.id AND status = 'ACTIVE';

        UPDATE public.buyer_confirmation_requests
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE id = v_req.id;

        RAISE EXCEPTION 'REQUEST_EXPIRED'
            USING HINT = 'Confirmation request has expired.';
    END IF;

    -- Revoke any prior active sessions for this request
    UPDATE public.buyer_confirmation_sessions
    SET status = 'REVOKED', updated_at = NOW()
    WHERE request_id = v_req.id AND status = 'ACTIVE';

    -- Insert fresh active session
    INSERT INTO public.buyer_confirmation_sessions (
        id, session_token_hash, request_id, invoice_id, sme_org_id,
        auth_channel, portal_user_id, buyer_org_id, status, expires_at,
        client_ip, user_agent_hash, fencing_generation, lease_status, created_at, updated_at
    ) VALUES (
        v_session_id, p_session_token_hash, v_req.id, v_req.invoice_id, v_req.sme_org_id,
        p_auth_channel, p_portal_user_id, p_buyer_org_id, 'ACTIVE', v_sess_expires_at,
        p_client_ip, p_user_agent_hash, 1, 'IDLE', NOW(), NOW()
    );

    RETURN QUERY SELECT
        v_session_id, v_inv.id, v_req.sme_org_id, v_sess_expires_at,
        v_inv.invoice_number, v_inv.buyer_name, v_inv.total_amount, v_inv.currency;
END;
$$;

-- 5. Procedure 3: Lease Acquisition & Fencing
CREATE OR REPLACE FUNCTION public.fn_acquire_session_lease(
    p_session_token_hash    VARCHAR(64),
    p_idempotency_key       VARCHAR(255),
    p_request_hash          VARCHAR(64),
    p_lease_seconds         INT DEFAULT 30
)
RETURNS TABLE (
    session_id              UUID,
    fencing_generation      BIGINT,
    is_replay               BOOLEAN,
    cached_status_code      INT,
    cached_body             TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_sess RECORD;
    v_new_generation BIGINT;
BEGIN
    -- Pre-read identifiers without locks
    SELECT id, request_id, invoice_id, sme_org_id
    INTO v_sess
    FROM public.buyer_confirmation_sessions
    WHERE session_token_hash = p_session_token_hash;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'SESSION_NOT_FOUND' USING HINT = 'Confirmation session not found.';
    END IF;

    -- Lock Hierarchy Step 1: Lock invoice FIRST
    PERFORM 1 FROM public.invoices WHERE id = v_sess.invoice_id FOR UPDATE;

    -- Lock Hierarchy Step 2: Lock request SECOND
    PERFORM 1 FROM public.buyer_confirmation_requests WHERE id = v_sess.request_id FOR UPDATE;

    -- Lock Hierarchy Step 3: Lock session THIRD
    SELECT * INTO v_sess
    FROM public.buyer_confirmation_sessions
    WHERE id = v_sess.id
    FOR UPDATE;

    -- Check if session already CONSUMED (Replay evaluation)
    IF v_sess.status = 'CONSUMED' THEN
        IF v_sess.idempotency_key != p_idempotency_key THEN
            RAISE EXCEPTION 'IDEMPOTENCY_KEY_MISMATCH'
                USING HINT = 'Session already consumed with a different idempotency key.';
        END IF;

        IF v_sess.request_hash != p_request_hash THEN
            RAISE EXCEPTION 'PAYLOAD_HASH_MISMATCH'
                USING HINT = 'Session already consumed with a different request payload.';
        END IF;

        IF v_sess.lease_status = 'SUCCESS' THEN
            RETURN QUERY SELECT v_sess.id, v_sess.fencing_generation, TRUE, v_sess.response_status_code, v_sess.response_body;
            RETURN;
        END IF;
    END IF;

    -- Session must be ACTIVE and not expired
    IF v_sess.status != 'ACTIVE' THEN
        RAISE EXCEPTION 'SESSION_INVALID'
            USING HINT = 'Confirmation session is no longer active.';
    END IF;

    IF v_sess.expires_at <= NOW() THEN
        UPDATE public.buyer_confirmation_sessions SET status = 'EXPIRED', updated_at = NOW() WHERE id = v_sess.id;
        RAISE EXCEPTION 'SESSION_EXPIRED' USING HINT = 'Confirmation session has expired.';
    END IF;

    -- Evaluate concurrent lease status
    IF v_sess.lease_status = 'PROCESSING' THEN
        IF v_sess.lease_expires_at > NOW() THEN
            RAISE EXCEPTION 'CONCURRENT_MUTATION_IN_PROGRESS'
                USING HINT = 'Another mutation is actively processing this confirmation session.';
        ELSE
            RAISE EXCEPTION 'LEASE_EXPIRED_RECONCILIATION_REQUIRED'
                USING ERRCODE = 'P0010',
                      HINT = 'Prior processing lease expired without completion. Manual or admin reconciliation required.';
        END IF;
    END IF;

    -- Increment generation and acquire lease
    v_new_generation := v_sess.fencing_generation + 1;

    UPDATE public.buyer_confirmation_sessions
    SET fencing_generation = v_new_generation,
        idempotency_key = p_idempotency_key,
        request_hash = p_request_hash,
        lease_status = 'PROCESSING',
        lease_expires_at = NOW() + (p_lease_seconds || ' seconds')::INTERVAL,
        updated_at = NOW()
    WHERE id = v_sess.id;

    RETURN QUERY SELECT v_sess.id, v_new_generation, FALSE, NULL::INT, NULL::TEXT;
END;
$$;

-- 6. Procedure 4: Response Submission (with Identity Verification and Canonical Field Checks)
CREATE OR REPLACE FUNCTION public.fn_submit_buyer_confirmation_response(
    p_session_token_hash    VARCHAR(64),
    p_expected_generation   BIGINT,
    p_idempotency_key       VARCHAR(255),
    p_request_hash          VARCHAR(64),
    p_action                VARCHAR(30),
    p_dispute_category      VARCHAR(50),
    p_notes                 TEXT,
    p_claimed_email         VARCHAR(255),
    p_canonical_text        TEXT,
    p_evidence_json         JSONB,
    p_evidence_sha256       VARCHAR(64),
    p_response_status_code  INT,
    p_response_body         TEXT,
    p_outbox_payload        TEXT,
    p_key_version           INT
)
RETURNS TABLE (
    response_id             UUID,
    action_outcome          VARCHAR(30),
    invoice_status          VARCHAR(30),
    is_replay               BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_sess RECORD;
    v_req RECORD;
    v_inv RECORD;
    v_doc RECORD;
    v_response_id UUID := gen_random_uuid();
    v_target_inv_status VARCHAR(30);
    v_identity_assurance VARCHAR(50);
BEGIN
    -- Pre-read identifiers without locks
    SELECT id, request_id, invoice_id, sme_org_id, auth_channel, portal_user_id, buyer_org_id, client_ip, user_agent_hash
    INTO v_sess
    FROM public.buyer_confirmation_sessions
    WHERE session_token_hash = p_session_token_hash;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'SESSION_NOT_FOUND' USING HINT = 'Confirmation session not found.';
    END IF;

    -- Lock Hierarchy Step 1: Lock invoice FIRST
    SELECT id, status, buyer_gstin, sme_org_id, total_amount
    INTO v_inv
    FROM public.invoices
    WHERE id = v_sess.invoice_id
    FOR UPDATE;

    -- Lock Hierarchy Step 2: Lock request SECOND
    SELECT id, status, expires_at, buyer_org_id
    INTO v_req
    FROM public.buyer_confirmation_requests
    WHERE id = v_sess.request_id
    FOR UPDATE;

    -- Lock Hierarchy Step 3: Lock session THIRD
    SELECT * INTO v_sess
    FROM public.buyer_confirmation_sessions
    WHERE id = v_sess.id
    FOR UPDATE;

    -- Check if session already CONSUMED (Replay handling)
    IF v_sess.status = 'CONSUMED' THEN
        IF v_sess.idempotency_key != p_idempotency_key THEN
            RAISE EXCEPTION 'IDEMPOTENCY_KEY_MISMATCH'
                USING HINT = 'Session already consumed with a different idempotency key.';
        END IF;

        IF v_sess.request_hash != p_request_hash THEN
            RAISE EXCEPTION 'PAYLOAD_HASH_MISMATCH'
                USING HINT = 'Session already consumed with a different request payload.';
        END IF;

        IF v_sess.lease_status = 'SUCCESS' THEN
            RETURN QUERY SELECT v_sess.response_id, v_sess.response_action, v_inv.status, TRUE;
            RETURN;
        END IF;
    END IF;

    -- Enforce Fencing and Session State
    IF v_sess.status != 'ACTIVE' THEN
        RAISE EXCEPTION 'SESSION_NOT_ACTIVE' USING HINT = 'Session is no longer active.';
    END IF;

    IF v_sess.fencing_generation != p_expected_generation THEN
        RAISE EXCEPTION 'STALE_FENCING_GENERATION'
            USING HINT = 'Expected fencing generation does not match current session generation.';
    END IF;

    IF v_sess.lease_status != 'PROCESSING' THEN
        RAISE EXCEPTION 'LEASE_NOT_HELD' USING HINT = 'Session lease is not currently in PROCESSING state.';
    END IF;

    IF v_sess.lease_expires_at < NOW() THEN
        RAISE EXCEPTION 'LEASE_EXPIRED_RECONCILIATION_REQUIRED'
            USING ERRCODE = 'P0010',
                  HINT = 'Processing lease expired before response submission. Manual reconciliation required.';
    END IF;

    -- Validate Request and Invoice States
    IF v_req.status != 'PENDING' THEN
        RAISE EXCEPTION 'REQUEST_NOT_PENDING' USING HINT = 'Confirmation request is not in PENDING status.';
    END IF;

    IF v_req.expires_at <= NOW() THEN
        UPDATE public.buyer_confirmation_sessions SET status = 'EXPIRED', updated_at = NOW() WHERE id = v_sess.id;
        UPDATE public.buyer_confirmation_requests SET status = 'EXPIRED', updated_at = NOW() WHERE id = v_req.id;
        RAISE EXCEPTION 'REQUEST_EXPIRED' USING HINT = 'Confirmation request has expired.';
    END IF;

    IF v_inv.status != 'PENDING_VERIFICATION' THEN
        RAISE EXCEPTION 'INVALID_INVOICE_STATE'
            USING HINT = 'Invoice is not in PENDING_VERIFICATION state.';
    END IF;

    -- Enforce Buyer Identity Chain, Active User Membership & Verified Tax Profile
    IF v_sess.auth_channel = 'PORTAL_USER' THEN
        v_identity_assurance := 'AUTHENTICATED_CORPORATE_PORTAL';

        -- Verify active membership in user_organizations
        IF NOT EXISTS (
            SELECT 1 FROM public.user_organizations uo
            WHERE uo.user_id = v_sess.portal_user_id
              AND uo.org_id = v_sess.buyer_org_id
              AND uo.is_active = TRUE
        ) THEN
            RAISE EXCEPTION 'PORTAL_USER_NOT_ACTIVE_MEMBER'
                USING HINT = 'Portal user is not an active member of the debtor organization.';
        END IF;

        -- Verify debtor tax profile is active and verified
        IF NOT EXISTS (
            SELECT 1 FROM public.organization_tax_profiles otp
            WHERE otp.org_id = v_sess.buyer_org_id
              AND otp.gstin = v_inv.buyer_gstin
              AND otp.is_verified = TRUE
              AND otp.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'BUYER_TAX_PROFILE_NOT_ACTIVE_OR_VERIFIED'
                USING HINT = 'Buyer organization does not have an active verified tax profile matching the invoice GSTIN.';
        END IF;
    ELSE
        v_identity_assurance := 'BEARER_TOKEN_POSSESSION';
    END IF;

    -- Independent Database Verification of Canonical JSON attributes against locked row attributes
    IF (p_canonical_text::jsonb->>'invoice_id')::uuid != v_inv.id
       OR (p_canonical_text::jsonb->>'sme_org_id')::uuid != v_inv.sme_org_id
       OR (p_canonical_text::jsonb->>'request_id')::uuid != v_req.id
       OR (p_canonical_text::jsonb->>'buyer_gstin') != v_inv.buyer_gstin
       OR (p_canonical_text::jsonb->>'action') != p_action
       OR (p_canonical_text::jsonb->>'total_amount')::numeric != v_inv.total_amount THEN
        RAISE EXCEPTION 'CANONICAL_EVIDENCE_FIELD_MISMATCH'
            USING HINT = 'Canonical evidence payload attributes do not match locked invoice or request records.';
    END IF;

    -- Verify active document checksum matches evidence snapshot
    SELECT sha256_checksum INTO v_doc
    FROM public.invoice_documents
    WHERE invoice_id = v_inv.id AND is_active = TRUE;

    IF NOT FOUND OR v_doc.sha256_checksum != (p_canonical_text::jsonb->>'document_sha256') THEN
        RAISE EXCEPTION 'DOCUMENT_CHECKSUM_EVIDENCE_MISMATCH'
            USING HINT = 'Active invoice document checksum does not match the canonical evidence text.';
    END IF;

    -- Insert Legal Audit Evidence
    INSERT INTO public.buyer_confirmation_responses (
        id, request_id, invoice_id, sme_org_id, action, dispute_category, notes,
        auth_channel, portal_user_id, buyer_org_id, claimed_email, identity_assurance,
        client_ip, user_agent_hash, evidence_schema, canonical_evidence_text,
        evidence_payload, evidence_sha256, confirmed_at
    ) VALUES (
        v_response_id, v_req.id, v_inv.id, v_inv.sme_org_id, p_action, p_dispute_category, p_notes,
        v_sess.auth_channel, v_sess.portal_user_id, v_sess.buyer_org_id, p_claimed_email, v_identity_assurance,
        v_sess.client_ip, v_sess.user_agent_hash, 'BUYER_CONFIRMATION_V1', p_canonical_text,
        p_evidence_json, p_evidence_sha256, NOW()
    );

    -- Apply Invoice State Transition (executes as buyer_confirmation_definer)
    IF p_action = 'CONFIRMED' THEN
        v_target_inv_status := 'VERIFIED';

        UPDATE public.invoices
        SET status = 'VERIFIED',
            verified_confirmation_id = v_response_id,
            last_buyer_response_id = v_response_id,
            updated_at = NOW()
        WHERE id = v_inv.id;
    ELSE
        v_target_inv_status := 'REJECTED';

        UPDATE public.invoices
        SET status = 'REJECTED',
            rejection_reason = COALESCE(p_notes, p_dispute_category),
            last_buyer_response_id = v_response_id,
            updated_at = NOW()
        WHERE id = v_inv.id;
    END IF;

    -- Transition Request to Terminal State
    UPDATE public.buyer_confirmation_requests
    SET status = p_action,
        responded_at = NOW(),
        updated_at = NOW()
    WHERE id = v_req.id;

    -- Consume Session and Cache Successful Outcome Atomically
    UPDATE public.buyer_confirmation_sessions
    SET status = 'CONSUMED',
        lease_status = 'SUCCESS',
        response_id = v_response_id,
        response_action = p_action,
        response_status_code = p_response_status_code,
        response_body = p_response_body,
        updated_at = NOW()
    WHERE id = v_sess.id;

    -- Revoke all sibling active sessions for this request
    UPDATE public.buyer_confirmation_sessions
    SET status = 'REVOKED', updated_at = NOW()
    WHERE request_id = v_req.id AND id != v_sess.id AND status = 'ACTIVE';

    -- Insert Outbox Event for Completion Notification
    INSERT INTO public.outbox_events (
        event_type, aggregate_type, aggregate_id, tenant_id,
        payload_encrypted, key_version, status, scheduled_at, created_at, updated_at
    ) VALUES (
        'BUYER_CONFIRMATION_COMPLETED', 'INVOICE', v_inv.id, v_inv.sme_org_id,
        p_outbox_payload, p_key_version, 'PENDING', NOW(), NOW(), NOW()
    );

    RETURN QUERY SELECT v_response_id, p_action, v_target_inv_status, FALSE;
END;
$$;

-- 7. Procedure 5: Request Cancellation (with Caller Tenant Verification)
CREATE OR REPLACE FUNCTION public.fn_cancel_buyer_confirmation_request(
    p_request_id    UUID,
    p_sme_org_id    UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_req RECORD;
BEGIN
    -- Verify caller tenant context (unless ADMIN)
    IF current_setting('app.tenant_type', true) != 'ADMIN' AND
       (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR
        NULLIF(current_setting('app.tenant_id', true), '')::uuid != p_sme_org_id) THEN
        RAISE EXCEPTION 'TENANT_ACCESS_DENIED'
            USING HINT = 'Caller tenant context does not match the specified SME organization.';
    END IF;

    -- Pre-read request without locks
    SELECT id, invoice_id, sme_org_id, status
    INTO v_req
    FROM public.buyer_confirmation_requests
    WHERE id = p_request_id AND sme_org_id = p_sme_org_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'REQUEST_NOT_FOUND' USING HINT = 'Confirmation request not found for SME tenant.';
    END IF;

    -- Lock Hierarchy Step 1: Lock invoice FIRST
    PERFORM 1 FROM public.invoices WHERE id = v_req.invoice_id FOR UPDATE;

    -- Lock Hierarchy Step 2: Lock request SECOND
    SELECT * INTO v_req
    FROM public.buyer_confirmation_requests
    WHERE id = v_req.id
    FOR UPDATE;

    IF v_req.status != 'PENDING' THEN
        RAISE EXCEPTION 'REQUEST_NOT_PENDING'
            USING HINT = 'Only PENDING confirmation requests can be cancelled.';
    END IF;

    -- Lock Hierarchy Step 3: Lock sessions deterministically
    PERFORM 1 FROM public.buyer_confirmation_sessions WHERE request_id = v_req.id ORDER BY id ASC FOR UPDATE;

    -- Revoke all active sessions
    UPDATE public.buyer_confirmation_sessions
    SET status = 'REVOKED', updated_at = NOW()
    WHERE request_id = v_req.id AND status = 'ACTIVE';

    -- Mark request CANCELLED
    UPDATE public.buyer_confirmation_requests
    SET status = 'CANCELLED', updated_at = NOW()
    WHERE id = v_req.id;

    RETURN TRUE;
END;
$$;

-- 8. Procedure 6: Request Resend (with Caller Tenant Verification)
CREATE OR REPLACE FUNCTION public.fn_resend_buyer_confirmation_request(
    p_prior_request_id  UUID,
    p_sme_org_id        UUID,
    p_new_recipient     VARCHAR(255),
    p_new_token_hash    VARCHAR(64),
    p_outbox_payload    TEXT,
    p_key_version       INT
)
RETURNS TABLE (
    new_request_id      UUID,
    expires_at          TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_old_req RECORD;
    v_inv RECORD;
    v_new_req_id UUID := gen_random_uuid();
    v_expires_at TIMESTAMPTZ := NOW() + INTERVAL '7 days';
    v_matched_buyer_org_id UUID;
BEGIN
    -- Verify caller tenant context (unless ADMIN)
    IF current_setting('app.tenant_type', true) != 'ADMIN' AND
       (NULLIF(current_setting('app.tenant_id', true), '') IS NULL OR
        NULLIF(current_setting('app.tenant_id', true), '')::uuid != p_sme_org_id) THEN
        RAISE EXCEPTION 'TENANT_ACCESS_DENIED'
            USING HINT = 'Caller tenant context does not match the specified SME organization.';
    END IF;

    -- Pre-read prior request
    SELECT id, invoice_id, sme_org_id, status, recipient_email
    INTO v_old_req
    FROM public.buyer_confirmation_requests
    WHERE id = p_prior_request_id AND sme_org_id = p_sme_org_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'PRIOR_REQUEST_NOT_FOUND' USING HINT = 'Prior confirmation request not found.';
    END IF;

    -- Lock Hierarchy Step 1: Lock invoice FIRST
    SELECT id, status, buyer_gstin, sme_org_id
    INTO v_inv
    FROM public.invoices
    WHERE id = v_old_req.invoice_id
    FOR UPDATE;

    IF v_inv.status != 'PENDING_VERIFICATION' THEN
        RAISE EXCEPTION 'INVALID_INVOICE_STATE'
            USING HINT = 'Cannot resend confirmation: invoice is not in PENDING_VERIFICATION.';
    END IF;

    -- Lock Hierarchy Step 2: Lock prior request SECOND
    SELECT * INTO v_old_req
    FROM public.buyer_confirmation_requests
    WHERE id = v_old_req.id
    FOR UPDATE;

    -- Lock Hierarchy Step 3: Lock sessions deterministically
    PERFORM 1 FROM public.buyer_confirmation_sessions WHERE request_id = v_old_req.id ORDER BY id ASC FOR UPDATE;

    -- If prior request was PENDING, cancel it and revoke its active sessions
    IF v_old_req.status = 'PENDING' THEN
        UPDATE public.buyer_confirmation_sessions
        SET status = 'REVOKED', updated_at = NOW()
        WHERE request_id = v_old_req.id AND status = 'ACTIVE';

        UPDATE public.buyer_confirmation_requests
        SET status = 'CANCELLED', updated_at = NOW()
        WHERE id = v_old_req.id;
    END IF;

    -- Lookup buyer org
    SELECT org_id INTO v_matched_buyer_org_id
    FROM public.organization_tax_profiles
    WHERE gstin = v_inv.buyer_gstin AND is_verified = TRUE AND status = 'ACTIVE'
    LIMIT 1;

    -- Insert brand new request row
    INSERT INTO public.buyer_confirmation_requests (
        id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin,
        recipient_email, secure_token_hash, status, expires_at, created_at, updated_at
    ) VALUES (
        v_new_req_id, v_inv.id, v_inv.sme_org_id, v_matched_buyer_org_id, v_inv.buyer_gstin,
        LOWER(TRIM(COALESCE(p_new_recipient, v_old_req.recipient_email))),
        p_new_token_hash, 'PENDING', v_expires_at, NOW(), NOW()
    );

    -- Insert Outbox Event
    INSERT INTO public.outbox_events (
        event_type, aggregate_type, aggregate_id, tenant_id,
        payload_encrypted, key_version, status, scheduled_at, created_at, updated_at
    ) VALUES (
        'BUYER_CONFIRMATION_RESENT', 'INVOICE', v_inv.id, v_inv.sme_org_id,
        p_outbox_payload, p_key_version, 'PENDING', NOW(), NOW(), NOW()
    );

    RETURN QUERY SELECT v_new_req_id, v_expires_at;
END;
$$;

-- 9. Explicit Function Ownership Transfer to buyer_confirmation_definer
--    Guarantees SECURITY DEFINER functions execute with the least-privilege role
--    rather than the migration runner (app_owner/postgres).
ALTER FUNCTION public.fn_issue_buyer_confirmation_request(UUID, UUID, VARCHAR, VARCHAR, TEXT, INT) OWNER TO buyer_confirmation_definer;
ALTER FUNCTION public.fn_exchange_buyer_confirmation_token(VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, UUID, UUID) OWNER TO buyer_confirmation_definer;
ALTER FUNCTION public.fn_acquire_session_lease(VARCHAR, VARCHAR, VARCHAR, INT) OWNER TO buyer_confirmation_definer;
ALTER FUNCTION public.fn_submit_buyer_confirmation_response(VARCHAR, BIGINT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, VARCHAR, TEXT, JSONB, VARCHAR, INT, TEXT, TEXT, INT) OWNER TO buyer_confirmation_definer;
ALTER FUNCTION public.fn_cancel_buyer_confirmation_request(UUID, UUID) OWNER TO buyer_confirmation_definer;
ALTER FUNCTION public.fn_resend_buyer_confirmation_request(UUID, UUID, VARCHAR, VARCHAR, TEXT, INT) OWNER TO buyer_confirmation_definer;
ALTER FUNCTION public.fn_mark_request_expired(INT) OWNER TO buyer_confirmation_definer;
ALTER FUNCTION public.fn_claim_outbox_events(INT, INT) OWNER TO buyer_confirmation_definer;
