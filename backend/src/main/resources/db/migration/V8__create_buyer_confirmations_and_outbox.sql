-- ─────────────────────────────────────────────────────────────────────────────
-- V8__create_buyer_confirmations_and_outbox.sql
--
-- Milestone 2, Increment 2: Buyer Confirmation Workflow
--
-- Establishes:
--   1. pgcrypto extension for cryptographic hashing and token derivation
--   2. Dedicated NOLOGIN execution role: buyer_confirmation_definer
--   3. Verified corporate debtor identity table: organization_tax_profiles
--   4. Alterations to invoices: authoritative confirmation and response tracking
--   5. Document immutability and parent-first locking triggers on invoice_documents
--   6. buyer_confirmation_requests table with composite tenant constraints
--   7. buyer_confirmation_sessions table with monotonic fencing and lease tracking
--   8. buyer_confirmation_responses table for immutable legal audit evidence
--   9. Durable transactional outbox: outbox_events with AES-256-GCM metadata
--  10. Distributed throttling table: confirmation_attempt_throttles
--  11. Executable SECURITY DEFINER procedures with strict search paths
--  12. Row-Level Security (RLS) policies and least-privilege grant matrix
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Cryptographic Extension
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 2. Dedicated Execution Role
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'buyer_confirmation_definer') THEN
        CREATE ROLE buyer_confirmation_definer WITH
            NOLOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO buyer_confirmation_definer;

-- 3. Verified Corporate Debtor Identity Table
CREATE TABLE public.organization_tax_profiles (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES public.organizations(id) ON DELETE RESTRICT,
    gstin               VARCHAR(15) NOT NULL,
    is_verified         BOOLEAN NOT NULL DEFAULT FALSE,
    verified_by_user_id UUID REFERENCES public.users(id) ON DELETE RESTRICT,
    verified_at         TIMESTAMPTZ,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_tax_profile_gstin UNIQUE (gstin),
    CONSTRAINT uq_tax_profile_org_gstin UNIQUE (org_id, gstin)
);

CREATE INDEX idx_tax_profiles_lookup ON public.organization_tax_profiles (gstin, status, is_verified);

-- 4. Invoices Table Enhancements
ALTER TABLE public.invoices
    ADD COLUMN verified_confirmation_id UUID,
    ADD COLUMN last_buyer_response_id UUID;

-- 5. Parent-First Locking & Document Immutability Trigger on invoice_documents
CREATE OR REPLACE FUNCTION public.fn_trg_lock_invoice_on_document_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_inv RECORD;
    v_target_invoice_id UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'DOCUMENT_DELETION_PROHIBITED'
            USING HINT = 'Invoice documents are immutable historical audit records and cannot be deleted.';
    END IF;

    v_target_invoice_id := NEW.invoice_id;

    -- Strict Lock Hierarchy Step 1: Acquire exclusive row lock on parent invoice FIRST
    SELECT id, status, sme_org_id
    INTO v_inv
    FROM public.invoices
    WHERE id = v_target_invoice_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'INVOICE_NOT_FOUND'
            USING HINT = 'Parent invoice does not exist.';
    END IF;

    IF v_inv.status != 'DRAFT' THEN
        RAISE EXCEPTION 'INVOICE_NOT_IN_DRAFT_STATE'
            USING HINT = 'Cannot attach, replace, or deactivate documents for invoice not in DRAFT status.';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        -- Only allow transitioning active status from TRUE to FALSE during document version replacement
        IF OLD.is_active = TRUE AND NEW.is_active = FALSE THEN
            IF NEW.id != OLD.id
               OR NEW.invoice_id != OLD.invoice_id
               OR NEW.sme_org_id != OLD.sme_org_id
               OR NEW.version != OLD.version
               OR NEW.file_name != OLD.file_name
               OR NEW.file_size_bytes != OLD.file_size_bytes
               OR NEW.mime_type != OLD.mime_type
               OR NEW.storage_path != OLD.storage_path
               OR NEW.sha256_checksum != OLD.sha256_checksum
               OR NEW.uploaded_by != OLD.uploaded_by
               OR NEW.uploaded_at != OLD.uploaded_at THEN
                RAISE EXCEPTION 'DOCUMENT_METADATA_IMMUTABLE'
                    USING HINT = 'Only is_active flag may transition from TRUE to FALSE during document deactivation.';
            END IF;
        ELSE
            RAISE EXCEPTION 'DOCUMENT_UPDATE_PROHIBITED'
                USING HINT = 'Invoice documents cannot be modified once created; only deactivation during version replacement is permitted.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_invoice_doc_lock_and_immutability ON public.invoice_documents;
