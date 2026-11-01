package com.tcs.invoicefactoring.common.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final String endpoint = "POST /api/v1/invoices";
    private final String key = "test-key-123";
    private final String requestHash = "hash-abc-123";

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository);
    }

    @Test
    void acquireLease_whenNewKey_acquiresLeaseWithFencingToken() {
        when(repository.findForUpdate(tenantId, userId, endpoint, key)).thenReturn(Optional.empty());

        IdempotencyLease lease = service.acquireLease(tenantId, userId, endpoint, key, requestHash);

        assertThat(lease.isCached()).isFalse();
        assertThat(lease.getFencingToken()).isNotNull();
        verify(repository).save(any(IdempotencyKey.class));
    }

    @Test
    void acquireLease_whenSuccessWithSameHash_returnsCachedResponse() {
        IdempotencyKey existing = new IdempotencyKey(tenantId, userId, endpoint, key, requestHash, Instant.now().plusSeconds(60));
        existing.setStatus("SUCCESS");
        existing.setResponseCode(201);
        existing.setResponseBody("{\"success\":true}");

        when(repository.findForUpdate(tenantId, userId, endpoint, key)).thenReturn(Optional.of(existing));

        IdempotencyLease lease = service.acquireLease(tenantId, userId, endpoint, key, requestHash);

        assertThat(lease.isCached()).isTrue();
        assertThat(lease.getCachedStatusCode()).isEqualTo(201);
        assertThat(lease.getCachedResponseBody()).isEqualTo("{\"success\":true}");
    }

    @Test
    void acquireLease_whenSuccessWithMismatchedHash_throwsConflict() {
        IdempotencyKey existing = new IdempotencyKey(tenantId, userId, endpoint, key, "different-hash", Instant.now().plusSeconds(60));
        existing.setStatus("SUCCESS");

        when(repository.findForUpdate(tenantId, userId, endpoint, key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.acquireLease(tenantId, userId, endpoint, key, requestHash))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("mismatched request parameters");
    }

    @Test
    void acquireLease_whenActiveProcessing_throwsConflict() {
        IdempotencyKey existing = new IdempotencyKey(tenantId, userId, endpoint, key, requestHash, Instant.now().plusSeconds(60));
        existing.setStatus("PROCESSING");

        when(repository.findForUpdate(tenantId, userId, endpoint, key)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.acquireLease(tenantId, userId, endpoint, key, requestHash))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("concurrent request");
    }

    @Test
    void acquireLease_whenExpiredProcessing_reclaimsWithNewToken() {
        UUID oldToken = UUID.randomUUID();
        IdempotencyKey existing = new IdempotencyKey(tenantId, userId, endpoint, key, requestHash, Instant.now().minusSeconds(10));
        existing.setStatus("PROCESSING");
        existing.setFencingToken(oldToken);

        when(repository.findForUpdate(tenantId, userId, endpoint, key)).thenReturn(Optional.of(existing));

        IdempotencyLease lease = service.acquireLease(tenantId, userId, endpoint, key, requestHash);

        assertThat(lease.isCached()).isFalse();
        assertThat(lease.getFencingToken()).isNotEqualTo(oldToken);
        verify(repository).save(existing);
    }

    @Test
    void verifyFencingTokenAndLock_whenTokenMatches_succeeds() {
        UUID token = UUID.randomUUID();
        IdempotencyKey record = new IdempotencyKey(tenantId, userId, endpoint, key, requestHash, Instant.now().plusSeconds(60));
        record.setStatus("PROCESSING");
        record.setFencingToken(token);

        when(repository.findForUpdate(tenantId, userId, endpoint, key)).thenReturn(Optional.of(record));

        service.verifyFencingTokenAndLock(tenantId, userId, endpoint, key, token);
        // No exception
    }

    @Test
    void verifyFencingTokenAndLock_whenTokenMismatched_throwsStaleLease() {
        UUID workerToken = UUID.randomUUID();
        UUID otherToken = UUID.randomUUID();

        IdempotencyKey record = new IdempotencyKey(tenantId, userId, endpoint, key, requestHash, Instant.now().plusSeconds(60));
        record.setStatus("PROCESSING");
        record.setFencingToken(otherToken);

        when(repository.findForUpdate(tenantId, userId, endpoint, key)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.verifyFencingTokenAndLock(tenantId, userId, endpoint, key, workerToken))
                .isInstanceOf(StaleIdempotencyLeaseException.class);
    }

    @Test
    void completeSuccess_updatesStatusAndPayload() {
        UUID token = UUID.randomUUID();
        IdempotencyKey record = new IdempotencyKey(tenantId, userId, endpoint, key, requestHash, Instant.now().plusSeconds(60));
        record.setStatus("PROCESSING");
        record.setFencingToken(token);

        when(repository.findForUpdate(tenantId, userId, endpoint, key)).thenReturn(Optional.of(record));

        service.completeSuccess(tenantId, userId, endpoint, key, token, 201, "{\"id\":\"123\"}");

        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getResponseCode()).isEqualTo(201);
        assertThat(record.getResponseBody()).isEqualTo("{\"id\":\"123\"}");
        verify(repository).save(record);
    }
}
