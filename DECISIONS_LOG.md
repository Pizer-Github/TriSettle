# DECISIONS_LOG.md
1. **Idempotency Recovery:** Abandoned leases require reconciliation to verify commit status; no blind retries.
2. **Reserve Loss Allocation:** SME reserve does not absorb ordinary credit defaults on non-recourse sales.
3. **Ledger Protection:** Strict `BEFORE INSERT/UPDATE/DELETE` triggers ensure `POSTED` journals and lines are mathematically immutable.
4. **Implementation Foundation:** Docker, CI, and Flyway are mandatory prerequisites in Milestone 1 to ensure a stable development environment.
5. **No-Lombok Core Entities:** Core domain entities use explicit getters, setters, and constructors rather than Lombok annotation processing to guarantee compiler portability across Java 21 LTS and diverse build environments.
6. **Flyway Role Portability:** Migration `V4__enable_rls_foundation.sql` includes an idempotent role check ensuring the `app_worker` role exists when running against clean ephemeral databases (CI / Testcontainers).
7. **Safe UUID Casting in RLS Policies:** RLS policies wrap `current_setting('app.tenant_id', true)` with `NULLIF(..., '')::uuid` to prevent invalid UUID syntax exceptions when context is unset or empty.
8. **Stateless Token Management:** Frontend retains authentication state via Angular Signals and sessionStorage, avoiding long-term localStorage exposure to mitigate persistent token theft.
9. **Elimination of Public Admin Registration:** Public self-service registration is strictly limited to SMEs. Registration payloads cannot specify privileged organization types or roles; initial administrators are provisioned via `V5__seed_platform_admin.sql`.
10. **Aspect-Driven Transactional RLS Binding:** `TenantRlsAspect` runs at `@Order(200)` inside Spring `@Transactional` boundaries (`@Order(100)`), ensuring PostgreSQL `app.tenant_id` is set on the active transaction connection and auto-cleared upon commit/rollback (`is_local=true`).
11. **Elimination of Blanket Permissive RLS Policies:** Blanket `USING (true)` policies on `users` and `user_organizations` were eliminated. Pre-authentication lookups and atomic SME registration are handled via `SECURITY DEFINER` functions with fixed `search_path`, preserving strict table-level RLS without granting `app_worker` bypass privileges.
12. **Lockfile Tracking:** `package-lock.json` was un-ignored from version control and committed to ensure reproducible dependency installs with `npm ci` in Docker and CI workflows.