CREATE TRIGGER trg_invoice_doc_lock_and_immutability
    BEFORE INSERT OR UPDATE OR DELETE ON public.invoice_documents
    FOR EACH ROW
    EXECUTE FUNCTION public.fn_trg_lock_invoice_on_document_mutation();

-- 6. Buyer Confirmation Requests Table
CREATE TABLE public.buyer_confirmation_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id          UUID NOT NULL,
    sme_org_id          UUID NOT NULL,
    buyer_org_id        UUID REFERENCES public.organizations(id) ON DELETE RESTRICT,
    buyer_gstin         VARCHAR(15) NOT NULL,
    recipient_email     VARCHAR(255) NOT NULL,
    secure_token_hash   VARCHAR(64) NOT NULL UNIQUE,
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'CONFIRMED', 'DISPUTED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    exchange_attempts   INT NOT NULL DEFAULT 0,
    throttled_until     TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ NOT NULL,
    responded_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Composite FK: Guarantees request belongs strictly to the parent invoice and SME organization
    CONSTRAINT fk_buyer_req_invoice FOREIGN KEY (invoice_id, sme_org_id)
        REFERENCES public.invoices (id, sme_org_id) ON DELETE RESTRICT,
    -- Composite Unique Constraint for child composite FK chaining
    CONSTRAINT uq_buyer_req_composite UNIQUE (id, invoice_id, sme_org_id)
);

-- Exactly one active PENDING confirmation request per invoice
CREATE UNIQUE INDEX uq_invoice_single_pending_request
    ON public.buyer_confirmation_requests (invoice_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_buyer_requests_sme ON public.buyer_confirmation_requests (sme_org_id, status);
CREATE INDEX idx_buyer_requests_expiry ON public.buyer_confirmation_requests (expires_at) WHERE status = 'PENDING';

-- 7. Buyer Confirmation Sessions Table (Database-Backed Idempotency & Monotonic Fencing)
CREATE TABLE public.buyer_confirmation_sessions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_token_hash      VARCHAR(64) NOT NULL UNIQUE,
    request_id              UUID NOT NULL,
    invoice_id              UUID NOT NULL,
    sme_org_id              UUID NOT NULL,
    auth_channel            VARCHAR(30) NOT NULL CHECK (auth_channel IN ('TOKEN_LINK', 'PORTAL_USER')),
    portal_user_id          UUID REFERENCES public.users(id) ON DELETE RESTRICT,
    buyer_org_id            UUID REFERENCES public.organizations(id) ON DELETE RESTRICT,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (status IN ('ACTIVE', 'CONSUMED', 'REVOKED', 'EXPIRED')),
    expires_at              TIMESTAMPTZ NOT NULL,
    client_ip               VARCHAR(45) NOT NULL,
    user_agent_hash         VARCHAR(64) NOT NULL,

    -- Monotonic Fencing & Idempotency Lease Fields
    fencing_generation      BIGINT NOT NULL DEFAULT 1,
    idempotency_key         VARCHAR(255),
    request_hash            VARCHAR(64),
    lease_status            VARCHAR(20) NOT NULL DEFAULT 'IDLE'
                            CHECK (lease_status IN ('IDLE', 'PROCESSING', 'SUCCESS', 'FAILED')),
    lease_expires_at        TIMESTAMPTZ,
    response_id             UUID,
    response_action         VARCHAR(30) CHECK (response_action IN ('CONFIRMED', 'DISPUTED', 'REJECTED')),
    response_status_code    INT,
    response_body           TEXT,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Composite FK: Enforces session strictly matches the parent request, invoice, and SME tenant
    CONSTRAINT fk_session_request FOREIGN KEY (request_id, invoice_id, sme_org_id)
        REFERENCES public.buyer_confirmation_requests (id, invoice_id, sme_org_id) ON DELETE RESTRICT,
    CONSTRAINT uq_session_composite UNIQUE (id, request_id, invoice_id, sme_org_id),

    -- Replay Integrity Constraint: CONSUMED session must have complete cached response
    CONSTRAINT chk_session_consumed_cache CHECK (
        (status = 'CONSUMED' AND lease_status = 'SUCCESS'
         AND response_id IS NOT NULL AND response_action IS NOT NULL
         AND response_status_code IS NOT NULL AND response_body IS NOT NULL
         AND idempotency_key IS NOT NULL AND request_hash IS NOT NULL)
        OR (status != 'CONSUMED')
    )
);

