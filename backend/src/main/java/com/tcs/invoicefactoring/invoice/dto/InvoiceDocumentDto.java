package com.tcs.invoicefactoring.invoice.dto;

import java.time.Instant;
import java.util.UUID;

public record InvoiceDocumentDto(
        UUID id,
        UUID invoiceId,
        int version,
        boolean active,
        String fileName,
        long fileSizeBytes,
        String mimeType,
        String sha256Checksum,
        Instant uploadedAt
) {}
