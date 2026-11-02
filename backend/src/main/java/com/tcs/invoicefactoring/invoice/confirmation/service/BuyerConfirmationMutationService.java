package com.tcs.invoicefactoring.invoice.confirmation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.common.outbox.domain.OutboxEvent;
import com.tcs.invoicefactoring.common.outbox.dto.BuyerNotificationPayload;
import com.tcs.invoicefactoring.common.outbox.repository.OutboxEventRepository;
import com.tcs.invoicefactoring.common.outbox.service.NotificationEnvelopeEncryptionService;
import com.tcs.invoicefactoring.identity.domain.OrganizationTaxProfile;
import com.tcs.invoicefactoring.identity.repository.OrganizationTaxProfileRepository;
import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationRequest;
import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationResponse;
import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationSession;
import com.tcs.invoicefactoring.invoice.confirmation.domain.ConfirmationAttemptThrottle;
import com.tcs.invoicefactoring.invoice.confirmation.dto.BuyerConfirmationRequestDto;
import com.tcs.invoicefactoring.invoice.confirmation.dto.BuyerConfirmationResponseDto;
import com.tcs.invoicefactoring.invoice.confirmation.dto.SessionLeaseResult;
import com.tcs.invoicefactoring.invoice.confirmation.dto.SubmitConfirmationRequest;
import com.tcs.invoicefactoring.invoice.confirmation.dto.TokenExchangeResponseDto;
import com.tcs.invoicefactoring.invoice.confirmation.exception.BuyerTaxProfileException;
import com.tcs.invoicefactoring.invoice.confirmation.exception.ConfirmationLeaseException;
import com.tcs.invoicefactoring.invoice.confirmation.exception.ConfirmationSessionExpiredException;
import com.tcs.invoicefactoring.invoice.confirmation.exception.FencingGenerationException;
import com.tcs.invoicefactoring.invoice.confirmation.exception.InvalidConfirmationTokenException;
import com.tcs.invoicefactoring.invoice.confirmation.exception.RateLimitExceededException;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationRequestRepository;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationResponseRepository;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationSessionRepository;
import com.tcs.invoicefactoring.invoice.confirmation.repository.ConfirmationAttemptThrottleRepository;
import com.tcs.invoicefactoring.invoice.domain.Invoice;
import com.tcs.invoicefactoring.invoice.domain.InvoiceDocument;
import com.tcs.invoicefactoring.invoice.exception.DocumentRequiredException;
import com.tcs.invoicefactoring.invoice.exception.InvalidInvoiceStateException;
import com.tcs.invoicefactoring.invoice.exception.InvoiceNotFoundException;
import com.tcs.invoicefactoring.invoice.repository.InvoiceDocumentRepository;
import com.tcs.invoicefactoring.invoice.repository.InvoiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BuyerConfirmationMutationService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceDocumentRepository documentRepository;
    private final BuyerConfirmationRequestRepository requestRepository;
    private final BuyerConfirmationSessionRepository sessionRepository;
    private final BuyerConfirmationResponseRepository responseRepository;
    private final OrganizationTaxProfileRepository taxProfileRepository;
    private final ConfirmationAttemptThrottleRepository throttleRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final NotificationEnvelopeEncryptionService encryptionService;
    private final EvidenceSerializationService evidenceService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final SecureRandom secureRandom = new SecureRandom();

    public BuyerConfirmationMutationService(
            InvoiceRepository invoiceRepository,
            InvoiceDocumentRepository documentRepository,
            BuyerConfirmationRequestRepository requestRepository,
            BuyerConfirmationSessionRepository sessionRepository,
            BuyerConfirmationResponseRepository responseRepository,
            OrganizationTaxProfileRepository taxProfileRepository,
            ConfirmationAttemptThrottleRepository throttleRepository,
            OutboxEventRepository outboxEventRepository,
            NotificationEnvelopeEncryptionService encryptionService,
            EvidenceSerializationService evidenceService,
            ObjectMapper objectMapper
    ) {
        this(invoiceRepository, documentRepository, requestRepository, sessionRepository, responseRepository,
                taxProfileRepository, throttleRepository, outboxEventRepository, encryptionService, evidenceService, objectMapper, null);
    }

    @Autowired
    public BuyerConfirmationMutationService(
            InvoiceRepository invoiceRepository,
            InvoiceDocumentRepository documentRepository,
            BuyerConfirmationRequestRepository requestRepository,
            BuyerConfirmationSessionRepository sessionRepository,
            BuyerConfirmationResponseRepository responseRepository,
            OrganizationTaxProfileRepository taxProfileRepository,
            ConfirmationAttemptThrottleRepository throttleRepository,
            OutboxEventRepository outboxEventRepository,
            NotificationEnvelopeEncryptionService encryptionService,
            EvidenceSerializationService evidenceService,
            ObjectMapper objectMapper,
            @Autowired(required = false) PlatformTransactionManager transactionManager
    ) {
        this.invoiceRepository = invoiceRepository;
        this.documentRepository = documentRepository;
        this.requestRepository = requestRepository;
        this.sessionRepository = sessionRepository;
        this.responseRepository = responseRepository;
        this.taxProfileRepository = taxProfileRepository;
        this.throttleRepository = throttleRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.encryptionService = encryptionService;
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
        this.transactionManager = transactionManager;
    }

    /**
     * Issues a buyer confirmation request for an invoice in PENDING_VERIFICATION.
     * Universal lock order: invoices -> buyer_confirmation_requests.
     */
    @Transactional
    public BuyerConfirmationRequestDto requestConfirmation(UUID smeOrgId, UUID userId, UUID invoiceId, String recipientEmail, String baseUrl) {
        // Step 1: Lock invoice FIRST
        Invoice invoice = invoiceRepository.findForUpdate(invoiceId, smeOrgId)
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice [" + invoiceId + "] not found"));

        if (!"PENDING_VERIFICATION".equals(invoice.getStatus())) {
            throw new InvalidInvoiceStateException("Cannot request confirmation: invoice status is [" + invoice.getStatus() + "], but must be PENDING_VERIFICATION");
        }

        // Step 2: Lock existing pending request if any
        Optional<BuyerConfirmationRequest> existingReqOpt = requestRepository.findPendingByInvoiceId(invoiceId);
        if (existingReqOpt.isPresent()) {
            BuyerConfirmationRequest existingReq = existingReqOpt.get();
            if (existingReq.getExpiresAt().isAfter(Instant.now())) {
                throw new InvalidInvoiceStateException("An active pending confirmation request already exists for this invoice");
            } else {
                // Lazily expire overdue request and revoke sessions
                List<BuyerConfirmationSession> sessions = sessionRepository.findActiveByRequestId(existingReq.getId());
                sessions.forEach(s -> s.setStatus("EXPIRED"));
                sessionRepository.saveAll(sessions);

                existingReq.setStatus("EXPIRED");
                existingReq.setUpdatedAt(Instant.now());
                requestRepository.save(existingReq);
            }
        }

        // Lookup verified debtor tax profile
        Optional<OrganizationTaxProfile> taxProfileOpt = taxProfileRepository.findActiveVerifiedByGstin(invoice.getBuyerGstin());
        UUID buyerOrgId = taxProfileOpt.map(OrganizationTaxProfile::getOrgId).orElse(null);

        // Generate high-entropy token
        String rawToken = generateSecureToken();
        String secureTokenHash = computeSha256(rawToken);
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        BuyerConfirmationRequest request = new BuyerConfirmationRequest();
        request.setInvoiceId(invoiceId);
        request.setSmeOrgId(smeOrgId);
        request.setBuyerOrgId(buyerOrgId);
        request.setBuyerGstin(invoice.getBuyerGstin());
        request.setRecipientEmail(recipientEmail.trim().toLowerCase());
        request.setSecureTokenHash(secureTokenHash);
        request.setStatus("PENDING");
        request.setExpiresAt(expiresAt);
        request = requestRepository.save(request);

        String invitationUrl = baseUrl + "/confirm#token=" + rawToken;

        // Create Outbox Event
        BuyerNotificationPayload outboxPayload = new BuyerNotificationPayload(
                invoiceId,
                request.getId(),
                request.getRecipientEmail(),
                invoice.getInvoiceNumber(),
                invoice.getBuyerName(),
                invoice.getTotalAmount().toPlainString(),
                invoice.getCurrency(),
                invitationUrl,
                "BUYER_CONFIRMATION_REQUESTED"
        );

        String serializedPayload = serialize(outboxPayload);
        int keyVersion = encryptionService.getActiveKeyVersion();
        String encryptedPayload = encryptionService.encrypt(serializedPayload, "INVOICE", invoiceId, smeOrgId, keyVersion);

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventType("BUYER_CONFIRMATION_REQUESTED");
        outboxEvent.setAggregateType("INVOICE");
        outboxEvent.setAggregateId(invoiceId);
        outboxEvent.setTenantId(smeOrgId);
        outboxEvent.setPayloadEncrypted(encryptedPayload);
        outboxEvent.setKeyVersion(keyVersion);
        outboxEvent.setStatus("PENDING");
        outboxEventRepository.save(outboxEvent);

        return new BuyerConfirmationRequestDto(
                request.getId(),
                request.getInvoiceId(),
                request.getRecipientEmail(),
                request.getStatus(),
                request.getExpiresAt(),
                invitationUrl
        );
    }

    /**
     * Exchanges a raw URL fragment token for an authenticated 15-minute confirmation session token.
     * Universal lock order: invoices -> buyer_confirmation_requests -> buyer_confirmation_sessions.
     */
    @Transactional(noRollbackFor = ConfirmationSessionExpiredException.class)
    public TokenExchangeResponseDto exchangeToken(String rawToken, String clientIp, String userAgent, String authChannel, UUID portalUserId, UUID buyerOrgId) {
        checkRateLimit(clientIp);

        String secureTokenHash = computeSha256(rawToken);
        BuyerConfirmationRequest initialReq = requestRepository.findBySecureTokenHash(secureTokenHash)
                .orElseGet(() -> {
                    recordFailedThrottleAttempt(clientIp);
                    throw new InvalidConfirmationTokenException("Confirmation token is invalid or does not exist");
                });

        // Step 1: Lock invoice FIRST
        Invoice invoice = invoiceRepository.findForUpdate(initialReq.getInvoiceId(), initialReq.getSmeOrgId())
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice [" + initialReq.getInvoiceId() + "] not found"));

        // Step 2: Lock request SECOND
        BuyerConfirmationRequest req = requestRepository.findById(initialReq.getId())
                .orElseThrow(() -> new InvalidConfirmationTokenException("Confirmation request not found"));

        // Step 3: Lock sessions deterministically
        List<BuyerConfirmationSession> activeSessions = sessionRepository.findActiveByRequestId(req.getId());

        if (!"PENDING".equals(req.getStatus())) {
            throw new InvalidConfirmationTokenException("Confirmation request is no longer pending (status: " + req.getStatus() + ")");
        }

        if (req.getExpiresAt().isBefore(Instant.now())) {
            activeSessions.forEach(s -> s.setStatus("EXPIRED"));
            sessionRepository.saveAll(activeSessions);
            sessionRepository.flush();

            req.setStatus("EXPIRED");
            req.setUpdatedAt(Instant.now());
            requestRepository.save(req);
            requestRepository.flush();
            throw new ConfirmationSessionExpiredException("Confirmation request has expired");
        }

        // Revoke prior active sessions
        activeSessions.forEach(s -> s.setStatus("REVOKED"));
        sessionRepository.saveAll(activeSessions);

        // Generate session token
        String rawSessionToken = generateSecureToken();
        String sessionTokenHash = computeSha256(rawSessionToken);
        Instant sessionExpiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);

        BuyerConfirmationSession session = new BuyerConfirmationSession();
        session.setSessionTokenHash(sessionTokenHash);
        session.setRequestId(req.getId());
        session.setInvoiceId(invoice.getId());
        session.setSmeOrgId(req.getSmeOrgId());
        session.setAuthChannel(authChannel);
        session.setPortalUserId(portalUserId);
        session.setBuyerOrgId(buyerOrgId);
        session.setStatus("ACTIVE");
        session.setExpiresAt(sessionExpiresAt);
        session.setClientIp(clientIp);
        session.setUserAgentHash(computeSha256(userAgent != null ? userAgent : ""));
        session.setFencingGeneration(1L);
        session.setLeaseStatus("IDLE");
        sessionRepository.save(session);

        return new TokenExchangeResponseDto(
                rawSessionToken,
                sessionExpiresAt,
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getBuyerName(),
                invoice.getTotalAmount().toPlainString(),
                invoice.getCurrency()
        );
    }

    /**
     * Acquires a processing lease on an active confirmation session, incrementing monotonic fencing generation.
     * Universal lock order: invoices -> buyer_confirmation_requests -> buyer_confirmation_sessions.
     */
    @Transactional(noRollbackFor = ConfirmationSessionExpiredException.class)
    public SessionLeaseResult acquireSessionLease(String rawSessionToken, String idempotencyKey, String requestHash, int leaseSeconds) {
        String sessionTokenHash = computeSha256(rawSessionToken);
        BuyerConfirmationSession session = sessionRepository.findBySessionTokenHash(sessionTokenHash)
                .orElseThrow(() -> new ConfirmationSessionExpiredException("Confirmation session not found"));

        // Step 1: Lock invoice FIRST
        invoiceRepository.findForUpdate(session.getInvoiceId(), session.getSmeOrgId())
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice not found"));

        // Step 2: Lock request SECOND
        requestRepository.findById(session.getRequestId())
                .orElseThrow(() -> new InvalidConfirmationTokenException("Confirmation request not found"));

        // Step 3: Lock session THIRD
        session = sessionRepository.findById(session.getId())
                .orElseThrow(() -> new ConfirmationSessionExpiredException("Confirmation session not found"));

        if ("CONSUMED".equals(session.getStatus())) {
            if (!idempotencyKey.equals(session.getIdempotencyKey())) {
                throw new ConfirmationLeaseException("Session already consumed with a different idempotency key");
            }
            if (!requestHash.equals(session.getRequestHash())) {
                throw new ConfirmationLeaseException("Session already consumed with a different request payload hash");
            }
            if ("SUCCESS".equals(session.getLeaseStatus())) {
                return new SessionLeaseResult(session.getId(), session.getFencingGeneration(), true, session.getResponseStatusCode(), session.getResponseBody());
            }
        }

        if (!"ACTIVE".equals(session.getStatus())) {
            throw new ConfirmationSessionExpiredException("Confirmation session is no longer active (status: " + session.getStatus() + ")");
        }

        if (session.getExpiresAt().isBefore(Instant.now())) {
            session.setStatus("EXPIRED");
            session.setUpdatedAt(Instant.now());
            sessionRepository.save(session);
            sessionRepository.flush();
            throw new ConfirmationSessionExpiredException("Confirmation session has expired");
        }

        if ("PROCESSING".equals(session.getLeaseStatus())) {
            if (session.getLeaseExpiresAt() != null && session.getLeaseExpiresAt().isAfter(Instant.now())) {
                throw new ConfirmationLeaseException("Concurrent mutation in progress for this confirmation session");
            } else {
                throw new ConfirmationLeaseException("LEASE_EXPIRED_RECONCILIATION_REQUIRED");
            }
        }

        long nextGeneration = session.getFencingGeneration() + 1;
        session.setFencingGeneration(nextGeneration);
        session.setIdempotencyKey(idempotencyKey);
        session.setRequestHash(requestHash);
        session.setLeaseStatus("PROCESSING");
        session.setLeaseExpiresAt(Instant.now().plus(leaseSeconds, ChronoUnit.SECONDS));
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);

        return new SessionLeaseResult(session.getId(), nextGeneration, false, null, null);
    }

    /**
     * Submits a buyer confirmation response, updating invoice status and constructing canonical legal audit evidence.
     * Universal lock order: invoices -> buyer_confirmation_requests -> buyer_confirmation_sessions.
     */
    @Transactional(noRollbackFor = ConfirmationSessionExpiredException.class)
    public BuyerConfirmationResponseDto respondToConfirmation(
            String rawSessionToken,
            long expectedGeneration,
            String idempotencyKey,
            SubmitConfirmationRequest request,
            String clientIp,
            String userAgent,
            String authChannel,
            UUID portalUserId,
            UUID buyerOrgId
    ) {
        String sessionTokenHash = computeSha256(rawSessionToken);
        BuyerConfirmationSession session = sessionRepository.findBySessionTokenHash(sessionTokenHash)
                .orElseThrow(() -> new ConfirmationSessionExpiredException("Confirmation session not found"));

        // Step 1: Lock invoice FIRST
        Invoice invoice = invoiceRepository.findForUpdate(session.getInvoiceId(), session.getSmeOrgId())
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice not found"));

        // Step 2: Lock request SECOND
        BuyerConfirmationRequest req = requestRepository.findById(session.getRequestId())
                .orElseThrow(() -> new InvalidConfirmationTokenException("Confirmation request not found"));

        // Step 3: Lock session THIRD
        session = sessionRepository.findById(session.getId())
                .orElseThrow(() -> new ConfirmationSessionExpiredException("Confirmation session not found"));

        String requestHash = computeSha256(serialize(request));

        // Replay Check
        if ("CONSUMED".equals(session.getStatus())) {
            if (!idempotencyKey.equals(session.getIdempotencyKey())) {
                throw new ConfirmationLeaseException("Session already consumed with a different idempotency key");
            }
            if (!requestHash.equals(session.getRequestHash())) {
                throw new ConfirmationLeaseException("Session already consumed with a different request payload hash");
            }
            if ("SUCCESS".equals(session.getLeaseStatus())) {
                return deserializeResponseDto(session.getResponseBody());
            }
        }

        // Validate Fencing and State
        if (!"ACTIVE".equals(session.getStatus())) {
            throw new ConfirmationSessionExpiredException("Confirmation session is not active");
        }
        if (session.getFencingGeneration() != expectedGeneration) {
            throw new FencingGenerationException("Stale fencing generation [" + expectedGeneration + "]; expected [" + session.getFencingGeneration() + "]");
        }
        if (!"PROCESSING".equals(session.getLeaseStatus())) {
            throw new ConfirmationLeaseException("Confirmation lease not held in PROCESSING state");
        }
        if (session.getLeaseExpiresAt() != null && session.getLeaseExpiresAt().isBefore(Instant.now())) {
            throw new ConfirmationLeaseException("LEASE_EXPIRED_RECONCILIATION_REQUIRED");
        }
        if (!"PENDING".equals(req.getStatus())) {
            throw new InvalidConfirmationTokenException("Confirmation request is not PENDING");
        }
        if (req.getExpiresAt().isBefore(Instant.now())) {
            Instant now = Instant.now();
            session.setStatus("EXPIRED");
            session.setUpdatedAt(now);
            sessionRepository.save(session);
            req.setStatus("EXPIRED");
            req.setUpdatedAt(now);
            requestRepository.save(req);
            sessionRepository.flush();
            requestRepository.flush();
            throw new ConfirmationSessionExpiredException("Confirmation request has expired");
        }
        if (!"PENDING_VERIFICATION".equals(invoice.getStatus())) {
            throw new InvalidInvoiceStateException("Invoice is not in PENDING_VERIFICATION state");
        }

        // Enforce Buyer Identity Chain if PORTAL_USER
        String identityAssurance;
        if ("PORTAL_USER".equals(authChannel)) {
            identityAssurance = "AUTHENTICATED_CORPORATE_PORTAL";
            Optional<OrganizationTaxProfile> taxProfile = taxProfileRepository.findActiveVerifiedByOrgIdAndGstin(buyerOrgId, invoice.getBuyerGstin());
            if (taxProfile.isEmpty()) {
                throw new BuyerTaxProfileException("Buyer organization does not possess an active verified tax profile matching invoice GSTIN [" + invoice.getBuyerGstin() + "]");
            }
        } else {
            identityAssurance = "BEARER_TOKEN_POSSESSION";
        }

        // Retrieve active document
        InvoiceDocument activeDoc = documentRepository.findByInvoiceIdAndActiveTrue(invoice.getId())
                .orElseThrow(() -> new DocumentRequiredException("Active document required for confirmation"));

        Instant now = Instant.now();

        // Construct Authoritative Canonical Evidence
        String canonicalEvidenceText = evidenceService.buildCanonicalEvidence(
                request.action(),
                authChannel,
                invoice.getBuyerGstin(),
                buyerOrgId,
                "TOKEN_LINK".equals(authChannel) ? request.claimedEmail() : null,
                now,
                request.disputeCategory(),
                activeDoc.getSha256Checksum(),
                invoice.getDueDate(),
                identityAssurance,
                invoice.getInvoiceDate(),
                invoice.getId(),
                invoice.getInvoiceNumber(),
                request.notes(),
                portalUserId,
                req.getId(),
                invoice.getSmeOrgId(),
                invoice.getTaxAmount(),
                invoice.getTaxableAmount(),
                invoice.getTotalAmount()
        );
        String evidenceSha256 = evidenceService.computeSha256(canonicalEvidenceText);

        BuyerConfirmationResponse response = new BuyerConfirmationResponse();
        response.setRequestId(req.getId());
        response.setInvoiceId(invoice.getId());
        response.setSmeOrgId(invoice.getSmeOrgId());
        response.setAction(request.action());
        response.setDisputeCategory(request.disputeCategory());
        response.setNotes(request.notes());
        response.setAuthChannel(authChannel);
        response.setPortalUserId(portalUserId);
        response.setBuyerOrgId(buyerOrgId);
        response.setClaimedEmail("TOKEN_LINK".equals(authChannel) ? request.claimedEmail() : null);
        response.setIdentityAssurance(identityAssurance);
        response.setClientIp(clientIp);
        response.setUserAgentHash(computeSha256(userAgent != null ? userAgent : ""));
        response.setCanonicalEvidenceText(canonicalEvidenceText);
        response.setEvidencePayload(canonicalEvidenceText);
        response.setEvidenceSha256(evidenceSha256);
        response.setConfirmedAt(now);
        response = responseRepository.save(response);

        // Mutate Invoice
        if ("CONFIRMED".equals(request.action())) {
            invoice.setStatus("VERIFIED");
            invoice.setVerifiedConfirmationId(response.getId());
        } else {
            invoice.setStatus("REJECTED");
            invoice.setRejectionReason(request.notes() != null ? request.notes() : request.disputeCategory());
        }
        invoice.setLastBuyerResponseId(response.getId());
        invoice.setUpdatedAt(now);
        invoiceRepository.save(invoice);

        // Mutate Request to Terminal State
        req.setStatus(request.action());
        req.setRespondedAt(now);
        req.setUpdatedAt(now);
        requestRepository.save(req);

        // Build Response DTO
        BuyerConfirmationResponseDto dto = new BuyerConfirmationResponseDto(
                response.getId(),
                response.getAction(),
                invoice.getStatus(),
                response.getConfirmedAt(),
                response.getEvidenceSha256()
        );
        String responseBody = serialize(dto);

        // Mutate Session to CONSUMED
        session.setStatus("CONSUMED");
        session.setLeaseStatus("SUCCESS");
        session.setResponseId(response.getId());
        session.setResponseAction(request.action());
        session.setResponseStatusCode(200);
        session.setResponseBody(responseBody);
        session.setUpdatedAt(now);
        sessionRepository.save(session);

        // Revoke sibling sessions
        final UUID currentSessionId = session.getId();
        List<BuyerConfirmationSession> siblingSessions = sessionRepository.findActiveByRequestId(req.getId());
        siblingSessions.forEach(s -> {
            if (!s.getId().equals(currentSessionId)) {
                s.setStatus("REVOKED");
                s.setUpdatedAt(now);
            }
        });
        sessionRepository.saveAll(siblingSessions);

        return dto;
    }

    /**
     * Cancels a pending confirmation request.
     */
    @Transactional
    public void cancelRequest(UUID smeOrgId, UUID userId, UUID requestId) {
        BuyerConfirmationRequest req = requestRepository.findByIdAndSmeOrgId(requestId, smeOrgId)
                .orElseThrow(() -> new InvalidConfirmationTokenException("Confirmation request [" + requestId + "] not found"));

        invoiceRepository.findForUpdate(req.getInvoiceId(), smeOrgId)
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice not found"));

        req = requestRepository.findById(req.getId()).orElseThrow();
        if (!"PENDING".equals(req.getStatus())) {
            throw new InvalidInvoiceStateException("Only PENDING confirmation requests can be cancelled (status: " + req.getStatus() + ")");
        }

        List<BuyerConfirmationSession> sessions = sessionRepository.findActiveByRequestId(req.getId());
        sessions.forEach(s -> s.setStatus("REVOKED"));
        sessionRepository.saveAll(sessions);

        req.setStatus("CANCELLED");
        req.setUpdatedAt(Instant.now());
        requestRepository.save(req);
    }

    /**
     * Resends a buyer confirmation request.
     */
    @Transactional
    public BuyerConfirmationRequestDto resendRequest(UUID smeOrgId, UUID userId, UUID priorRequestId, String newRecipient, String baseUrl) {
        BuyerConfirmationRequest oldReq = requestRepository.findByIdAndSmeOrgId(priorRequestId, smeOrgId)
                .orElseThrow(() -> new InvalidConfirmationTokenException("Prior confirmation request not found"));

        Invoice invoice = invoiceRepository.findForUpdate(oldReq.getInvoiceId(), smeOrgId)
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice not found"));

        if (!"PENDING_VERIFICATION".equals(invoice.getStatus())) {
            throw new InvalidInvoiceStateException("Cannot resend confirmation: invoice is not in PENDING_VERIFICATION");
        }

        oldReq = requestRepository.findById(oldReq.getId()).orElseThrow();
        if ("PENDING".equals(oldReq.getStatus())) {
            List<BuyerConfirmationSession> sessions = sessionRepository.findActiveByRequestId(oldReq.getId());
            sessions.forEach(s -> s.setStatus("REVOKED"));
            sessionRepository.saveAll(sessions);

            oldReq.setStatus("CANCELLED");
            oldReq.setUpdatedAt(Instant.now());
            requestRepository.save(oldReq);
        }

        String recipient = (newRecipient != null && !newRecipient.isBlank()) ? newRecipient.trim().toLowerCase() : oldReq.getRecipientEmail();
        String rawToken = generateSecureToken();
        String secureTokenHash = computeSha256(rawToken);
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        BuyerConfirmationRequest newReq = new BuyerConfirmationRequest();
        newReq.setInvoiceId(invoice.getId());
        newReq.setSmeOrgId(smeOrgId);
        newReq.setBuyerOrgId(oldReq.getBuyerOrgId());
        newReq.setBuyerGstin(invoice.getBuyerGstin());
        newReq.setRecipientEmail(recipient);
        newReq.setSecureTokenHash(secureTokenHash);
        newReq.setStatus("PENDING");
        newReq.setExpiresAt(expiresAt);
        newReq = requestRepository.save(newReq);

        String invitationUrl = baseUrl + "/confirm#token=" + rawToken;

        BuyerNotificationPayload outboxPayload = new BuyerNotificationPayload(
                invoice.getId(),
                newReq.getId(),
                recipient,
                invoice.getInvoiceNumber(),
                invoice.getBuyerName(),
                invoice.getTotalAmount().toPlainString(),
                invoice.getCurrency(),
                invitationUrl,
                "BUYER_CONFIRMATION_RESENT"
        );

        String serializedPayload = serialize(outboxPayload);
        int keyVersion = encryptionService.getActiveKeyVersion();
        String encryptedPayload = encryptionService.encrypt(serializedPayload, "INVOICE", invoice.getId(), smeOrgId, keyVersion);

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventType("BUYER_CONFIRMATION_RESENT");
        outboxEvent.setAggregateType("INVOICE");
        outboxEvent.setAggregateId(invoice.getId());
        outboxEvent.setTenantId(smeOrgId);
        outboxEvent.setPayloadEncrypted(encryptedPayload);
        outboxEvent.setKeyVersion(keyVersion);
        outboxEvent.setStatus("PENDING");
        outboxEventRepository.save(outboxEvent);

        return new BuyerConfirmationRequestDto(
                newReq.getId(),
                newReq.getInvoiceId(),
                newReq.getRecipientEmail(),
                newReq.getStatus(),
                newReq.getExpiresAt(),
                invitationUrl
        );
    }

    private void checkRateLimit(String clientIp) {
        Optional<ConfirmationAttemptThrottle> throttleOpt = throttleRepository.findByClientIp(clientIp);
        if (throttleOpt.isPresent()) {
            ConfirmationAttemptThrottle throttle = throttleOpt.get();
            if (throttle.getThrottledUntil() != null && throttle.getThrottledUntil().isAfter(Instant.now())) {
                throw new RateLimitExceededException("Too many failed attempts from IP [" + clientIp + "]. Throttled until [" + throttle.getThrottledUntil() + "]");
            }
        }
    }

    private void recordFailedThrottleAttempt(String clientIp) {
        Runnable action = () -> {
            ConfirmationAttemptThrottle throttle = throttleRepository.findByClientIp(clientIp)
                    .orElseGet(() -> {
                        ConfirmationAttemptThrottle t = new ConfirmationAttemptThrottle();
                        t.setClientIp(clientIp);
                        return t;
                    });

            int fails = throttle.getFailedAttempts() + 1;
            throttle.setFailedAttempts(fails);
            throttle.setLastAttemptAt(Instant.now());

            if (fails >= 5) {
                throttle.setThrottledUntil(Instant.now().plus(15, ChronoUnit.MINUTES));
            } else if (fails >= 3) {
                throttle.setThrottledUntil(Instant.now().plus(1, ChronoUnit.MINUTES));
            }

            throttleRepository.save(throttle);
        };

        if (transactionManager != null) {
            TransactionTemplate requiresNewTemplate = new TransactionTemplate(transactionManager);
            requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            requiresNewTemplate.executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Serialization failure", e);
        }
    }

    private BuyerConfirmationResponseDto deserializeResponseDto(String json) {
        try {
            return objectMapper.readValue(json, BuyerConfirmationResponseDto.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Deserialization failure", e);
        }
    }
}
