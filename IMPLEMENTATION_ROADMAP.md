# IMPLEMENTATION_ROADMAP.md

## Milestone 1: Implementation Foundation & Skeleton
*   **Scope:** Git repository init, Docker/Compose setup (PostgreSQL, Java, Angular), CI pipelines, Flyway migration testing, centralized logging, global exception handling, JWT Auth, RLS Setup.
*   **DoD:** Containerized app boots, runs Flyway, auth endpoints work, CI passes.

## Milestone 2: Invoice & Verification
*   **Scope:** Duplicate checks, Buyer Confirmation workflow, Listing creation.
*   **DoD:** SME can list a verified invoice. DB rejects listing an already active invoice.

## Milestone 3: Bidding & Transaction Boundaries
*   **Scope:** Polymorphic Bid payloads, Synchronous Bid Acceptance with `FOR UPDATE` locking, Draft Agreement generation.
*   **DoD:** Financier bids, SME accepts, Contract is created in `AGREEMENT_PENDING`.

## Milestone 4: Ledger & Webhooks
*   **Scope:** `ledger` tables, Immutability triggers, Webhook ingestion, Async Settlement allocation, Loan Accounting logic.
*   **DoD:** Simulated webhook triggers accurate double-entry ledger posting with immutable triggers firing correctly.

## Milestone 5: Exception Workflows & Admin Portal
*   **Scope:** Misdirected Payments, Admin APIs (Step-Up Auth, Dual Approval).
*   **DoD:** Admin can approve a Recourse Demand resulting in correct ledger reversal and audit trails.