CREATE INDEX idx_sessions_lookup ON public.buyer_confirmation_sessions (session_token_hash, status);
CREATE INDEX idx_sessions_request ON public.buyer_confirmation_sessions (request_id, status);

-- 8. Buyer Confirmation Responses Table (Immutable Legal Audit Evidence)
CREATE TABLE public.buyer_confirmation_responses (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id              UUID NOT NULL UNIQUE,
    invoice_id              UUID NOT NULL,
    sme_org_id              UUID NOT NULL,
    action                  VARCHAR(30) NOT NULL CHECK (action IN ('CONFIRMED', 'DISPUTED', 'REJECTED')),
    dispute_category        VARCHAR(50) CHECK (
        dispute_category IS NULL OR dispute_category IN (
            'INVOICE_AMOUNT_MISMATCH',
            'GOODS_SERVICES_NOT_RECEIVED',
            'GOODS_DEFECTIVE_OR_DAMAGED',
            'COMMERCIAL_TERMS_DISAGREEMENT',
            'DUPLICATE_INVOICE_SUBMISSION',
            'UNAUTHORIZED_INVOICE_ISSUANCE',
            'OTHER_COMMERCIAL_DISPUTE'
        )
    ),
    notes                   TEXT,
    
    -- Trusted Context Data (Captured from server context, not caller body)
    auth_channel            VARCHAR(30) NOT NULL CHECK (auth_channel IN ('TOKEN_LINK', 'PORTAL_USER')),
    portal_user_id          UUID,
    buyer_org_id            UUID,
    claimed_email           VARCHAR(255),
    identity_assurance      VARCHAR(50) NOT NULL CHECK (identity_assurance IN ('BEARER_TOKEN_POSSESSION', 'AUTHENTICATED_CORPORATE_PORTAL')),
    client_ip               VARCHAR(45) NOT NULL,
    user_agent_hash         VARCHAR(64) NOT NULL,

    -- Immutable Canonical Evidence Record
    evidence_schema         VARCHAR(30) NOT NULL DEFAULT 'BUYER_CONFIRMATION_V1',
    canonical_evidence_text TEXT NOT NULL,
    evidence_payload        JSONB NOT NULL,
    evidence_sha256         VARCHAR(64) NOT NULL,
    confirmed_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Composite FK ensuring response strictly matches parent request, invoice, and SME tenant
    CONSTRAINT fk_buyer_resp_request FOREIGN KEY (request_id, invoice_id, sme_org_id)
        REFERENCES public.buyer_confirmation_requests (id, invoice_id, sme_org_id) ON DELETE RESTRICT,
    
    -- Composite FK for Portal User membership verification
    CONSTRAINT fk_buyer_resp_portal_user_org FOREIGN KEY (portal_user_id, buyer_org_id)
        REFERENCES public.user_organizations (user_id, org_id) ON DELETE RESTRICT,

    -- Channel Integrity Constraints
    CONSTRAINT chk_resp_portal_identity CHECK (
        (auth_channel = 'PORTAL_USER' AND portal_user_id IS NOT NULL AND buyer_org_id IS NOT NULL AND identity_assurance = 'AUTHENTICATED_CORPORATE_PORTAL')
        OR (auth_channel = 'TOKEN_LINK' AND portal_user_id IS NULL AND buyer_org_id IS NULL AND identity_assurance = 'BEARER_TOKEN_POSSESSION')
    ),
    CONSTRAINT chk_resp_dispute_reason CHECK (
        (action IN ('DISPUTED', 'REJECTED') AND dispute_category IS NOT NULL)
        OR (action = 'CONFIRMED' AND dispute_category IS NULL)
    ),
    -- Evidence Digest Mathematical Integrity
    CONSTRAINT chk_resp_evidence_sha256 CHECK (
        evidence_sha256 = encode(digest(canonical_evidence_text, 'sha256'), 'hex')
    )
);

