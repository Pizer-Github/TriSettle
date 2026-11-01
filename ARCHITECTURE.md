# ARCHITECTURE.md

## 1. Topology & Modules
*   **Frontend:** Angular 17+ Workspace.
*   **Backend:** Java 21 / Spring Boot 3 Modular Monolith.
*   **Database:** PostgreSQL 16.
*   **Modules:** `identity`, `invoice`, `risk`, `financing`, `agreement`, `ledger`, `payment`, `admin`.

## 2. Transaction & Process Boundaries
*   **Synchronous (`@Transactional`):** Bid Acceptance, Agreement Signing, Journal Posting.
*   **Asynchronous:** OCR, Notifications, Webhook Processing.
*   **Background Worker RLS Context:** Workers must not trust payload `tenant_id` blindly. The worker must fetch trusted ownership data from the DB, establish a transaction-local RLS context via `set_config('app.tenant_id', id, true)`, execute the logic, and ensure connection pooling (`HikariCP`) strictly resets the context before returning the connection.