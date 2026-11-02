package com.tcs.invoicefactoring.common.outbox.service;

import com.tcs.invoicefactoring.common.outbox.dto.BuyerNotificationPayload;

public interface EmailNotificationTransport {
    void sendBuyerConfirmationEmail(BuyerNotificationPayload payload);
}
