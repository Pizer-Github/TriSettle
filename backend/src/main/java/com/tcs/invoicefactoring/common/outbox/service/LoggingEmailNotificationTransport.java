package com.tcs.invoicefactoring.common.outbox.service;

import com.tcs.invoicefactoring.common.outbox.dto.BuyerNotificationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(name = "customEmailTransport")
public class LoggingEmailNotificationTransport implements EmailNotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailNotificationTransport.class);

    @Override
    public void sendBuyerConfirmationEmail(BuyerNotificationPayload payload) {
        log.info("Dispatching confirmation email to recipient for invoice [{}] (masked email: [{}]), type: [{}]",
                payload.invoiceId(), maskEmail(payload.recipientEmail()), payload.eventType());
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        String[] parts = email.split("@", 2);
        String name = parts[0];
        String visible = name.length() > 2 ? name.substring(0, 2) + "***" : "***";
        return visible + "@" + parts[1];
    }
}