CREATE INDEX idx_buyer_responses_invoice ON public.buyer_confirmation_responses (invoice_id);

-- Enforce Response Immutability
CREATE OR REPLACE FUNCTION public.fn_trg_buyer_resp_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'BUYER_CONFIRMATION_RESPONSE_IMMUTABLE'
        USING HINT = 'Buyer confirmation evidence records are permanently immutable and cannot be updated or deleted.';
END;
$$;

CREATE TRIGGER trg_buyer_response_immutable
    BEFORE UPDATE OR DELETE ON public.buyer_confirmation_responses
    FOR EACH ROW
    EXECUTE FUNCTION public.fn_trg_buyer_resp_immutable();

-- Authoritative Invoice Verification Trigger: VERIFIED invoices must have a cryptographically valid CONFIRMED response
CREATE OR REPLACE FUNCTION public.fn_trg_check_invoice_verified_has_confirmation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_resp RECORD;
    v_doc RECORD;
BEGIN
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

CREATE TRIGGER trg_invoice_verification_authoritative_check
    BEFORE UPDATE OF status, verified_confirmation_id ON public.invoices
    FOR EACH ROW
    EXECUTE FUNCTION public.fn_trg_check_invoice_verified_has_confirmation();

-- 9. Durable Transactional Outbox Table
CREATE TABLE public.outbox_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type          VARCHAR(100) NOT NULL,
    aggregate_type      VARCHAR(50) NOT NULL,
    aggregate_id        UUID NOT NULL,
    tenant_id           UUID NOT NULL REFERENCES public.organizations(id) ON DELETE RESTRICT,
    payload_encrypted   TEXT NOT NULL,
    key_version         INT NOT NULL DEFAULT 1,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD_LETTER')),
    retry_count         INT NOT NULL DEFAULT 0,
    max_retries         INT NOT NULL DEFAULT 5,
    scheduled_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_until       TIMESTAMPTZ,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_unprocessed ON public.outbox_events (scheduled_at, status)
    WHERE status IN ('PENDING', 'FAILED');

