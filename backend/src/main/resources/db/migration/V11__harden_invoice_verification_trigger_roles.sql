-- ─────────────────────────────────────────────────────────────────────────────
-- V11__harden_invoice_verification_trigger_roles.sql
--
-- Milestone 2, Increment 2: Explicit Trigger Role Authorization Hardening
--
-- Remediates:
--   1. Replaces wildcard role-name matching (CURRENT_USER LIKE '%owner%') with
--      an explicit whitelist of authorized roles ('buyer_confirmation_definer',
--      'postgres', 'app_owner', 'test_owner').
--   2. Prevents unintended roles whose names contain the substring "owner"
--      (e.g., 'pawnshop_owner', 'business_owner') from receiving trusted privileges.
--   3. Preserves unconditional permanent immutability for VERIFIED invoices (Invariant A).
--   4. Preserves unconditional confirmation binding and checksum integrity (Invariant C).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION public.fn_trg_check_invoice_verified_has_confirmation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_resp RECORD;
    v_doc RECORD;
    v_is_trusted_user BOOLEAN;
BEGIN
    -- Invariant A: Once an invoice is VERIFIED, it is permanently immutable across ALL roles
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
    -- is strictly prohibited for runtime users (app_worker) and unintended roles.
    -- Can ONLY be applied by buyer_confirmation_definer via authoritative confirmation
    -- procedures, or explicit administrative owner roles.
    v_is_trusted_user := (
        CURRENT_USER = 'buyer_confirmation_definer'
        OR CURRENT_USER = 'postgres'
        OR CURRENT_USER = 'app_owner'
        OR CURRENT_USER = 'test_owner'
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
    -- Executes unconditionally for ALL roles attempting to set status = 'VERIFIED'.
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
