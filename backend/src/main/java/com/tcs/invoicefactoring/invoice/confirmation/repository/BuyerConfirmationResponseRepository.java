package com.tcs.invoicefactoring.invoice.confirmation.repository;

import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BuyerConfirmationResponseRepository extends JpaRepository<BuyerConfirmationResponse, UUID> {

    Optional<BuyerConfirmationResponse> findByRequestId(UUID requestId);

    Optional<BuyerConfirmationResponse> findByInvoiceId(UUID invoiceId);
}
