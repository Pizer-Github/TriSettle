package com.tcs.invoicefactoring.invoice.confirmation.dto;

import java.time.Instant;
import java.util.UUID;

public record BuyerConfirmationResponseDto(
        UUID responseId,
        String action,
        String invoiceStatus,
        Instant confirmedAt,
        String evidenceSha256
) {}
