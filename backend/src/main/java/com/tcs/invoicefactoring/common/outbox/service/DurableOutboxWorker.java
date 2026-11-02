package com.tcs.invoicefactoring.common.outbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.common.outbox.domain.OutboxEvent;
import com.tcs.invoicefactoring.common.outbox.dto.BuyerNotificationPayload;
import com.tcs.invoicefactoring.common.outbox.repository.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class DurableOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(DurableOutboxWorker.class);

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationEnvelopeEncryptionService encryptionService;
    private final EmailNotificationTransport emailTransport;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public DurableOutboxWorker(
            OutboxEventRepository outboxEventRepository,
            NotificationEnvelopeEncryptionService encryptionService,
            EmailNotificationTransport emailTransport,
            ObjectMapper objectMapper,
            EntityManager entityManager
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.encryptionService = encryptionService;
        this.emailTransport = emailTransport;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    @Scheduled(fixedDelay = 5000)
    public void scheduleProcessing() {
        processPendingBatch(10);
    }

    @Transactional
    public int processPendingBatch(int batchSize) {
        // Query ready events with FOR UPDATE SKIP LOCKED
        List<OutboxEvent> events;
        try {
            events = entityManager.createQuery("""
                    SELECT o FROM OutboxEvent o
                    WHERE o.status IN ('PENDING', 'FAILED')
                      AND o.scheduledAt <= :now
                      AND (o.claimedUntil IS NULL OR o.claimedUntil < :now)
                    ORDER BY o.scheduledAt ASC
                    """, OutboxEvent.class)
                    .setParameter("now", Instant.now())
                    .setMaxResults(batchSize)
                    .getResultList();
        } catch (Exception e) {
            log.error("Failed to query ready outbox events", e);
            return 0;
        }

        int processed = 0;
        for (OutboxEvent event : events) {
            processEvent(event);
            processed++;
        }
        return processed;
    }

    private void processEvent(OutboxEvent event) {
        try {
            String decryptedJson = encryptionService.decrypt(
                    event.getPayloadEncrypted(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getTenantId(),
                    event.getKeyVersion()
            );

            BuyerNotificationPayload payload = objectMapper.readValue(decryptedJson, BuyerNotificationPayload.class);
            emailTransport.sendBuyerConfirmationEmail(payload);

            event.setStatus("SENT");
            event.setUpdatedAt(Instant.now());
            outboxEventRepository.save(event);
            log.info("Successfully delivered outbox event [{}] for aggregate [{}]", event.getId(), event.getAggregateId());
        } catch (Exception ex) {
            log.error("Error processing outbox event [{}]: {}", event.getId(), ex.getMessage());
            int newRetryCount = event.getRetryCount() + 1;
            event.setRetryCount(newRetryCount);
            event.setLastError(ex.getMessage());
            event.setUpdatedAt(Instant.now());

            if (newRetryCount >= event.getMaxRetries()) {
                event.setStatus("DEAD_LETTER");
                log.warn("Outbox event [{}] exceeded max retries [{}]; moved to DEAD_LETTER", event.getId(), event.getMaxRetries());
            } else {
                event.setStatus("FAILED");
                long backoffSeconds = (long) Math.pow(2, newRetryCount) * 30L;
                event.setScheduledAt(Instant.now().plus(backoffSeconds, ChronoUnit.SECONDS));
                event.setClaimedUntil(null);
            }
            outboxEventRepository.save(event);
        }
    }
}
