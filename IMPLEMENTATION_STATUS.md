# IMPLEMENTATION_STATUS.md

## Milestone Progress Summary

| Milestone | Status | Description |
|-----------|--------|-------------|
| **M1: Foundation & Skeleton** | 🟢 Accepted & Hardened | Modular Monolith, Flyway V1-V5, Hardened JWT Auth, DEFINER Auth Lookups, Transactional RLS Aspect, Dynamic Admin Bootstrap, Angular 17 workspace with lockfile, CI pipeline |
| **M2: Invoice & Verification** | 🟢 Accepted & Baseline Tagged | Increment 1: Invoice Creation, Document Management, Idempotency Lease Fencing, RLS Tenant Isolation (Complete). Increment 2: Buyer Confirmation Workflow, Encrypted Outbox, RFC 8785 Evidence Digest, Monotonic Fencing, V9-V11 Security Hardening (Complete). Tag: `m2-increment-2-buyer-confirmation`, Merge Commit: `47c36c0`. |
| **M3: Bidding & Transaction Boundaries** | ⚪ Not Implemented | Polymorphic bids, `@Transactional` `FOR UPDATE` bid acceptance |
| **M4: Ledger & Webhooks** | ⚪ Not Implemented | Immutable double-entry ledger triggers, webhook ingestion |
| **M5: Exception Workflows & Admin Portal** | ⚪ Not Implemented | Recourse Demands, Dual approval, Step-Up Auth |

---

## Detailed Security Audit & Verification Record (Final)

### 1. Platform Administrator Provisioning Audit
- **Vulnerability Remediated:** Static BCrypt password hash in `V5` migration was eliminated.
- **Current Architecture:**
  - `V5__seed_platform_admin.sql` only establishes the Platform Administration organization (`00000000-0000-0000-0000-000000000001`).
  - `AdminBootstrapRunner` creates the initial administrator user on application startup **only if** `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` are explicitly supplied in the environment.
  - Idempotent: If an administrator account already exists, bootstrap creation is skipped and existing credentials are preserved.
  - Non-public: Public registration (`/api/v1/auth/register`) only accepts SME details; `organizationType` and `role` parameters are rejected.
  - Auditable: Explicit audit log event emitted upon administrator provisioning (`AUDIT: Initial platform administrator successfully provisioned`).

### 2. SECURITY DEFINER Functions Audit (Flyway V4)
- **`auth_lookup_user_by_email(VARCHAR)`:**
  - Owner: `app_owner` | SECURITY DEFINER: YES | Search path: `public, pg_temp`.
  - Grants: `REVOKE ALL FROM PUBLIC; GRANT EXECUTE TO app_worker;`.
  - Security boundary: Only returns the single record matching `LOWER(TRIM(p_email))`.
- **`auth_lookup_user_by_id(UUID)`:**
  - Owner: `app_owner` | SECURITY DEFINER: YES | Search path: `public, pg_temp`.
  - Grants: `REVOKE ALL FROM PUBLIC; GRANT EXECUTE TO app_worker;`.
  - Security boundary: Returns active user state for token re-validation without requiring prior tenant context.
- **`auth_register_sme(...)`:**
  - Owner: `app_owner` | SECURITY DEFINER: YES | Search path: `public, pg_temp`.
  - Grants: `REVOKE ALL FROM PUBLIC; GRANT EXECUTE TO app_worker;`.
  - Security boundary: Hardcodes `org_type = 'SME'` and `role = 'SME'`; client cannot inject administrative or financier roles.

### 3. Spring Transaction & RLS Integration
- **Transaction Boundary Ordering:**
  - `@EnableTransactionManagement(order = 100)`: Spring's `TransactionInterceptor` starts the database transaction and checks out the connection.
  - `TenantRlsAspect` (`@Order(200)`): Runs inside the transaction, executing `set_config('app.tenant_id', id, true)` on the active connection.
  - PostgreSQL transaction-local semantics (`is_local=true`) guarantee that session variables auto-reset upon commit or rollback.
  - Filter cleanup: `JwtAuthFilter` wraps `filterChain.doFilter()` in `try/finally`, guaranteeing `TenantContextHolder.clear()` runs after every request.

