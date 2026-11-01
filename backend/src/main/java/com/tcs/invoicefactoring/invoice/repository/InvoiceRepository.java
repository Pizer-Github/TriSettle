package com.tcs.invoicefactoring.invoice.repository;

import com.tcs.invoicefactoring.invoice.domain.Invoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByIdAndSmeOrgId(UUID id, UUID smeOrgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.id = :id AND i.smeOrgId = :smeOrgId")
    Optional<Invoice> findForUpdate(@Param("id") UUID id, @Param("smeOrgId") UUID smeOrgId);

    boolean existsBySmeOrgIdAndBuyerGstinAndInvoiceNumber(UUID smeOrgId, String buyerGstin, String invoiceNumber);

    Page<Invoice> findBySmeOrgId(UUID smeOrgId, Pageable pageable);

    Page<Invoice> findBySmeOrgIdAndStatus(UUID smeOrgId, String status, Pageable pageable);
}
