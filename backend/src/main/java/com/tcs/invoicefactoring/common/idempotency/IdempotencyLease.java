package com.tcs.invoicefactoring.common.idempotency;

import java.util.UUID;

/**
 * Result of an idempotency lease acquisition attempt.
 * Indicates whether a cached response is available or whether a new lease was acquired with a fencing token.
 */
public class IdempotencyLease {

    private final UUID keyId;
    private final UUID fencingToken;
    private final boolean cached;
    private final Integer cachedStatusCode;
    private final String cachedResponseBody;

    private IdempotencyLease(UUID keyId, UUID fencingToken, boolean cached, Integer cachedStatusCode, String cachedResponseBody) {
        this.keyId = keyId;
        this.fencingToken = fencingToken;
        this.cached = cached;
        this.cachedStatusCode = cachedStatusCode;
        this.cachedResponseBody = cachedResponseBody;
    }

    public static IdempotencyLease acquired(UUID keyId, UUID fencingToken) {
        return new IdempotencyLease(keyId, fencingToken, false, null, null);
    }

    public static IdempotencyLease cached(UUID keyId, int statusCode, String responseBody) {
        return new IdempotencyLease(keyId, null, true, statusCode, responseBody);
    }

    public UUID getKeyId() {
        return keyId;
    }

    public UUID getFencingToken() {
        return fencingToken;
    }

    public boolean isCached() {
        return cached;
    }

    public Integer getCachedStatusCode() {
        return cachedStatusCode;
    }

    public String getCachedResponseBody() {
        return cachedResponseBody;
    }
}
