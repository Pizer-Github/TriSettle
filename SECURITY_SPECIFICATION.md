# SECURITY_SPECIFICATION.md

## 1. Tenant Isolation & Pooling Leakage
*   Application sets `app.tenant_id` at the start of a transaction via `set_config('app.tenant_id', id, true)` (transaction-local).
*   HikariCP strictly resets connections on return to prevent tenant bleed.
*   Background workers use explicit trusted ownership contexts, never payload-supplied tenant IDs.

## 2. Administrative Access (Step-Up Auth)
*   No global `BYPASSRLS` for app administrators.
*   Admins use explicit service methods with audit logging, explicit RBAC permissions, and dual approval (Maker/Checker) for financial overrides.

## 3. Webhook Signature Verification
*   **Cryptographic Authenticity:** Every webhook payload must be validated against its provider-supplied HMAC or RSA signature before JSON parsing or database insertion. 
*   Requests failing signature validation must immediately return `401 Unauthorized` and be logged as security events, bypassing standard idempotency and persistence logic to prevent storage exhaustion attacks.