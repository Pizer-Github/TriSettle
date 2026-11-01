package com.tcs.invoicefactoring.security;

import java.util.UUID;

/**
 * Holds the transaction-local tenant context for the current thread.
 *
 * The tenant context is established from TRUSTED backend data (the authenticated
 * user's DB-verified organisation membership) — NEVER from request bodies or
 * unverified JWT claims alone.
 *
 * This holder is cleared at the end of every request by {@link JwtAuthFilter}.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static void set(UUID userId, UUID orgId, String role) {
        CONTEXT.set(new TenantContext(userId, orgId, role));
    }

    public static TenantContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Immutable tenant context record for the current request.
     */
    public record TenantContext(UUID userId, UUID orgId, String role) {}
}
