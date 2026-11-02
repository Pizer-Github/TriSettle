package com.tcs.invoicefactoring.invoice.confirmation.service;

import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationRequest;
import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationSession;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationRequestRepository;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationSessionRepository;
import com.tcs.invoicefactoring.invoice.domain.Invoice;
import com.tcs.invoicefactoring.invoice.repository.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class BuyerConfirmationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(BuyerConfirmationExpiryService.class);

    private final BuyerConfirmationRequestRepository requestRepository;
    private final BuyerConfirmationSessionRepository sessionRepository;
    private final InvoiceRepository invoiceRepository;

    public BuyerConfirmationExpiryService(
            BuyerConfirmationRequestRepository requestRepository,
            BuyerConfirmationSessionRepository sessionRepository,
            InvoiceRepository invoiceRepository
    ) {
        this.requestRepository = requestRepository;
        this.sessionRepository = sessionRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Scheduled(fixedDelay = 60000)
    public void sweepExpiredRequestsScheduled() {
        sweepExpiredRequests(50);
    }

    @Transactional
    public int sweepExpiredRequests(int limit) {
        List<BuyerConfirmationRequest> overdue = requestRepository.findOverduePendingRequests(Instant.now());
        int expiredCount = 0;

        for (BuyerConfirmationRequest candidate : overdue) {
            if (expiredCount >= limit) {
                break;
            }

            try {
                // Lock invoice FIRST
                Optional<Invoice> invoiceOpt = invoiceRepository.findForUpdate(candidate.getInvoiceId(), candidate.getSmeOrgId());
                if (invoiceOpt.isEmpty()) {
                    continue;
                }

                // Re-validate request
                Optional<BuyerConfirmationRequest> reqOpt = requestRepository.findById(candidate.getId());
                if (reqOpt.isEmpty()) {
                    continue;
                }
                BuyerConfirmationRequest req = reqOpt.get();

                if ("PENDING".equals(req.getStatus()) && req.getExpiresAt().isBefore(Instant.now())) {
                    List<BuyerConfirmationSession> sessions = sessionRepository.findActiveByRequestId(req.getId());
                    sessions.forEach(s -> s.setStatus("EXPIRED"));
                    sessionRepository.saveAll(sessions);

                    req.setStatus("EXPIRED");
                    req.setUpdatedAt(Instant.now());
                    requestRepository.save(req);
                    expiredCount++;
                }
            } catch (Exception e) {
                log.warn("Could not sweep confirmation request [{}]: {}", candidate.getId(), e.getMessage());
            }
        }

        if (expiredCount > 0) {
            log.info("Sweeper successfully expired [{}] overdue buyer confirmation requests", expiredCount);
        }
        return expiredCount;
    }
}
