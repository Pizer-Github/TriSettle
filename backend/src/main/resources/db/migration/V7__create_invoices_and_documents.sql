-- ─────────────────────────────────────────────────────────────────────────────
-- V7__create_invoices_and_documents.sql
-- Invoices, composite consistency, non-partial document hash uniqueness, and RLS
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE invoices (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sme_org_id           UUID NOT NULL REFERENCES organizations(id),
    buyer_name           VARCHAR(255) NOT NULL,
    buyer_gstin          VARCHAR(15) NOT NULL,
    buyer_pan            VARCHAR(10),
    invoice_number       VARCHAR(100) NOT NULL,
    invoice_date         DATE NOT NULL,
    due_date             DATE NOT NULL,
    currency             VARCHAR(3) NOT NULL DEFAULT 'INR',
    taxable_amount       NUMERIC(15, 2) NOT NULL CHECK (taxable_amount > 0),
    tax_amount           NUMERIC(15, 2) NOT NULL DEFAULT 0.00 CHECK (tax_amount >= 0),
    total_amount         NUMERIC(15, 2) NOT NULL CHECK (total_amount > 0),
    status               VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
                         CHECK (status IN ('DRAFT', 'PENDING_VERIFICATION', 'VERIFIED', 'REJECTED')),
    rejection_reason     TEXT,
    submitted_at         TIMESTAMPTZ,
    created_by           UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Exact financial balance: total must equal taxable + tax
    CONSTRAINT chk_invoices_amount_math CHECK (total_amount = taxable_amount + tax_amount),
    -- Temporal consistency check
    CONSTRAINT chk_invoices_due_date CHECK (due_date >= invoice_date),
    -- Composite unique key enabling composite foreign key referencing from child tables
    CONSTRAINT uq_invoices_id_tenant UNIQUE (id, sme_org_id),
    -- Logical duplicate prevention: unique invoice number per SME and Buyer GSTIN
    CONSTRAINT uq_sme_buyer_invoice_number UNIQUE (sme_org_id, buyer_gstin, invoice_number),

    -- Creator Tenant Integrity: Guarantees creator belongs to the SME organisation
    -- ON DELETE RESTRICT prevents accidental cascading deletion of financial records
    CONSTRAINT fk_invoices_creator_org FOREIGN KEY (created_by, sme_org_id)
        REFERENCES user_organizations (user_id, org_id) ON DELETE RESTRICT
);

CREATE INDEX idx_invoices_sme_status ON invoices (sme_org_id, status);
CREATE INDEX idx_invoices_buyer_gstin ON invoices (buyer_gstin);

-- ── Invoice Documents Table (Versioned, Composite Consistency) ───────────────
CREATE TABLE invoice_documents (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id           UUID NOT NULL,
    sme_org_id           UUID NOT NULL,
    version              INT NOT NULL DEFAULT 1 CHECK (version > 0),
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    file_name            VARCHAR(255) NOT NULL,
    file_size_bytes      BIGINT NOT NULL CHECK (file_size_bytes > 0 AND file_size_bytes <= 10485760), -- Max 10MB
    mime_type            VARCHAR(100) NOT NULL CHECK (mime_type = 'application/pdf'),
    storage_path         VARCHAR(500) NOT NULL,
    sha256_checksum      CHAR(64) NOT NULL,
    uploaded_by          UUID NOT NULL,
    uploaded_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Composite FK: Enforces that invoice_id and sme_org_id refer to the SAME invoice and tenant
    CONSTRAINT fk_invoice_documents_invoice_tenant FOREIGN KEY (invoice_id, sme_org_id)
        REFERENCES invoices (id, sme_org_id) ON DELETE RESTRICT,

    -- Uploader Tenant Integrity: Guarantees uploader belongs to the SME organisation
    CONSTRAINT fk_invoice_documents_uploader_org FOREIGN KEY (uploaded_by, sme_org_id)
        REFERENCES user_organizations (user_id, org_id) ON DELETE RESTRICT,

    -- Version uniqueness per invoice
    CONSTRAINT uq_invoice_doc_version UNIQUE (invoice_id, version),

    -- Global non-partial uniqueness across all retained active and historic document versions
    CONSTRAINT uq_global_document_sha256 UNIQUE (sha256_checksum)
);

-- Partial unique index ensuring strictly one active document per invoice
CREATE UNIQUE INDEX uq_invoice_single_active_doc ON invoice_documents (invoice_id) WHERE is_active = TRUE;

CREATE INDEX idx_invoice_documents_sme ON invoice_documents (sme_org_id);

-- ── RLS Policies ─────────────────────────────────────────────────────────────
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_documents ENABLE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE ON invoices TO app_worker;
GRANT SELECT, INSERT, UPDATE ON invoice_documents TO app_worker;

CREATE POLICY invoice_sme_isolation ON invoices
    FOR ALL TO app_worker
    USING (
        sme_org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    )
    WITH CHECK (
        sme_org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    );

CREATE POLICY invoice_doc_sme_isolation ON invoice_documents
    FOR ALL TO app_worker
    USING (
        sme_org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    )
    WITH CHECK (
        sme_org_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR current_setting('app.tenant_type', true) = 'ADMIN'
    );
