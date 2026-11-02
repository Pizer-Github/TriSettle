package com.tcs.invoicefactoring.invoice.confirmation.dto;

import java.time.Instant;
import java.util.UUID;

public record TokenExchangeResponseDto(
        String confirmationSessionToken,
        Instant expiresAt,
        UUID invoiceId,
        String invoiceNumber,
        String buyerName,
        String totalAmount,
        String currency
) {}
