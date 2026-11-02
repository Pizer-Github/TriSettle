package com.tcs.invoicefactoring.invoice.repository;

import com.tcs.invoicefactoring.invoice.domain.InvoiceDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceDocumentRepository extends JpaRepository<InvoiceDocument, UUID> {

    Optional<InvoiceDocument> findByInvoiceIdAndActiveTrue(UUID invoiceId);

    List<InvoiceDocument> findByInvoiceIdOrderByVersionDesc(UUID invoiceId);

    @Query("SELECT COALESCE(MAX(d.version), 0) FROM InvoiceDocument d WHERE d.invoiceId = :invoiceId")
    int findMaxVersionByInvoiceId(@Param("invoiceId") UUID invoiceId);

    boolean existsBySha256Checksum(String sha256Checksum);

    Optional<InvoiceDocument> findByIdAndInvoiceIdAndSmeOrgId(UUID id, UUID invoiceId, UUID smeOrgId);

    Optional<InvoiceDocument> findByIdAndInvoiceId(UUID id, UUID invoiceId);
}
