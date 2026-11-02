package com.tcs.invoicefactoring.common.outbox.dto;

import java.util.UUID;

public record BuyerNotificationPayload(
        UUID invoiceId,
        UUID requestId,
        String recipientEmail,
        String invoiceNumber,
        String buyerName,
        String totalAmount,
        String currency,
        String tokenUrl,
        String eventType
) {}