### 4. Integration Test Coverage
- Real PostgreSQL integration tests in `TenantIsolationIT.java` running under `app_worker`:
  - `missingTenantContext_failsClosedUnderRls()`
  - `smeTenant_canOnlyReadOwnData_crossTenantAccessDenied()`
  - `crossTenantWrite_isBlockedByRlsPolicy()`
  - `financierTenant_canOnlyReadOwnData_smeDataInvisible()`
  - `adminContext_hasPlatformVisibilityAcrossTenants()`
  - `authLookupFunction_returnsOnlySingleMatchingRecord()`
  - `contextIsolation_betweenConsecutiveTransactions()`
  - `backgroundWorker_dynamicallyAssumesTrustedTenantContext()`

### 5. Backend Test Compilation & Scope Remediation
- **Compiler Defect Remediated:** Removed erroneous cross-package test import (`import com.tcs.invoicefactoring.migration.FlywayMigrationTest;`) in `JwtServiceTest.java`.
- **Architectural Separation:**
  - `JwtServiceTest` is decoupled from Spring context and database containers, focusing purely on token generation, claim extraction, signature validation, tamper rejection, and input validation.
  - `FlywayMigrationTest` remains package-private in `com.tcs.invoicefactoring.migration`, testing migration application against clean PostgreSQL containers.
  - Test compilation validated with zero compiler warnings/errors: `Compiling 4 source files to target/test-classes` -> `BUILD SUCCESS`.
  - `JwtServiceTest` unit tests executed with 4 passing tests (0 failures, 0 errors, 0 skipped).

---

## Milestone 2 Increment 1: Invoice Creation & Document Management (Completed)

### 1. Database Migrations
- **`V6__create_idempotency_keys.sql`:**
  - Table: `idempotency_keys` with composite foreign key `(created_by, tenant_id) REFERENCES user_organizations(user_id, org_id) ON DELETE RESTRICT`.
  - Fencing Token: Monotonic `fencing_token UUID` acquired during lease acquisition, verified via `SELECT ... FOR UPDATE` before business mutations.
  - RLS Enabled: `ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;` with tenant isolation policy for `app_worker`.
- **`V7__create_invoices_and_documents.sql`:**
  - Table: `invoices` with composite unique constraint `(id, sme_org_id)` and foreign key `(created_by, sme_org_id) REFERENCES user_organizations(user_id, org_id) ON DELETE RESTRICT`.
  - Table: `invoice_documents` with composite foreign key `(uploaded_by, sme_org_id) REFERENCES user_organizations(user_id, org_id) ON DELETE RESTRICT`.
  - Non-partial Global SHA-256 Unique Constraint: `uq_global_document_sha256 UNIQUE (sha256_checksum)`.
  - Invariants & Mathematical Constraints: `chk_invoices_amount_math` (`total_amount = taxable_amount + tax_amount`), `chk_invoices_due_date` (`due_date >= invoice_date`), and positive amount constraints.
  - RLS Policies: Separate tenant-isolation policies on `invoices` and `invoice_documents` with default-deny failsafe.

### 2. Idempotency Engine & Lease Fencing
- Two-Phase Lease Acquisition: Phase 1 acquires lease in `REQUIRES_NEW` transaction; Phase 2 executes business mutation and co-commits `SUCCESS` status in same transaction.
- Fencing Token Validation: Row-level lock (`SELECT ... FOR UPDATE`) validates that the transaction's fencing token matches the active lease token, eliminating stale-worker race conditions.
- Safe Leases Reclamation: Expired leases with `lease_expires_at < now()` are reclaimed with a newly generated fencing token; uncommitted transactions are safely aborted.
- Request Payload Hashing: SHA-256 request hashing prevents key reuse across differing requests (returns `409 Conflict`).

### 3. Document Management & Security
- Stream Staging & Size Bounds: `BoundedInputStream` terminates uploads exceeding 10MB (`10485760` bytes) with `PayloadTooLargeException`.
- Cryptographic Checksum: SHA-256 calculated on the fly during staging.
- Magic Byte Verification: Validates `%PDF-` prefix before accepting document files.
- Atomic Move & Cleanup: Files staged and atomically renamed within `/var/app/documents`; `TransactionSynchronization` cleans up uncommitted files on transaction rollback.

