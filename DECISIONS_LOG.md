# DECISIONS_LOG.md
1. **Idempotency Recovery:** Abandoned leases require reconciliation to verify commit status; no blind retries.
2. **Reserve Loss Allocation:** SME reserve does not absorb ordinary credit defaults on non-recourse sales.
3. **Ledger Protection:** Strict `BEFORE INSERT/UPDATE/DELETE` triggers ensure `POSTED` journals and lines are mathematically immutable.
4. **Implementation Foundation:** Docker, CI, and Flyway are mandatory prerequisites in Milestone 1 to ensure a stable development environment.
5. **No-Lombok Core Entities:** Core domain entities use explicit getters, setters, and constructors rather than Lombok annotation processing to guarantee compiler portability across Java 21 LTS and diverse build environments.
6. **Flyway Role Portability:** Migration `V4__enable_rls_foundation.sql` includes an idempotent role check ensuring the `app_worker` role exists when running against clean ephemeral databases (CI / Testcontainers).
7. **Safe UUID Casting in RLS Policies:** RLS policies wrap `current_setting('app.tenant_id', true)` with `NULLIF(..., '')::uuid` to prevent invalid UUID syntax exceptions when context is unset or empty.
8. **Stateless Token Management:** Frontend retains authentication state via Angular Signals and sessionStorage, avoiding long-term localStorage exposure to mitigate persistent token theft.
