# API_SPECIFICATION.md

## 1. Idempotency Contract, Boundaries & Recovery
*   **Transaction Boundary:**
    1. Begin Database Transaction.
    2. Insert into `idempotency_keys` (`PROCESSING`, `lease_expires_at`). On conflict, handle concurrently.
    3. Execute state-mutating business logic and ledger posting.
    4. Update `idempotency_keys` to `SUCCESS` and store the response payload.
    5. Commit Transaction.
*   **Safe Lease Recovery:** If a request arrives and the existing idempotency record is `PROCESSING` but the lease has expired, **the system must never blindly retry**. 
    *   The business logic might have committed, but the network dropped before the key was updated, or the server crashed mid-transaction.
    *   *Recovery Action:* The system must inspect the database (e.g., query the `financing_contracts` table by idempotency key/reference, or check PostgreSQL `xid` transaction status) to definitively establish if the original business operation committed. Only if it definitively rolled back may the operation be retried.

## 2. Webhooks & Provider Reconciliation
*   **Endpoint:** `POST /api/v1/webhooks/{provider}`
*   **Deduplication & Scoping:** Webhooks must be deduplicated using `UNIQUE(provider_account_id, provider_event_id)` to prevent collisions if the platform operates multiple merchant accounts with the same provider.
*   **Replay & Order:** 
    *   The raw JSON must be retained in `webhook_events`.
    *   Out-of-order events (e.g., `settled` arrives before `authorized`) must be handled by monotonic state checks on the target `payment_records`. An older state transition must never overwrite a newer one.
*   **Reconciliation:** Webhook ingestion is only the first step. A separate cron job must actively query the provider's API (Reconciliation) to catch webhooks that were never delivered, ensuring no payment state is permanently missed.