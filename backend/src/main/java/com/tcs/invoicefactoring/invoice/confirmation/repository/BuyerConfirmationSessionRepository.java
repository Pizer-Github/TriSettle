package com.tcs.invoicefactoring.invoice.confirmation.repository;

import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BuyerConfirmationSessionRepository extends JpaRepository<BuyerConfirmationSession, UUID> {

    Optional<BuyerConfirmationSession> findBySessionTokenHash(String sessionTokenHash);

    List<BuyerConfirmationSession> findByRequestId(UUID requestId);

    @Query("""
            SELECT s FROM BuyerConfirmationSession s
            WHERE s.requestId = :requestId
              AND s.status = 'ACTIVE'
            ORDER BY s.id ASC
            """)
    List<BuyerConfirmationSession> findActiveByRequestId(@Param("requestId") UUID requestId);
}
