# DECISIONS_LOG.md
1. **Idempotency Recovery:** Abandoned leases require reconciliation to verify commit status; no blind retries.
2. **Reserve Loss Allocation:** SME reserve does not absorb ordinary credit defaults on non-recourse sales.
3. **Ledger Protection:** Strict `BEFORE INSERT/UPDATE/DELETE` triggers ensure `POSTED` journals and lines are mathematically immutable.
4. **Implementation Foundation:** Docker, CI, and Flyway are mandatory prerequisites in Milestone 1 to ensure a stable development environment.