-- 10. Confirmation Attempt Throttles (Distributed Rate Limiting)
CREATE TABLE public.confirmation_attempt_throttles (
    client_ip           VARCHAR(45) PRIMARY KEY,
    failed_attempts     INT NOT NULL DEFAULT 0,
    throttled_until     TIMESTAMPTZ,
    last_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 11. Complete Executable Functions (SECURITY DEFINER with Fixed Search Paths)
-- ─────────────────────────────────────────────────────────────────────────────

-- Function 1: Request Issuance
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

-- Function 2: Token Exchange (Issuing 15-minute Session)
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

    -- Lock Hierarchy Step 2: Lock request SECOND
    PERFORM 1 FROM public.buyer_confirmation_requests WHERE id = v_req.id FOR UPDATE;

    -- Lock Hierarchy Step 3: Lock sessions deterministically
    PERFORM 1 FROM public.buyer_confirmation_sessions WHERE request_id = v_req.id ORDER BY id ASC FOR UPDATE;

    -- Revalidate request status and expiry
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

-- Function 3: Lease Acquisition & Fencing
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
            -- Lease expired due to worker crash: throw P0010 per Decision 1
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

-- Function 4: Response Submission
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
    SELECT id, status, buyer_gstin, sme_org_id
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

    -- Enforce Buyer Identity Chain & Verified Tax Profile
    IF v_sess.auth_channel = 'PORTAL_USER' THEN
        v_identity_assurance := 'AUTHENTICATED_CORPORATE_PORTAL';

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

    -- Apply Invoice State Transition
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

-- Function 5: Request Cancellation
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

-- Function 6: Request Resend
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

-- Function 7: Expiry Sweeper
CREATE OR REPLACE FUNCTION public.fn_mark_request_expired(p_limit INT DEFAULT 50)
RETURNS INT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    v_req RECORD;
    v_inv_id UUID;
    v_count INT := 0;
    v_locked_req RECORD;
BEGIN
    -- 1. Discover candidates without locks
    FOR v_req IN (
        SELECT id, invoice_id 
        FROM public.buyer_confirmation_requests 
        WHERE status = 'PENDING' AND expires_at <= NOW() 
        LIMIT p_limit
    ) LOOP
        -- Lock Hierarchy Step 1: Lock invoice FIRST with SKIP LOCKED
        SELECT id INTO v_inv_id 
        FROM public.invoices 
        WHERE id = v_req.invoice_id 
        FOR UPDATE SKIP LOCKED;

        IF FOUND THEN
            -- Lock Hierarchy Step 2: Lock request SECOND
            SELECT id, status, expires_at INTO v_locked_req
            FROM public.buyer_confirmation_requests
            WHERE id = v_req.id
            FOR UPDATE;

            -- Revalidate: Ensure request is still PENDING and overdue
            IF v_locked_req.status = 'PENDING' AND v_locked_req.expires_at <= NOW() THEN
                -- Lock Hierarchy Step 3: Lock sessions deterministically
                PERFORM 1
                FROM public.buyer_confirmation_sessions
                WHERE request_id = v_req.id
                ORDER BY id ASC
                FOR UPDATE;
                
                -- Mutate atomically
                UPDATE public.buyer_confirmation_sessions
                SET status = 'EXPIRED', updated_at = NOW()
                WHERE request_id = v_req.id AND status = 'ACTIVE';

                UPDATE public.buyer_confirmation_requests
                SET status = 'EXPIRED', updated_at = NOW()
                WHERE id = v_req.id;
                
                v_count := v_count + 1;
            END IF;
        END IF;
    END LOOP;

    RETURN v_count;
END;
$$;

-- Function 8: Outbox Claiming Engine
CREATE OR REPLACE FUNCTION public.fn_claim_outbox_events(
    p_batch_size    INT DEFAULT 10,
    p_lock_seconds  INT DEFAULT 60
)
RETURNS TABLE (
    event_id            UUID,
    event_type          VARCHAR(100),
    aggregate_type      VARCHAR(50),
    aggregate_id        UUID,
    tenant_id           UUID,
    payload_encrypted   TEXT,
    key_version         INT,
    retry_count         INT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    RETURN QUERY
    WITH claimed AS (
        SELECT id
        FROM public.outbox_events
        WHERE status IN ('PENDING', 'FAILED')
          AND scheduled_at <= NOW()
          AND (claimed_until IS NULL OR claimed_until < NOW())
        ORDER BY scheduled_at ASC
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    UPDATE public.outbox_events o
    SET status = 'PROCESSING',
        claimed_until = NOW() + (p_lock_seconds || ' seconds')::INTERVAL,
        updated_at = NOW()
    FROM claimed c
    WHERE o.id = c.id
    RETURNING o.id, o.event_type, o.aggregate_type, o.aggregate_id, o.tenant_id, o.payload_encrypted, o.key_version, o.retry_count;
END;
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 12. Complete Role, Grant, and RLS Policy Configuration
-- ─────────────────────────────────────────────────────────────────────────────

-- Function Permissions: Revoke PUBLIC, Grant app_worker
REVOKE ALL ON FUNCTION public.fn_issue_buyer_confirmation_request(UUID, UUID, VARCHAR, VARCHAR, TEXT, INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fn_exchange_buyer_confirmation_token(VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fn_acquire_session_lease(VARCHAR, VARCHAR, VARCHAR, INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fn_submit_buyer_confirmation_response(VARCHAR, BIGINT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, VARCHAR, TEXT, JSONB, VARCHAR, INT, TEXT, TEXT, INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fn_cancel_buyer_confirmation_request(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fn_resend_buyer_confirmation_request(UUID, UUID, VARCHAR, VARCHAR, TEXT, INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fn_mark_request_expired(INT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fn_claim_outbox_events(INT, INT) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.fn_issue_buyer_confirmation_request(UUID, UUID, VARCHAR, VARCHAR, TEXT, INT) TO app_worker;
GRANT EXECUTE ON FUNCTION public.fn_exchange_buyer_confirmation_token(VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, UUID, UUID) TO app_worker;
GRANT EXECUTE ON FUNCTION public.fn_acquire_session_lease(VARCHAR, VARCHAR, VARCHAR, INT) TO app_worker;
GRANT EXECUTE ON FUNCTION public.fn_submit_buyer_confirmation_response(VARCHAR, BIGINT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, VARCHAR, TEXT, JSONB, VARCHAR, INT, TEXT, TEXT, INT) TO app_worker;
GRANT EXECUTE ON FUNCTION public.fn_cancel_buyer_confirmation_request(UUID, UUID) TO app_worker;
GRANT EXECUTE ON FUNCTION public.fn_resend_buyer_confirmation_request(UUID, UUID, VARCHAR, VARCHAR, TEXT, INT) TO app_worker;
GRANT EXECUTE ON FUNCTION public.fn_mark_request_expired(INT) TO app_worker;
GRANT EXECUTE ON FUNCTION public.fn_claim_outbox_events(INT, INT) TO app_worker;

-- Table Grants to app_worker (Runtime Application Role)
GRANT SELECT ON public.organization_tax_profiles TO app_worker;
GRANT SELECT, INSERT, UPDATE ON public.buyer_confirmation_requests TO app_worker;
GRANT SELECT ON public.buyer_confirmation_sessions TO app_worker;
GRANT SELECT ON public.buyer_confirmation_responses TO app_worker;
GRANT SELECT, INSERT, UPDATE ON public.outbox_events TO app_worker;
GRANT SELECT, INSERT, UPDATE ON public.confirmation_attempt_throttles TO app_worker;

-- Table Grants to buyer_confirmation_definer
GRANT SELECT, UPDATE (status, rejection_reason, verified_confirmation_id, last_buyer_response_id, updated_at)
    ON public.invoices TO buyer_confirmation_definer;
GRANT SELECT, INSERT, UPDATE
    ON public.buyer_confirmation_requests TO buyer_confirmation_definer;
GRANT SELECT, INSERT, UPDATE
    ON public.buyer_confirmation_sessions TO buyer_confirmation_definer;
GRANT SELECT, INSERT
    ON public.buyer_confirmation_responses TO buyer_confirmation_definer;
GRANT SELECT (id, org_id, gstin, is_verified, status)
    ON public.organization_tax_profiles TO buyer_confirmation_definer;
GRANT SELECT (id, invoice_id, sme_org_id, sha256_checksum, is_active)
    ON public.invoice_documents TO buyer_confirmation_definer;
GRANT SELECT (id, name, org_type, is_active)
    ON public.organizations TO buyer_confirmation_definer;
GRANT SELECT (id, user_id, org_id, role, is_active)
    ON public.user_organizations TO buyer_confirmation_definer;
GRANT SELECT, INSERT, UPDATE
    ON public.outbox_events TO buyer_confirmation_definer;
GRANT SELECT, INSERT, UPDATE
    ON public.confirmation_attempt_throttles TO buyer_confirmation_definer;

-- Enable RLS on New Tables
ALTER TABLE public.organization_tax_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.buyer_confirmation_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.buyer_confirmation_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.buyer_confirmation_responses ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.outbox_events ENABLE ROW LEVEL SECURITY;

-- Scoped RLS Policies for buyer_confirmation_definer
CREATE POLICY definer_invoice_access ON public.invoices
    FOR ALL TO buyer_confirmation_definer USING (true) WITH CHECK (true);
CREATE POLICY definer_requests_access ON public.buyer_confirmation_requests
    FOR ALL TO buyer_confirmation_definer USING (true) WITH CHECK (true);
CREATE POLICY definer_sessions_access ON public.buyer_confirmation_sessions
    FOR ALL TO buyer_confirmation_definer USING (true) WITH CHECK (true);
CREATE POLICY definer_responses_access ON public.buyer_confirmation_responses
    FOR ALL TO buyer_confirmation_definer USING (true) WITH CHECK (true);
CREATE POLICY definer_outbox_access ON public.outbox_events
    FOR ALL TO buyer_confirmation_definer USING (true) WITH CHECK (true);
CREATE POLICY definer_tax_profiles_access ON public.organization_tax_profiles
    FOR ALL TO buyer_confirmation_definer USING (true) WITH CHECK (true);

-- Scoped RLS Policies for app_worker (Runtime Multi-Tenant Isolation)

-- 1. organization_tax_profiles: Visible to owning organization or Platform Admin
CREATE POLICY tax_profile_tenant_isolation ON public.organization_tax_profiles
    FOR SELECT TO app_worker
    USING (
        org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    );

-- 2. buyer_confirmation_requests: SME tenant isolation + Permissive Buyer Portal visibility
CREATE POLICY buyer_requests_sme_isolation ON public.buyer_confirmation_requests
    FOR ALL TO app_worker
    USING (
        sme_org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    )
    WITH CHECK (
        sme_org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    );

CREATE POLICY buyer_requests_portal_visibility ON public.buyer_confirmation_requests
    FOR SELECT TO app_worker
    USING (
        current_setting('app.tenant_type', true) = 'BUYER'
        AND EXISTS (
            SELECT 1 FROM public.organization_tax_profiles otp
            WHERE otp.org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
              AND otp.gstin = buyer_confirmation_requests.buyer_gstin
              AND otp.is_verified = TRUE
              AND otp.status = 'ACTIVE'
        )
    );

-- 3. buyer_confirmation_responses: Visible to SME tenant or Verified Buyer Organization
CREATE POLICY buyer_responses_tenant_isolation ON public.buyer_confirmation_responses
    FOR SELECT TO app_worker
    USING (
        sme_org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR (
            current_setting('app.tenant_type', true) = 'BUYER'
            AND EXISTS (
                SELECT 1 FROM public.organization_tax_profiles otp
                WHERE otp.org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                  AND otp.org_id = buyer_confirmation_responses.buyer_org_id
                  AND otp.is_verified = TRUE
                  AND otp.status = 'ACTIVE'
            )
        )
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    );

-- 4. outbox_events: Scoped strictly to the owning tenant
CREATE POLICY outbox_tenant_isolation ON public.outbox_events
    FOR ALL TO app_worker
    USING (
        tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    );

-- 5. invoices: Add Permissive Buyer Portal SELECT Access Policy
CREATE POLICY invoice_buyer_portal_access ON public.invoices
    FOR SELECT TO app_worker
    USING (
        current_setting('app.tenant_type', true) = 'BUYER'
        AND EXISTS (
            SELECT 1 FROM public.organization_tax_profiles otp
            WHERE otp.org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
              AND otp.gstin = invoices.buyer_gstin
              AND otp.is_verified = TRUE
              AND otp.status = 'ACTIVE'
        )
    );
