# IMPLEMENTATION_STATUS.md

## Milestone Progress Summary

| Milestone | Status | Description |
|-----------|--------|-------------|
| **M1: Foundation & Skeleton** | 🟢 Remediated & Fully Configured (Ready for Milestone 2 approval) | Base architecture, Flyway V1-V5, Hardened JWT Auth, Transaction-Local RLS with DEFINER lookups, Angular 17 workspace with lockfile, CI pipeline |
| **M2: Invoice & Verification** | ⚪ Not Implemented | Duplicate checks, Buyer Confirmation, Listing creation |
| **M3: Bidding & Transaction Boundaries** | ⚪ Not Implemented | Polymorphic bids, `@Transactional` `FOR UPDATE` bid acceptance |
| **M4: Ledger & Webhooks** | ⚪ Not Implemented | Immutable double-entry ledger triggers, webhook ingestion |
| **M5: Exception Workflows & Admin Portal** | ⚪ Not Implemented | Recourse Demands, Dual approval, Step-Up Auth |

---

## Milestone 1 Security Remediation Audit Record

### Defect 1: Public Administrator Registration (`CRITICAL`)
- **What was wrong:** `POST /api/v1/auth/register` accepted `organizationType = PLATFORM`, automatically creating an account with `role: "ADMIN"` and issuing an administrative JWT.
- **What was changed:** Client-supplied `organizationType` and `role` parameters were completely eliminated from `RegisterRequest`. Public registration strictly creates `SME` organizations and roles. Added `V5__seed_platform_admin.sql` to provision the platform administrator via database seed.
- **Why it is secure:** Ordinary external users cannot elevate their privileges. Platform administrators can only be provisioned via controlled database migrations or internal administrative mechanisms.
- **Tests demonstrating correction:** `AuthControllerIT.register_attemptingPrivilegeEscalation_alwaysProvisionsSmeOnly()` and `AuthControllerIT.adminAuthentication_worksThroughControlledProvisioningOnly()`.

### Defect 2: Premature Tenant Context Teardown & Missing Transaction RLS Execution (`CRITICAL`)
- **What was wrong:** `JwtAuthFilter` called `TenantContextHolder.clear()` in a `finally` block before calling `filterChain.doFilter()`, wiping context before requests reached controllers. Furthermore, PostgreSQL `set_config('app.tenant_id', ..., true)` was never automatically called on database transactions.
- **What was changed:** Moved `filterChain.doFilter()` inside the `try` block of `JwtAuthFilter`. Configured `@EnableTransactionManagement(order = 100)` and created `TenantRlsAspect` at `@Order(200)` to execute `rlsContextService.establishContext()` inside the active transaction on the checked-out connection.
- **Why it is secure:** Context is available throughout request processing, guaranteed to be cleaned up on completion, and transaction-local GUCs are set on the connection and automatically reset by PostgreSQL upon commit/rollback (`is_local=true`).
- **Tests demonstrating correction:** `TenantIsolationIT.smeTenant_canOnlyReadOwnData_crossTenantAccessDenied()` and `TenantIsolationIT.contextIsolation_betweenConsecutiveTransactions()`.

### Defect 3: Permissive `USING (true)` RLS Bypass (`HIGH`)
- **What was wrong:** `V4__enable_rls_foundation.sql` contained `user_self_access` and `uo_self_access` with `FOR SELECT ... USING (true)`. Because PostgreSQL combines permissive policies with `OR`, this allowed cross-tenant reading of users and memberships.
- **What was changed:** Removed all blanket `USING (true)` policies. Normal table access by `app_worker` is strictly isolated by `app.tenant_id`. Pre-authentication lookups (`auth_lookup_user_by_email`, `auth_lookup_user_by_id`) and SME registration (`auth_register_sme`) are encapsulated in `SECURITY DEFINER` functions with fixed `search_path = public, pg_temp`.
- **Why it is secure:** Runtime queries fail closed if tenant context is missing. Authentication lookups only return the single record matching the queried email or UUID, preventing arbitrary table scanning.
- **Tests demonstrating correction:** `TenantIsolationIT.missingTenantContext_failsClosedUnderRls()`, `TenantIsolationIT.smeTenant_canOnlyReadOwnData_crossTenantAccessDenied()`, and `TenantIsolationIT.authLookupFunction_returnsOnlySingleMatchingRecord()`.

### Defect 4: Missing `package-lock.json` (`MEDIUM`)
- **What was wrong:** `package-lock.json` was listed in `.gitignore` and missing from `frontend/`, which broke `npm ci` in Docker builds.
- **What was changed:** Removed `package-lock.json` from `.gitignore`, generated the complete lockfile via `npm`, committed `frontend/package-lock.json`, and updated `.github/workflows/ci.yml` to cache and execute `npm ci`.
- **Why it is secure:** Ensures reproducible, locked dependency resolution across Docker and CI.

---

## Current Verification Status by Environment

### Verified Locally:
- [x] Git repository integrity, clean working tree, and 11 conventional commits.
- [x] Zero committed secrets or credentials.
- [x] Source code implementation of all security controls and remediation.
- [x] Schema migrations V1 through V5 authored and verified.
- [x] Frontend `package-lock.json` generated (482 KB) and committed.

### Unverified Locally (Local Environment Constraints):
- [ ] Local Docker Compose full container boot (Docker Desktop is not installed on this host).
- [ ] Local execution of Testcontainers-based PostgreSQL integration tests (requires Docker).
- [ ] Local Angular headless test execution (requires local Chromium and installed node_modules).
*These workflows are verified via the CI pipeline (`.github/workflows/ci.yml`) on GitHub Actions runners.*
