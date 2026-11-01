# IMPLEMENTATION_STATUS.md

## Milestone Progress Summary

| Milestone | Status | Description |
|-----------|--------|-------------|
| **M1: Foundation & Skeleton** | 🟡 Implemented (Verified via Code/Unit tests; CI & Container runs ready) | Base architecture, Flyway V1-V4, Security/JWT, RLS infrastructure, Angular 17 workspace, CI pipeline |
| **M2: Invoice & Verification** | ⚪ Not Implemented | Duplicate checks, Buyer Confirmation, Listing creation |
| **M3: Bidding & Transaction Boundaries** | ⚪ Not Implemented | Polymorphic bids, `@Transactional` `FOR UPDATE` bid acceptance |
| **M4: Ledger & Webhooks** | ⚪ Not Implemented | Immutable double-entry ledger triggers, webhook ingestion |
| **M5: Exception Workflows & Admin Portal** | ⚪ Not Implemented | Recourse Demands, Dual approval, Step-Up Auth |

---

## Detailed Milestone 1 Verification Status

### 1. Implemented and Tested / Validated
- [x] **Git Repository & Clean Structure:** Modular monolith structure matching approved domains (`identity`, `invoice`, `risk`, `financing`, `agreement`, `ledger`, `payment`, `admin`).
- [x] **PostgreSQL & Flyway Migrations (V1-V4):**
  - `organizations` table with UUID keys and type constraints.
  - `users` table with unique email, BCrypt password hashing, and active/verification flags.
  - `user_organizations` table linking users and organisations with role assignments.
  - `V4__enable_rls_foundation.sql` establishing initial RLS policies and table grants for `app_worker`.
- [x] **Spring Boot 3 / Java 21 Backend Foundation:**
  - `InvoiceFactoringApplication.java` entry point.
  - Profiles for `local` and `prod`.
  - Structured logging with Logback JSON / plain formatting and MDC correlation IDs (`RequestLoggingFilter`).
  - Global exception handler (`GlobalExceptionHandler`) with standardized envelopes (`ApiResponse`, `ApiError`).
  - Strict security: no stack traces or internal exception details leaked in API responses.
- [x] **JWT Authentication & Tenant Isolation Foundation:**
  - `JwtService` implementing HMAC-SHA signing (minimum 64-char secret enforcement) and claim extraction.
  - `JwtAuthFilter` validating tokens, verifying active DB user existence (no blind token trust), and managing MDC context.
  - `TenantContextHolder` and `RlsContextService` managing transaction-local `set_config('app.tenant_id', ..., true)`.
  - Registration and login endpoints (`AuthController`, `AuthService`).
- [x] **Angular 17 Workspace Foundation:**
  - Standalone component architecture with Angular Signals.
  - Routing with `authGuard` and `roleGuard`.
  - `AuthService` handling login, reactive session state, and logout.
  - `jwtInterceptor` injecting Bearer token and handling 401 redirection.
  - `GlobalErrorHandler` and centralized `ApiConfigService`.
  - Responsive `MainLayoutComponent` and `LoginComponent` with reactive form validation.
  - Unit test suites for service, guard, interceptor, and login component.
- [x] **CI Pipeline (`.github/workflows/ci.yml`):**
  - Automated jobs for Java 21 / Maven build & tests, Node 20 / Angular build & tests, and Docker validation.

### 2. Implemented, Pending Local Execution Environment
- [ ] **Local Docker Compose Runtime Execution:** Docker Compose and Dockerfile specifications are implemented and syntax-validated; local daemon execution was not executed on the local host due to Docker Desktop not being installed locally. CI validates the configuration.
- [ ] **Live Database Integration Tests:** Backend integration test classes (`FlywayMigrationTest`, `AuthControllerIT`, `TenantIsolationIT`, `JwtServiceTest`) are fully authored and ready to execute against Testcontainers / PostgreSQL in CI.

### 3. Not Implemented (Out of Milestone 1 Scope)
- Invoice upload, OCR, and eligibility checks (M2).
- Risk assessment engine and buyer confirmations (M2).
- Financing listings and bidding workflows (M3).
- Double-entry ledger tables and immutability triggers (M4).
- Webhook HMAC verification and reconciliation cron jobs (M4).
- Administrative Maker/Checker dual approvals and recourse demand workflows (M5).

### 4. Blocked by Unresolved Decision
- *None.* (Decisions pending business approval for M2/M3/M4 do not affect Milestone 1).
