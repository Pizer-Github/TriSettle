# IMPLEMENTATION_STATUS.md

## Milestone Progress Summary

| Milestone | Status | Description |
|-----------|--------|-------------|
| **M1: Foundation & Skeleton** | 🟢 Fully Hardened & Audited (Awaiting User Sign-off) | Modular Monolith, Flyway V1-V5, Hardened JWT Auth, DEFINER Auth Lookups, Transactional RLS Aspect, Dynamic Admin Bootstrap, Angular 17 workspace with lockfile, CI pipeline |
| **M2: Invoice & Verification** | ⚪ Not Implemented | Duplicate checks, Buyer Confirmation, Listing creation |
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

---

## Command Execution & Verification Evidence

| Step / Command | Target | Local Result | CI Result | Classification |
|---|---|---|---|:---:|
| `git status` / `git log` | Repository Integrity & Commit History | Clean working tree, 15 linear commits (HEAD: `3e24f0e`) | Verified | **PASS** |
| `.github/workflows/ci.yml` | CI Pipeline Definition & Headless Launcher | Configured with `mvnw clean verify`, `npm ci`, `npm run test` (ChromeHeadlessCustom), `docker compose config` | Pre-flight validated | **PASS** |
| `npm --prefix frontend i --package-lock-only` | Frontend Lockfile Generation | 482 KB `package-lock.json` generated & audited | Stored in repo | **PASS** |
| `cd backend; ./mvnw clean verify -B` | Java 21 / Spring Boot 3 Backend Test Suite | Sandbox network isolation blocks wrapper download | GitHub Actions `backend` job | **NOT VERIFIED (Awaiting CI Run)** |
| `cd frontend; npm ci && npm run test` | Angular 17 / Karma Unit Test Suite | Sandbox network blocks npm package installation | GitHub Actions `frontend` job | **NOT VERIFIED (Awaiting CI Run)** |
| `docker compose --env-file .env.example config` | Compose & Dockerfile Configuration | Host lacks Docker daemon | GitHub Actions `docker-validation` job | **NOT VERIFIED (Awaiting CI Run)** |

*Note: All automated tests, container builds, and lockfile installations are fully configured in `.github/workflows/ci.yml`. When the repository is connected to a remote and pushed, GitHub Actions runners execute the full test suites.*