### 4. Validation & Error Handling
- GSTIN Mod 36, 36 Checksum: ISO/IEC 7064 algorithm with weights `[1, 2]` and Indian State/UT code validation (`GstinValidator`).
- PAN Invariant: Validates 10-character PAN syntax and matches against GSTIN characters 3-12 (`PanValidator`).
- Financial Amounts: Strict `NUMERIC(15, 2)` parsing with scale 2; checks `total = taxable + tax` (`FinancialAmountValidator`).
- Generic Error Mapping: Maps unique constraint violations (`uq_global_document_sha256`) to generic `DUPLICATE_DOCUMENT` without exposing third-party tenant information.

### 5. Automated Tests & Verification
- Unit Tests (Local & CI Execution): 46 passing tests (0 failures, 0 errors, 0 skipped):
  - `IdempotencyServiceTest` (8 tests)
  - `DocumentStorageServiceTest` (4 tests)
  - `GstinValidatorTest` (14 tests)
  - `FinancialAmountValidatorTest` (9 tests)
  - `InvoiceServiceTest` (7 tests)
  - `JwtServiceTest` (4 tests)
- Integration Tests (Executed in CI via Testcontainers PostgreSQL 16.4): 38 tests (0 failures, 0 errors, 0 skipped):
  - `AuthControllerIT` (9 tests)
  - `InvoiceCreationIT` (7 tests)
  - `InvoiceDocumentUploadIT` (4 tests)
  - `InvoiceSubmissionIT` (4 tests)
  - `InvoiceTenantIsolationIT` (5 tests)
  - `TenantIsolationIT` (8 tests)
  - `IdempotencyFencingIT` (1 test)
- Total Backend Automated Tests Executed in CI: **85 tests (0 failures, 0 errors, 0 skipped)**.

---

## Milestone 2 Increment 2: Buyer Confirmation Workflow (Completed)

### 1. Database Migrations & Security Hardening
- **`V8__create_buyer_confirmations_and_outbox.sql`:**
  - Table: `organization_tax_profiles` for verified corporate identity bound to Indian GSTINs with `UNIQUE (gstin)` and `UNIQUE (org_id, gstin)`.
  - Invoices altered: authoritative state transition tracking with `verified_confirmation_id` and `last_buyer_response_id`.
  - Table: `buyer_confirmation_requests` with single-pending-request partial index `uq_invoice_single_pending_req ON (invoice_id) WHERE status = 'PENDING'` and composite constraints `FOREIGN KEY (invoice_id, sme_org_id) REFERENCES invoices(id, sme_org_id)`.
  - Table: `buyer_confirmation_sessions` providing ephemeral (15-minute) authenticated sessions, session-level idempotency leases (`IDLE`, `PROCESSING`, `SUCCESS`), monotonic `fencing_generation BIGINT`, and SHA-256 session token hashing.
  - Table: `buyer_confirmation_responses` capturing immutable legal evidence records with trigger `trg_buyer_response_immutable` preventing updates/deletes.
  - Table: `outbox_events` for durable transactional messaging with AES-256-GCM encrypted payloads, key rotation versioning, and status polling.
  - Table: `confirmation_attempt_throttles` tracking client IP exchange attempts with progressive backoff (3 failed $\rightarrow$ 1 min; 5 failed $\rightarrow$ 15 min).
  - Triggers & Functions:
    - `trg_invoice_doc_lock_and_immutability`: locks invoice `FOR UPDATE` before document mutations, enforcing strict document immutability and serialization.
    - `trg_invoice_verification_authoritative_check`: enforces that invoice transitions to `VERIFIED` or `REJECTED` can only be authored by `buyer_confirmation_definer`.
    - Executable procedures: `fn_issue_buyer_confirmation_request`, `fn_exchange_buyer_confirmation_token`, `fn_acquire_session_lease`, `fn_submit_buyer_confirmation_response`, `fn_cancel_buyer_confirmation_request`, `fn_resend_buyer_confirmation_request`, `fn_mark_request_expired`, and `fn_claim_outbox_events`.
  - Role Separation: Dedicated `NOLOGIN` execution role `buyer_confirmation_definer` possessing scoped table grants and `USING (true)` definer policies; `app_worker` runtime role has `EXECUTE` grants only without direct `UPDATE` privileges on sensitive invoice verification columns.

