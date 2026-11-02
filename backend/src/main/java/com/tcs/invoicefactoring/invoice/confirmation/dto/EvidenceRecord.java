package com.tcs.invoicefactoring.invoice.confirmation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EvidenceRecord(
        String action,
        String authChannel,
        String buyerGstin,
        UUID buyerOrgId,
        String claimedEmail,
        Instant confirmationTimestamp,
        String disputeCategory,
        String documentSha256,
        LocalDate dueDate,
        String identityAssurance,
        LocalDate invoiceDate,
        UUID invoiceId,
        String invoiceNumber,
        String notes,
        UUID portalUserId,
        UUID requestId,
        UUID smeOrgId,
        BigDecimal taxAmount,
        BigDecimal taxableAmount,
        BigDecimal totalAmount,
        String canonicalEvidenceText,
        String evidenceSha256
) {}
