# DATABASE_DESIGN.md

## 1. Core RLS & Multi-Tenancy Concurrency
*   **Tenant Establishment:** Transaction-local context is strictly established using `set_config('app.tenant_type', '...', true)` and `set_config('app.tenant_id', '...', true)`.
*   **RLS Access Rules:** 
    *   `SME`: `USING (tenant_type = 'SME' AND sme_org_id = current_setting('app.tenant_id')::uuid)`
    *   `FINANCIER`: `USING (tenant_type = 'FINANCIER' AND financier_org_id = current_setting('app.tenant_id')::uuid)`
    *   `BUYER`: (If portal provided) `USING (tenant_type = 'BUYER' AND buyer_id = current_setting('app.tenant_id')::uuid)`
    *   `ADMIN`: `USING (current_setting('app.tenant_type') = 'ADMIN')` (Restricted via strict RBAC).
    *   `WORKER`: Must dynamically assume the exact `tenant_type` and `tenant_id` of the target entity before querying.

## 2. Duplicate Financing Prevention
*   **Database Constraint:** `CREATE UNIQUE INDEX idx_active_contract ON financing_contracts(invoice_id) WHERE status NOT IN ('SETTLED', 'REPAID', 'CANCELLED', 'ENCUMBRANCE_TERMINATED')`.
*   *(Note: `DEFAULTED`, `OVERDUE`, and `DISPUTED` intentionally remain out of this exclusion list so the invoice remains strictly locked).*

## 3. Webhook Deduplication & State Machine
*   **State Machine:** `RECEIVED` -> `PROCESSING` -> `RECONCILED` / `FAILED`.
*   **Deduplication:** `UNIQUE(provider_name, provider_event_id)`. Out-of-order events are handled by checking the `payment_records` monotonic version or timestamp before applying state transitions.

## 4. Ledger Immutability Triggers & Privileges
*   **Database Privileges:** The application database role is explicitly `REVOKE`d from direct `DELETE` and `UPDATE` on `ledger_lines`. All inserts must happen via controlled functions or standard ORM inserts subject to the triggers below.
*   **Journal Header Protection:** 
    *   `BEFORE UPDATE ON journal_entries`: `IF OLD.status = 'POSTED' THEN RAISE EXCEPTION 'Cannot modify a posted journal header'`.
*   **Journal Line Protection (INSERT/UPDATE/DELETE):** 
    *   The trigger must aggressively lock the parent row to prevent race conditions during concurrent posting: `SELECT status FROM journal_entries WHERE id = [target_id] FOR UPDATE`.
    *   *ON INSERT:* `IF (SELECT status ... WHERE id = NEW.journal_id FOR UPDATE) = 'POSTED' THEN RAISE EXCEPTION`.
    *   *ON UPDATE:* Must check BOTH `OLD.journal_id` and `NEW.journal_id` to prevent repointing lines between journals.
    *   *ON DELETE:* `IF (SELECT status ... WHERE id = OLD.journal_id FOR UPDATE) = 'POSTED' THEN RAISE EXCEPTION`.
*   **Atomic Posting Validation:** When updating `journal_entries.status` to `POSTED`, a `CONSTRAINT` or `BEFORE UPDATE` trigger must synchronously aggregate all child lines and assert `SUM(DEBIT) == SUM(CREDIT)` ensuring mathematical perfection at the exact microsecond of posting.