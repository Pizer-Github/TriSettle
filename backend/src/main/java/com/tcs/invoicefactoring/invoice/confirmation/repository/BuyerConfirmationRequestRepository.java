package com.tcs.invoicefactoring.invoice.confirmation.repository;

import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BuyerConfirmationRequestRepository extends JpaRepository<BuyerConfirmationRequest, UUID> {

    Optional<BuyerConfirmationRequest> findBySecureTokenHash(String secureTokenHash);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM BuyerConfirmationRequest r WHERE r.id = :id")
    Optional<BuyerConfirmationRequest> findForUpdate(@Param("id") UUID id);

    @Query("""
            SELECT r FROM BuyerConfirmationRequest r
            WHERE r.invoiceId = :invoiceId
              AND r.status = 'PENDING'
            """)
    Optional<BuyerConfirmationRequest> findPendingByInvoiceId(@Param("invoiceId") UUID invoiceId);

    Optional<BuyerConfirmationRequest> findByIdAndSmeOrgId(UUID id, UUID smeOrgId);

    List<BuyerConfirmationRequest> findBySmeOrgId(UUID smeOrgId);

    @Query("""
            SELECT r FROM BuyerConfirmationRequest r
            WHERE r.status = 'PENDING'
              AND r.expiresAt <= :now
            """)
    List<BuyerConfirmationRequest> findOverduePendingRequests(@Param("now") Instant now);
}
