-- ─────────────────────────────────────────────────────────────────────────────
-- V10__transactional_expiry_and_privilege_hardening.sql
--
-- Milestone 2, Increment 2: Transactional Expiry Persistence & Hardening
--
-- Remediates:
--   1. Replaces exception-raising expiry paths in confirmation stored functions
--      with structured result outcomes ('EXPIRED'), ensuring PostgreSQL transaction
--      commits successfully and permanently persists 'EXPIRED' status.
--   2. Explicitly maintains SECURITY DEFINER execution under buyer_confirmation_definer,
--      strict search_path, and least-privilege execution grants (app_worker only).
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Procedure 2: Token Exchange (with Structured Expiry Return)
DROP FUNCTION IF EXISTS public.fn_exchange_buyer_confirmation_token(VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, UUID, UUID);

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
    currency            VARCHAR(3),
    outcome_status      VARCHAR(30)
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
#variable_conflict use_column
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
    SELECT req.id, req.invoice_id, req.sme_org_id, req.status, req.expires_at
    INTO v_req
    FROM public.buyer_confirmation_requests req
    WHERE req.secure_token_hash = p_secure_token_hash;

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
    SELECT inv.id, inv.invoice_number, inv.buyer_name, inv.total_amount, inv.currency, inv.status
    INTO v_inv
    FROM public.invoices inv
    WHERE inv.id = v_req.invoice_id
    FOR UPDATE;

    -- Lock Hierarchy Step 2: Lock request SECOND and reload into v_req
    SELECT req.id, req.invoice_id, req.sme_org_id, req.status, req.expires_at
    INTO v_req
    FROM public.buyer_confirmation_requests req
    WHERE req.id = v_req.id
    FOR UPDATE;

    -- Lock Hierarchy Step 3: Lock sessions deterministically
    PERFORM 1 FROM public.buyer_confirmation_sessions WHERE request_id = v_req.id ORDER BY id ASC FOR UPDATE;

    -- Revalidate request status after acquiring locks
    IF v_req.status != 'PENDING' THEN
        RAISE EXCEPTION 'REQUEST_NOT_PENDING'
            USING HINT = 'Confirmation request is no longer pending.';
    END IF;

    -- Transaction-safe Expiry: Mutate status to EXPIRED and return cleanly so transaction COMMITS
    IF v_req.expires_at <= NOW() THEN
        UPDATE public.buyer_confirmation_sessions
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE request_id = v_req.id AND status = 'ACTIVE';

        UPDATE public.buyer_confirmation_requests
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE id = v_req.id;

        RETURN QUERY SELECT
            NULL::UUID, v_inv.id, v_req.sme_org_id, v_req.expires_at,
            v_inv.invoice_number, v_inv.buyer_name, v_inv.total_amount, v_inv.currency,
            'EXPIRED'::VARCHAR(30);
        RETURN;
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
        v_inv.invoice_number, v_inv.buyer_name, v_inv.total_amount, v_inv.currency,
        'SUCCESS'::VARCHAR(30);
END;
$$;

ALTER FUNCTION public.fn_exchange_buyer_confirmation_token(VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, UUID, UUID) OWNER TO buyer_confirmation_definer;
REVOKE ALL ON FUNCTION public.fn_exchange_buyer_confirmation_token(VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fn_exchange_buyer_confirmation_token(VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, UUID, UUID) TO app_worker;


-- 2. Procedure 3: Lease Acquisition (with Structured Expiry Return)
DROP FUNCTION IF EXISTS public.fn_acquire_session_lease(VARCHAR, VARCHAR, VARCHAR, INT);

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
    cached_body             TEXT,
    lease_outcome           VARCHAR(30)
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
#variable_conflict use_column
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
            RETURN QUERY SELECT v_sess.id, v_sess.fencing_generation, TRUE, v_sess.response_status_code, v_sess.response_body, 'REPLAY'::VARCHAR(30);
            RETURN;
        END IF;
    END IF;

    -- Session must be ACTIVE and not expired
    IF v_sess.status != 'ACTIVE' THEN
        RAISE EXCEPTION 'SESSION_INVALID'
            USING HINT = 'Confirmation session is no longer active.';
    END IF;

    -- Transaction-safe Expiry: Mutate status to EXPIRED and return cleanly so transaction COMMITS
    IF v_sess.expires_at <= NOW() THEN
        UPDATE public.buyer_confirmation_sessions SET status = 'EXPIRED', updated_at = NOW() WHERE id = v_sess.id;
        RETURN QUERY SELECT v_sess.id, v_sess.fencing_generation, FALSE, NULL::INT, NULL::TEXT, 'EXPIRED'::VARCHAR(30);
        RETURN;
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

    RETURN QUERY SELECT v_sess.id, v_new_generation, FALSE, NULL::INT, NULL::TEXT, 'SUCCESS'::VARCHAR(30);
END;
$$;

ALTER FUNCTION public.fn_acquire_session_lease(VARCHAR, VARCHAR, VARCHAR, INT) OWNER TO buyer_confirmation_definer;
REVOKE ALL ON FUNCTION public.fn_acquire_session_lease(VARCHAR, VARCHAR, VARCHAR, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fn_acquire_session_lease(VARCHAR, VARCHAR, VARCHAR, INT) TO app_worker;


-- 3. Procedure 4: Response Submission (with Structured Expiry Return)
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
#variable_conflict use_column
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

    -- Transaction-safe Expiry: Mutate status to EXPIRED and return cleanly so transaction COMMITS
    IF v_req.expires_at <= NOW() THEN
        UPDATE public.buyer_confirmation_sessions SET status = 'EXPIRED', updated_at = NOW() WHERE id = v_sess.id;
        UPDATE public.buyer_confirmation_requests SET status = 'EXPIRED', updated_at = NOW() WHERE id = v_req.id;
        RETURN QUERY SELECT NULL::UUID, 'EXPIRED'::VARCHAR(30), v_inv.status, FALSE;
        RETURN;
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

ALTER FUNCTION public.fn_submit_buyer_confirmation_response(VARCHAR, BIGINT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, VARCHAR, TEXT, JSONB, VARCHAR, INT, TEXT, TEXT, INT) OWNER TO buyer_confirmation_definer;
REVOKE ALL ON FUNCTION public.fn_submit_buyer_confirmation_response(VARCHAR, BIGINT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, VARCHAR, TEXT, JSONB, VARCHAR, INT, TEXT, TEXT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fn_submit_buyer_confirmation_response(VARCHAR, BIGINT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, VARCHAR, TEXT, JSONB, VARCHAR, INT, TEXT, TEXT, INT) TO app_worker;

-- 4. Authoritative Verification Trigger Hardening:
--    Grant SELECT on invoice_documents to buyer_confirmation_definer and establish RLS policy
--    so fn_trg_check_invoice_verified_has_confirmation can verify active document checksums.
GRANT SELECT ON public.invoice_documents TO buyer_confirmation_definer;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'invoice_documents' AND policyname = 'definer_invoice_docs_access'
    ) THEN
        CREATE POLICY definer_invoice_docs_access ON public.invoice_documents
            FOR SELECT TO buyer_confirmation_definer USING (true);
    END IF;
END $$;
