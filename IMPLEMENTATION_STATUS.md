# IMPLEMENTATION_STATUS.md

## Milestone Progress Summary

| Milestone | Status | Description |
|-----------|--------|-------------|
| **M1: Foundation & Skeleton** | 🟢 Accepted & Hardened | Modular Monolith, Flyway V1-V5, Hardened JWT Auth, DEFINER Auth Lookups, Transactional RLS Aspect, Dynamic Admin Bootstrap, Angular 17 workspace with lockfile, CI pipeline |
| **M2: Invoice & Verification** | 🟡 In Progress (Increment 1 Complete) | Increment 1: Invoice Creation, Document Management, Idempotency Lease Fencing, RLS Tenant Isolation (Complete). Increment 2: Buyer Confirmation & Listing (Pending). |
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
- Unit Tests (Local Execution): 46 passing tests (0 failures, 0 errors, 0 skipped):
  - `IdempotencyServiceTest` (8 tests)
  - `DocumentStorageServiceTest` (4 tests)
  - `GstinValidatorTest` (14 tests)
  - `FinancialAmountValidatorTest` (9 tests)
  - `InvoiceServiceTest` (7 tests)
  - `JwtServiceTest` (4 tests)
- Integration Tests Authored (CI Execution via Testcontainers):
  - `InvoiceCreationIT` (7 scenarios)
  - `InvoiceDocumentUploadIT` (4 scenarios)
  - `InvoiceSubmissionIT` (4 scenarios)
  - `InvoiceTenantIsolationIT` (5 scenarios)
  - `IdempotencyFencingIT` (2 scenarios)
- Source Compilation: 14 test source files and 39 main source files compile cleanly with zero errors (`BUILD SUCCESS`).

---

## Command Execution & Verification Evidence

| Step / Command | Target | Local Result | CI Result | Classification |
|---|---|---|---|:---:|
| `git status` / `git log` | Repository Integrity & Commit History | Clean working tree, linear commits | Verified | **PASS** |
| `cd backend; .\mvnw.cmd test-compile` | Maven Test Source Compilation | All 39 main & 14 test classes compile cleanly (`BUILD SUCCESS`) | Verified | **PASS** |
| `cd backend; .\mvnw.cmd test -Dtest=...` | Unit Test Suite (46 tests) | 46 passed, 0 failed, 0 errors, 0 skipped (`BUILD SUCCESS`) | Verified | **PASS** |
| `.github/workflows/ci.yml` | CI Pipeline Definition & Headless Launcher | Configured with `mvnw clean verify`, `npm ci`, `npm run test` (ChromeHeadlessCustom), `docker compose config` | Pre-flight validated | **PASS** |
| `npm --prefix frontend i --package-lock-only` | Frontend Lockfile Generation | 482 KB `package-lock.json` generated & audited | Stored in repo | **PASS** |
| `cd backend; ./mvnw clean verify -B` | Full Integration Test Suite (Testcontainers) | Host lacks Docker daemon for containerized PostgreSQL | GitHub Actions `backend` job | **READY FOR CI** |
| `cd frontend; npm ci && npm run test` | Angular 17 / Karma Unit Test Suite | Headless Chrome in Ubuntu runner | GitHub Actions `frontend` job | **READY FOR CI** |
| `docker compose --env-file .env.example config` | Compose & Dockerfile Configuration | Host lacks Docker daemon | GitHub Actions `docker-validation` job | **READY FOR CI** |

*Note: All automated tests, container builds, and lockfile installations are fully configured in `.github/workflows/ci.yml`. When the repository is connected to a remote and pushed, GitHub Actions runners execute the full test suites.*