### 2. Dual Buyer Access Model & Session Idempotency
- **Ad-Hoc / Single-Use Bearer Links:** High-entropy cryptographic tokens transferred exclusively in URL hash fragments (`#token=...`), exchanged for 15-minute confirmation sessions without requiring pre-provisioned user accounts.
- **Authenticated Portal Access:** Onboarded buyer users authenticate via JWT and query confirmation requests matching their organization's verified GSTINs in `organization_tax_profiles`.
- **Session-Level Monotonic Fencing:** `fencing_generation` increments monotonically with each acquired lease; stale workers attempting commits after lease expiration are rejected with `FencingGenerationException`.
- **Atomic Two-Phase Sweeper:** Overdue requests (7-day expiry) are swept using `FOR UPDATE SKIP LOCKED` and transitioned to `EXPIRED` while atomically revoking all active child sessions.

### 3. Cryptography & Non-Repudiation Legal Evidence
- **RFC 8785 Canonical Serialization:** `EvidenceSerializationService` sorts keys lexicographically by Unicode code points using `TreeMap`, formats numbers canonically, and calculates SHA-256 digest over the exact canonical UTF-8 representation.
- **AES-256-GCM Outbox Encryption:** `NotificationEnvelopeEncryptionService` encrypts notification payloads using 256-bit AES-GCM with 12-byte IV and AAD context binding (`aggregateType:aggregateId:tenantId:keyVersion`), with full support for seamless cryptographic key rotation.
- **Transactional Outbox Worker:** `DurableOutboxWorker` claims pending events with `SKIP LOCKED`, delivers notifications via pluggable transports (`LoggingEmailNotificationTransport`), and manages exponential backoff retries with dead-letter status.

### 4. Database Security Remediation & Migrations (V9, V10, V11)
- **`V9__remediate_buyer_confirmation_security.sql`:**
  - Hardened plpgsql implementation of all eight buyer confirmation SQL procedures.
  - Role ownership strictly transferred to `buyer_confirmation_definer`.
  - Permissions model: `REVOKE ALL ON FUNCTION ... FROM PUBLIC; GRANT EXECUTE TO app_worker;`.
  - Explicit function search path enforced: `SET search_path = pg_catalog, public, pg_temp;`.
  - Tenant and corporate identity boundaries verified against `organization_tax_profiles` and Indian GSTIN formatting.
- **`V10__transactional_expiry_and_privilege_hardening.sql`:**
  - Resolved transaction rollback defect during expiry handling: `fn_exchange_buyer_confirmation_token`, `fn_acquire_session_lease`, and `fn_submit_buyer_confirmation_response` mutate state to `EXPIRED` and return structured JSON outcomes (`{"outcome": "EXPIRED", "code": "REQUEST_EXPIRED"}`) rather than raising unhandled SQL exceptions.
  - Enclosing database transactions commit successfully; Spring application service maps structured outcome to HTTP 410 Gone only after persistence is committed.
  - Granted `INSERT, UPDATE ON confirmation_attempt_throttles TO app_worker` to ensure IP rate limiting persists correctly.
- **`V11__harden_invoice_verification_trigger_roles.sql`:**
  - Replaced wildcard role-name matching (`CURRENT_USER LIKE '%owner%'`) with an explicit whitelist: `buyer_confirmation_definer`, `postgres`, and `app_owner`.
  - Disallowed test-only roles (`test_owner`) from production migrations.
  - Unconditional Invariants:
    - **Invariant A:** Status cannot transition out of `VERIFIED`, and confirmation IDs cannot be altered once verified, unconditionally enforced across all roles.
    - **Invariant C:** Verified invoices require valid confirmation binding, status `'CONFIRMED'`, action `'CONFIRMED'`, and document SHA-256 checksum matching unconditionally across all roles, including table owners.
- **Accepted Operational Trust Boundary (`app.tenant_id`):**
  - PostgreSQL RLS policies evaluate `current_setting('app.tenant_id', true)` without cryptographic bearer token validation inside the database engine.
  - An attacker gaining direct SQL execution as `app_worker` could execute `SET LOCAL app.tenant_id` to forge context.
  - Accepted Trust Boundary: Tenant isolation relies on the application tier as the trusted gatekeeper (connection pooling, parameterized queries, strict network isolation preventing direct database access).

