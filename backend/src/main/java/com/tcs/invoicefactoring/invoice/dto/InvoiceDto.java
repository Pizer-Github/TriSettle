package com.tcs.invoicefactoring.invoice.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceDto(
        UUID id,
        UUID smeOrgId,
        String buyerName,
        String buyerGstin,
        String buyerPan,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        String currency,
        String taxableAmount,
        String taxAmount,
        String totalAmount,
        String status,
        String rejectionReason,
        Instant submittedAt,
        Instant createdAt,
        Instant updatedAt
) {}
