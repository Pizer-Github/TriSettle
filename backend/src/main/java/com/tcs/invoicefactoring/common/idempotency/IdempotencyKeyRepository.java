package com.tcs.invoicefactoring.common.idempotency;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByTenantIdAndUserIdAndEndpointAndKey(
            UUID tenantId, UUID userId, String endpoint, String key
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM IdempotencyKey k WHERE k.tenantId = :tenantId AND k.userId = :userId AND k.endpoint = :endpoint AND k.key = :key")
    Optional<IdempotencyKey> findForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId,
            @Param("endpoint") String endpoint,
            @Param("key") String key
    );
}