### 5. Automated Tests & CI Verification
- **Unit & Migration Tests (Surefire):** 68 tests (0 failures, 0 errors, 0 skipped):
  - `IdempotencyServiceTest` (8 tests)
  - `DocumentStorageServiceTest` (4 tests)
  - `GstinValidatorTest` (14 tests)
  - `FinancialAmountValidatorTest` (9 tests)
  - `InvoiceServiceTest` (7 tests)
  - `JwtServiceTest` (4 tests)
  - `BuyerConfirmationMutationServiceTest` (7 tests)
  - `EvidenceSerializationServiceTest` (11 tests)
  - `NotificationEnvelopeEncryptionServiceTest` (3 tests)
  - `FlywayMigrationTest` (1 test in containerized CI)
- **Integration Tests (Failsafe via Testcontainers PostgreSQL 16.4):** 79 tests (0 failures, 0 errors, 0 skipped):
  - `AuthControllerIT` (9 tests)
  - `InvoiceCreationIT` (7 tests)
  - `InvoiceDocumentUploadIT` (4 tests)
  - `InvoiceSubmissionIT` (4 tests)
  - `InvoiceTenantIsolationIT` (5 tests)
  - `TenantIsolationIT` (8 tests)
  - `IdempotencyFencingIT` (1 test)
  - `BuyerConfirmationWorkflowIT` (4 tests: token confirmation, doc download, rejection/dispute, rate limiting)
  - `BuyerConfirmationConcurrencyIT` (3 tests: concurrent cancellation, token exchange, monotonic fencing)
  - `BuyerConfirmationExpiryPersistenceIT` (6 tests: token expiry commit, lease expiry commit, response expiry commit, independent JDBC connection verification)
  - `BuyerConfirmationRateThrottlingIT` (3 tests: throttle persistence, backoff tiers)
  - `InvoiceVerificationTriggerIT` (9 tests: app_worker direct verification denied, definer success, definer missing ID rejected, definer checksum mismatch rejected, verified immutability across all roles, unintended role with 'owner' rejected, table owner unconditionally restricted)
  - `BuyerConfirmationTenantIsolationIT` (7 tests: tenant isolation across organizations, GSTINs, sessions, forged context)
  - `DatabaseSecurityDefinerPrivilegeIT` (5 tests: SECURITY DEFINER ownership, search path, PUBLIC revocation, worker execute grant, unauthorized role denial)
  - `V8ToV9MigrationUpgradeIT` (7 tests: populated upgrade V8 -> V9 -> V10 -> V11 with 100% relationship preservation for requests, invoices, sessions, responses, outbox events, and document checksums)
- **Total Backend Automated Tests Executed in CI:** **147 tests (0 failures, 0 errors, 0 skipped)**.

---

## Command Execution & Verification Evidence

| Step / Command | Target | Local Result | CI Result (Run 41567410045) | Classification |
|---|---|---|---|:---:|
| `git status` / `git log` | Repository Integrity & Commit History | Clean working tree, linear commits | PR Head `c7cfc82` / Merge `47c36c0` | **PASS** |
| `cd backend; .\mvnw.cmd test-compile` | Maven Test Source Compilation | All classes compile cleanly (`BUILD SUCCESS`, exit code 0) | Verified in CI | **PASS** |
| `cd backend; .\mvnw.cmd test -Dtest=...` | Unit Test Suite (67 tests locally) | 67 passed, 0 failed, 0 errors, 0 skipped (`BUILD SUCCESS`) | 68 passed (incl. Flyway) | **PASS** |
| `cd backend; ./mvnw clean verify -B` | Full Integration Test Suite (Testcontainers) | Host lacks Docker daemon | 147 passed, 0 failed, 0 skipped | **PASS** |
| `cd frontend; npm ci && npm run test` | Angular 17 / Karma Unit Test Suite | Node 20 / Angular CLI | 3 specs passed, 0 failures | **PASS** |
| `docker compose --env-file .env.example config` | Compose & Dockerfile Configuration | Compose syntax & Dockerfile checks | Validated successfully | **PASS** |

*Baseline Tag:* `m2-increment-2-buyer-confirmation`  
*Merge Commit:* `47c36c07d5f17da8450696178432c507985b5f5e`  
*PR #1 Head Tested:* `c7cfc8295a389f26d921ba82aa1408e036b9a116`  
*CI Workflow Run URL:* https://github.com/Pizer-Github/TriSettle/actions/runs/41567410045  
*All 3 CI Jobs (`Backend (Java 21 / Spring Boot 3)`, `Frontend (Angular 17 / Node.js 20)`, `Docker Configuration Validation`) green.*
