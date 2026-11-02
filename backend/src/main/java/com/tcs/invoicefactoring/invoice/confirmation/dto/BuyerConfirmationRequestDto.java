package com.tcs.invoicefactoring.invoice.confirmation.dto;

import java.time.Instant;
import java.util.UUID;

public record BuyerConfirmationRequestDto(
        UUID requestId,
        UUID invoiceId,
        String recipientEmail,
        String status,
        Instant expiresAt,
        String invitationUrl
) {}
