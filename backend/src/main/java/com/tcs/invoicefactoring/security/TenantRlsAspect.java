package com.tcs.invoicefactoring.security;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Aspect that automatically binds the authenticated tenant context to PostgreSQL
 * session settings at the entry of every database transaction.
 *
 * Ordered precedence:
 * - Runs at Order(200), strictly lower precedence than Spring's TransactionInterceptor (Order 100).
 * - This guarantees that the transaction has started and the database connection is checked out
 *   before set_config('app.tenant_id', ..., true) is executed on the connection.
 * - Because is_local=true is used, PostgreSQL automatically resets the setting when the transaction commits or rolls back.
 */
@Aspect
@Component
@Order(200)
public class TenantRlsAspect {

    private static final Logger log = LoggerFactory.getLogger(TenantRlsAspect.class);

    private final RlsContextService rlsContextService;

    public TenantRlsAspect(RlsContextService rlsContextService) {
        this.rlsContextService = rlsContextService;
    }

    @Around("@within(org.springframework.transaction.annotation.Transactional) || " +
            "@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object applyTenantContext(ProceedingJoinPoint joinPoint) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TenantContextHolder.TenantContext context = TenantContextHolder.get();
            if (context != null && context.orgId() != null) {
                log.debug("Auto-establishing RLS context for tx: tenantId={} role={}", context.orgId(), context.role());
                rlsContextService.establishContext(context.orgId(), context.role());
            }
        }
        return joinPoint.proceed();
    }
}
