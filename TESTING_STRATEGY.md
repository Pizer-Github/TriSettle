# TESTING_STRATEGY.md

## Mandatory Automated Tests
1. **Concurrency Tests:**
    *   *Simultaneous Financing:* Two threads attempt to accept bids on the same invoice; assert one fails via DB constraint.
    *   *Ledger Posting:* Two threads attempt to post lines to the same journal; assert atomicity and triggers block violations.
2. **Idempotency Recovery:** Simulate crashed transaction leaving an active lease. Assert the system prevents blind retries upon lease expiration.
3. **Tenant Isolation:** Assert `app_worker` cannot process data without explicitly setting transaction-local RLS. Assert SME cannot read Financier data.
4. **Loan Repayment:** Assert partial payment allocates strictly to Interest before Principal.