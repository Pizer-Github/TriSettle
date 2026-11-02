package com.tcs.invoicefactoring.invoice.confirmation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.common.outbox.domain.OutboxEvent;
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
import com.tcs.invoicefactoring.invoice.confirmation.exception.InvalidConfirmationTokenException;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationRequestRepository;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationResponseRepository;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationSessionRepository;
import com.tcs.invoicefactoring.invoice.confirmation.repository.ConfirmationAttemptThrottleRepository;
import com.tcs.invoicefactoring.invoice.domain.Invoice;
import com.tcs.invoicefactoring.invoice.domain.InvoiceDocument;
import com.tcs.invoicefactoring.invoice.exception.InvalidInvoiceStateException;
import com.tcs.invoicefactoring.invoice.repository.InvoiceDocumentRepository;
import com.tcs.invoicefactoring.invoice.repository.InvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuyerConfirmationMutationServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceDocumentRepository documentRepository;
    @Mock
    private BuyerConfirmationRequestRepository requestRepository;
    @Mock
    private BuyerConfirmationSessionRepository sessionRepository;
    @Mock
    private BuyerConfirmationResponseRepository responseRepository;
    @Mock
    private OrganizationTaxProfileRepository taxProfileRepository;
    @Mock
    private ConfirmationAttemptThrottleRepository throttleRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private NotificationEnvelopeEncryptionService encryptionService;

    private EvidenceSerializationService evidenceService;
    private ObjectMapper objectMapper;
    private BuyerConfirmationMutationService service;

    private UUID smeOrgId;
    private UUID userId;
    private UUID invoiceId;
    private Invoice testInvoice;

    @BeforeEach
    void setUp() {
        evidenceService = new EvidenceSerializationService();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        service = new BuyerConfirmationMutationService(
                invoiceRepository,
                documentRepository,
                requestRepository,
                sessionRepository,
                responseRepository,
                taxProfileRepository,
                throttleRepository,
                outboxEventRepository,
                encryptionService,
                evidenceService,
                objectMapper
        );

        smeOrgId = UUID.randomUUID();
        userId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();

        testInvoice = new Invoice();
        testInvoice.setId(invoiceId);
        testInvoice.setSmeOrgId(smeOrgId);
        testInvoice.setBuyerName("Acme Corp");
        testInvoice.setBuyerGstin("27AABCU9603R1ZM");
        testInvoice.setInvoiceNumber("INV-001");
        testInvoice.setInvoiceDate(LocalDate.now());
        testInvoice.setDueDate(LocalDate.now().plusDays(30));
        testInvoice.setTaxableAmount(new BigDecimal("10000.00"));
        testInvoice.setTaxAmount(new BigDecimal("1800.00"));
        testInvoice.setTotalAmount(new BigDecimal("11800.00"));
        testInvoice.setStatus("PENDING_VERIFICATION");
    }

    @Test
    void requestConfirmation_failsIfInvoiceNotPendingVerification() {
        testInvoice.setStatus("DRAFT");
        when(invoiceRepository.findForUpdate(invoiceId, smeOrgId)).thenReturn(Optional.of(testInvoice));

        assertThatThrownBy(() -> service.requestConfirmation(smeOrgId, userId, invoiceId, "buyer@acme.com", "http://localhost:3000"))
                .isInstanceOf(InvalidInvoiceStateException.class)
                .hasMessageContaining("must be PENDING_VERIFICATION");
    }

    @Test
    void requestConfirmation_success_createsRequestAndOutboxEvent() {
        when(invoiceRepository.findForUpdate(invoiceId, smeOrgId)).thenReturn(Optional.of(testInvoice));
        when(requestRepository.findPendingByInvoiceId(invoiceId)).thenReturn(Optional.empty());
        when(taxProfileRepository.findActiveVerifiedByGstin("27AABCU9603R1ZM")).thenReturn(Optional.empty());
        when(encryptionService.getActiveKeyVersion()).thenReturn(1);
        when(encryptionService.encrypt(any(), eq("INVOICE"), eq(invoiceId), eq(smeOrgId), eq(1)))
                .thenReturn("encrypted-payload");
        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BuyerConfirmationRequestDto result = service.requestConfirmation(
                smeOrgId, userId, invoiceId, "buyer@acme.com", "http://localhost:3000"
        );

        assertThat(result.invoiceId()).isEqualTo(invoiceId);
        assertThat(result.recipientEmail()).isEqualTo("buyer@acme.com");
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.invitationUrl()).contains("/confirm#token=");

        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void exchangeToken_failsIfTokenNotFound() {
        when(throttleRepository.findByClientIp("127.0.0.1")).thenReturn(Optional.empty());
        when(requestRepository.findBySecureTokenHash(any())).thenReturn(Optional.empty());
        when(throttleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.exchangeToken("invalid-raw-token", "127.0.0.1", "curl", "TOKEN_LINK", null, null))
                .isInstanceOf(InvalidConfirmationTokenException.class);
    }

    @Test
    void exchangeToken_success_issuesSession() {
        BuyerConfirmationRequest req = new BuyerConfirmationRequest();
        req.setInvoiceId(invoiceId);
        req.setSmeOrgId(smeOrgId);
        req.setStatus("PENDING");
        req.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));

        when(throttleRepository.findByClientIp("127.0.0.1")).thenReturn(Optional.empty());
        when(requestRepository.findBySecureTokenHash(any())).thenReturn(Optional.of(req));
        when(invoiceRepository.findForUpdate(invoiceId, smeOrgId)).thenReturn(Optional.of(testInvoice));
        when(requestRepository.findForUpdate(req.getId())).thenReturn(Optional.of(req));
        when(sessionRepository.findActiveByRequestId(req.getId())).thenReturn(Collections.emptyList());
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TokenExchangeResponseDto result = service.exchangeToken("valid-token", "127.0.0.1", "curl", "TOKEN_LINK", null, null);

        assertThat(result.confirmationSessionToken()).isNotBlank();
        assertThat(result.invoiceNumber()).isEqualTo("INV-001");
        assertThat(result.buyerName()).isEqualTo("Acme Corp");
    }

    @Test
    void respondToConfirmation_portalUser_failsIfTaxProfileNotVerified() {
        BuyerConfirmationSession session = new BuyerConfirmationSession();
        session.setInvoiceId(invoiceId);
        session.setSmeOrgId(smeOrgId);
        session.setStatus("ACTIVE");
        session.setFencingGeneration(2L);
        session.setLeaseStatus("PROCESSING");
        session.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        session.setExpiresAt(Instant.now().plusSeconds(900));

        BuyerConfirmationRequest req = new BuyerConfirmationRequest();
        req.setStatus("PENDING");
        req.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));

        when(sessionRepository.findBySessionTokenHash(any())).thenReturn(Optional.of(session));
        when(invoiceRepository.findForUpdate(invoiceId, smeOrgId)).thenReturn(Optional.of(testInvoice));
        when(requestRepository.findById(session.getRequestId())).thenReturn(Optional.of(req));
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        UUID buyerOrgId = UUID.randomUUID();
        when(taxProfileRepository.findActiveVerifiedByOrgIdAndGstin(buyerOrgId, "27AABCU9603R1ZM"))
                .thenReturn(Optional.empty()); // No active verified profile

        SubmitConfirmationRequest submitReq = new SubmitConfirmationRequest("CONFIRMED", null, "Verified", null);

        assertThatThrownBy(() -> service.respondToConfirmation(
                "token", 2L, "key-1", submitReq, "127.0.0.1", "curl", "PORTAL_USER", UUID.randomUUID(), buyerOrgId
        )).isInstanceOf(BuyerTaxProfileException.class);
    }

    @Test
    void respondToConfirmation_confirmed_transitionsInvoiceToVerified() {
        BuyerConfirmationSession session = new BuyerConfirmationSession();
        session.setInvoiceId(invoiceId);
        session.setSmeOrgId(smeOrgId);
        session.setStatus("ACTIVE");
        session.setFencingGeneration(2L);
        session.setLeaseStatus("PROCESSING");
        session.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        session.setExpiresAt(Instant.now().plusSeconds(900));

        BuyerConfirmationRequest req = new BuyerConfirmationRequest();
        req.setStatus("PENDING");
        req.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));

        InvoiceDocument doc = new InvoiceDocument();
        doc.setInvoiceId(invoiceId);
        doc.setActive(true);
        doc.setSha256Checksum("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        when(sessionRepository.findBySessionTokenHash(any())).thenReturn(Optional.of(session));
        when(invoiceRepository.findForUpdate(invoiceId, smeOrgId)).thenReturn(Optional.of(testInvoice));
        when(requestRepository.findById(session.getRequestId())).thenReturn(Optional.of(req));
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(documentRepository.findByInvoiceIdAndActiveTrue(invoiceId)).thenReturn(Optional.of(doc));
        when(responseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.findActiveByRequestId(req.getId())).thenReturn(Collections.emptyList());

        SubmitConfirmationRequest submitReq = new SubmitConfirmationRequest("CONFIRMED", null, "Confirmed all terms", "claimed@acme.com");

        BuyerConfirmationResponseDto responseDto = service.respondToConfirmation(
                "token", 2L, "key-1", submitReq, "127.0.0.1", "curl", "TOKEN_LINK", null, null
        );

        assertThat(responseDto.action()).isEqualTo("CONFIRMED");
        assertThat(responseDto.invoiceStatus()).isEqualTo("VERIFIED");
        assertThat(testInvoice.getStatus()).isEqualTo("VERIFIED");
        assertThat(testInvoice.getVerifiedConfirmationId()).isNotNull();
    }

    @Test
    void respondToConfirmation_disputed_transitionsInvoiceToRejected() {
        BuyerConfirmationSession session = new BuyerConfirmationSession();
        session.setInvoiceId(invoiceId);
        session.setSmeOrgId(smeOrgId);
        session.setStatus("ACTIVE");
        session.setFencingGeneration(2L);
        session.setLeaseStatus("PROCESSING");
        session.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        session.setExpiresAt(Instant.now().plusSeconds(900));

        BuyerConfirmationRequest req = new BuyerConfirmationRequest();
        req.setStatus("PENDING");
        req.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));

        InvoiceDocument doc = new InvoiceDocument();
        doc.setInvoiceId(invoiceId);
        doc.setActive(true);
        doc.setSha256Checksum("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        when(sessionRepository.findBySessionTokenHash(any())).thenReturn(Optional.of(session));
        when(invoiceRepository.findForUpdate(invoiceId, smeOrgId)).thenReturn(Optional.of(testInvoice));
        when(requestRepository.findById(session.getRequestId())).thenReturn(Optional.of(req));
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(documentRepository.findByInvoiceIdAndActiveTrue(invoiceId)).thenReturn(Optional.of(doc));
        when(responseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.findActiveByRequestId(req.getId())).thenReturn(Collections.emptyList());

        SubmitConfirmationRequest submitReq = new SubmitConfirmationRequest(
                "DISPUTED", "INVOICE_AMOUNT_MISMATCH", "Price does not match contract", "claimed@acme.com"
        );

        BuyerConfirmationResponseDto responseDto = service.respondToConfirmation(
                "token", 2L, "key-1", submitReq, "127.0.0.1", "curl", "TOKEN_LINK", null, null
        );

        assertThat(responseDto.action()).isEqualTo("DISPUTED");
        assertThat(responseDto.invoiceStatus()).isEqualTo("REJECTED");
        assertThat(testInvoice.getStatus()).isEqualTo("REJECTED");
        assertThat(testInvoice.getRejectionReason()).isEqualTo("Price does not match contract");
    }
}
