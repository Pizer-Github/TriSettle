# SECURITY_SPECIFICATION.md

## 1. Tenant Isolation & Database Row-Level Security (RLS)
*   **Transaction-Local Context:** The application establishes tenant context inside the actual database transaction using `set_config('app.tenant_id', id, true)` and `set_config('app.tenant_type', type, true)`. The `is_local=true` flag guarantees that PostgreSQL automatically resets the setting upon commit or rollback.
*   **Transaction Boundary Ordering:** Spring transaction management runs at `Order(100)` (starting the transaction), and `TenantRlsAspect` executes at `Order(200)` (inside the active transaction), ensuring the database connection is already checked out before setting GUCs.
*   **Connection Pool Protection:** HikariCP resets connection state upon return to the pool. Any missing context query fails closed (returns 0 rows) due to `NULLIF(current_setting('app.tenant_id', true), '')::uuid`.
*   **No General RLS Bypass for Auth:** Pre-authentication lookups (login credential verification and token re-validation) and SME self-registration run through narrowly scoped `SECURITY DEFINER` functions with fixed `search_path = public, pg_temp`. The runtime role `app_worker` is never granted `BYPASSRLS` or blanket `USING (true)` policies.
*   **Background Workers:** Background workers assume tenant context from trusted database ownership records before running tenant-scoped operations, never from untrusted job payloads.

## 2. Administrative Access & User Provisioning
*   **Prohibition of Public Administrative Registration:** Public registration endpoints (`/api/v1/auth/register`) strictly provision `SME` organizations and roles. Public registration of `PLATFORM`, `ADMIN`, `BUYER`, or `FINANCIER` roles is strictly prohibited.
*   **Controlled Admin Provisioning:** Platform administrators are provisioned via controlled database migrations (e.g., `V5__seed_platform_admin.sql`) or secure internal administrative procedures.
*   **Administrative RLS:** Admins have explicit RBAC permissions and use specific administrative policies (`current_setting('app.tenant_type', true) = 'ADMIN'`). There is no global `BYPASSRLS` grant for the application database role.

## 3. Webhook Signature Verification
*   **Cryptographic Authenticity:** Every webhook payload must be validated against its provider-supplied HMAC or RSA signature before JSON parsing or database insertion. 
*   Requests failing signature validation must immediately return `401 Unauthorized` and be logged as security events, bypassing standard idempotency and persistence logic to prevent storage exhaustion attacks.
