package com.tcs.invoicefactoring.security;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Establishes the transaction-local Row-Level Security context in PostgreSQL.
 *
 * Architecture rule (DATABASE_DESIGN.md §1, SECURITY_SPECIFICATION.md §1):
 * - Context is set with is_local=TRUE so it is automatically cleared at transaction end.
 * - This prevents pooled connection leakage across tenant boundaries.
 * - The tenant_id used here comes from TRUSTED backend data (verified DB membership),
 *   never from request bodies or raw JWT claims.
 *
 * Usage: Call {@link #establishContext(UUID, String)} at the start of a @Transactional
 * method (or via AOP) before any data access.
 */
@Component
public class RlsContextService {

    private static final Logger log = LoggerFactory.getLogger(RlsContextService.class);

    private final EntityManager entityManager;

    public RlsContextService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Set transaction-local RLS context.
     * Must be called inside an active database transaction.
     *
     * @param tenantId   the organisation UUID
     * @param tenantType one of: SME, FINANCIER, BUYER, ADMIN, WORKER
     */
    public void establishContext(UUID tenantId, String tenantType) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "RLS context must be established inside an active database transaction. " +
                    "Ensure @Transactional is applied before calling establishContext.");
        }

        log.debug("Establishing RLS context: tenantType={} tenantId={}", tenantType, tenantId);

        // is_local=true: the GUC is automatically reset at transaction end,
        // ensuring HikariCP connection pool cannot leak tenant context.
        entityManager.createNativeQuery(
                "SELECT set_config('app.tenant_type', :type, true)"
        ).setParameter("type", tenantType).getSingleResult();

        entityManager.createNativeQuery(
                "SELECT set_config('app.tenant_id', :id, true)"
        ).setParameter("id", tenantId.toString()).getSingleResult();
    }

    /**
     * Verify that the RLS context has been established (useful in tests).
     */
    public String getCurrentTenantType() {
        Object result = entityManager.createNativeQuery(
                "SELECT current_setting('app.tenant_type', true)"
        ).getSingleResult();
        return result != null ? result.toString() : null;
    }

    public String getCurrentTenantId() {
        Object result = entityManager.createNativeQuery(
                "SELECT current_setting('app.tenant_id', true)"
        ).getSingleResult();
        return result != null ? result.toString() : null;
    }
}
