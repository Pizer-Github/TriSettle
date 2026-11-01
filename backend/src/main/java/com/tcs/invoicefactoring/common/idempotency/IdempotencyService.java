package com.tcs.invoicefactoring.common.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing idempotency lease acquisition, fencing token validation, and co-committing response caching.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final long LEASE_DURATION_SECONDS = 60;

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Phase 1: Acquires a lease or returns a cached response.
     * Executes in an autonomous transaction (REQUIRES_NEW) to persist lease ownership independently.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyLease acquireLease(UUID tenantId, UUID userId, String endpoint, String key, String requestHash) {
        Optional<IdempotencyKey> existingOpt = repository.findForUpdate(tenantId, userId, endpoint, key);

        if (existingOpt.isPresent()) {
            IdempotencyKey existing = existingOpt.get();

            if ("SUCCESS".equals(existing.getStatus()) || "FAILED".equals(existing.getStatus())) {
                if (!requestHash.equals(existing.getRequestHash())) {
                    throw new IdempotencyConflictException("Idempotency key reused with mismatched request parameters");
                }
                return IdempotencyLease.cached(existing.getId(), existing.getResponseCode(), existing.getResponseBody());
            }

            if ("PROCESSING".equals(existing.getStatus())) {
                Instant now = Instant.now();
                if (now.isBefore(existing.getLeaseExpiresAt())) {
                    throw new IdempotencyConflictException("A concurrent request is already in progress with this idempotency key");
                }

                // Expired lease: reclaim with a new fencing token
                log.warn("Reclaiming expired idempotency lease for key [{}] at endpoint [{}]", key, endpoint);
                UUID newToken = UUID.randomUUID();
                existing.setStatus("PROCESSING");
                existing.setFencingToken(newToken);
                existing.setRequestHash(requestHash);
                existing.setLeaseExpiresAt(now.plusSeconds(LEASE_DURATION_SECONDS));
                existing.setUpdatedAt(now);
                repository.save(existing);
                return IdempotencyLease.acquired(existing.getId(), newToken);
            }
        }

        // New lease
        UUID newToken = UUID.randomUUID();
        IdempotencyKey newKey = new IdempotencyKey(
                tenantId,
                userId,
                endpoint,
                key,
                requestHash,
                Instant.now().plusSeconds(LEASE_DURATION_SECONDS)
        );
        newKey.setFencingToken(newToken);
        repository.save(newKey);
        return IdempotencyLease.acquired(newKey.getId(), newToken);
    }

    /**
     * Phase 2: Verifies fencing token and locks the idempotency record FOR UPDATE.
     * Must be called inside the active business transaction.
     */
    @Transactional
    public void verifyFencingTokenAndLock(UUID tenantId, UUID userId, String endpoint, String key, UUID fencingToken) {
        IdempotencyKey record = repository.findForUpdate(tenantId, userId, endpoint, key)
                .orElseThrow(() -> new StaleIdempotencyLeaseException("Idempotency record not found during transaction execution"));

        if (!fencingToken.equals(record.getFencingToken()) || !"PROCESSING".equals(record.getStatus())) {
            throw new StaleIdempotencyLeaseException("Idempotency lease is stale or was reclaimed by another worker");
        }
    }

    /**
     * Phase 2 completion: Updates the idempotency record to SUCCESS within the SAME business transaction.
     */
    @Transactional
    public void completeSuccess(UUID tenantId, UUID userId, String endpoint, String key, UUID fencingToken, int responseCode, String responseBody) {
        IdempotencyKey record = repository.findForUpdate(tenantId, userId, endpoint, key)
                .orElseThrow(() -> new StaleIdempotencyLeaseException("Idempotency record not found during completion"));

        if (!fencingToken.equals(record.getFencingToken())) {
            throw new StaleIdempotencyLeaseException("Cannot complete idempotency key: fencing token mismatch");
        }

        record.setStatus("SUCCESS");
        record.setResponseCode(responseCode);
        record.setResponseBody(responseBody);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
    }

    /**
     * Phase 2 completion for deterministic failure: Updates the idempotency record to FAILED to cache the error.
     */
    @Transactional
    public void completeFailed(UUID tenantId, UUID userId, String endpoint, String key, UUID fencingToken, int responseCode, String responseBody) {
        repository.findForUpdate(tenantId, userId, endpoint, key).ifPresent(record -> {
            if (fencingToken.equals(record.getFencingToken())) {
                record.setStatus("FAILED");
                record.setResponseCode(responseCode);
                record.setResponseBody(responseBody);
                record.setUpdatedAt(Instant.now());
                repository.save(record);
            }
        });
    }

    /**
     * Releases or deletes an active lease on transient failure to enable immediate retry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseLease(UUID tenantId, UUID userId, String endpoint, String key, UUID fencingToken) {
        repository.findForUpdate(tenantId, userId, endpoint, key).ifPresent(record -> {
            if (fencingToken.equals(record.getFencingToken()) && "PROCESSING".equals(record.getStatus())) {
                repository.delete(record);
            }
        });
    }
}
