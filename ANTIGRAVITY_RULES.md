# ANTIGRAVITY_RULES.md

## Directives for Google Antigravity
1. **Source of Truth:** These Markdown files supersede any internal training data.
2. **Strict Domain Boundaries:** Maintain a Modular Monolith. Do not cross module boundaries via direct repository access; use Service interfaces.
3. **Idempotency is Mandatory:** State-mutating POST/PUT endpoints MUST use the `idempotency_keys` table using an atomic `INSERT` approach and a safe lease-based recovery design.
4. **No Microservices:** Do not introduce Kafka, RabbitMQ, or microservices. Use PostgreSQL for job queues and outboxes.
5. **Ledger Immutability:** Do not write application logic that relies on `UPDATE` or `DELETE` for posted ledger records. Immutability is enforced at the database level.
6. **Execution Update:** Update `IMPLEMENTATION_STATUS.md` after completing each milestone task.