package com.tcs.invoicefactoring.invoice.confirmation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.common.outbox.domain.OutboxEvent;
import com.tcs.invoicefactoring.common.outbox.repository.OutboxEventRepository;
import com.tcs.invoicefactoring.identity.domain.OrganizationTaxProfile;
import com.tcs.invoicefactoring.identity.dto.RegisterRequest;
import com.tcs.invoicefactoring.identity.repository.OrganizationTaxProfileRepository;
import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationRequest;
import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationSession;
import com.tcs.invoicefactoring.invoice.confirmation.dto.RequestConfirmationDto;
import com.tcs.invoicefactoring.invoice.confirmation.dto.SubmitConfirmationRequest;
import com.tcs.invoicefactoring.invoice.confirmation.dto.TokenExchangeRequest;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationRequestRepository;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationSessionRepository;
import com.tcs.invoicefactoring.invoice.domain.Invoice;
import com.tcs.invoicefactoring.invoice.dto.CreateInvoiceRequest;
import com.tcs.invoicefactoring.invoice.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class BuyerConfirmationWorkflowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("test_db")
            .withUsername("test_owner")
            .withPassword("test_password");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private BuyerConfirmationRequestRepository requestRepository;

    @Autowired
    private BuyerConfirmationSessionRepository sessionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OrganizationTaxProfileRepository taxProfileRepository;

    @Test
    void endToEnd_buyerConfirmation_tokenChannel_confirmsInvoice() throws Exception {
        // 1. Setup SME & Invoice submitted to PENDING_VERIFICATION
        String smeToken = registerSme("sme1@supplier.com", "Supplier One Pvt Ltd");
        String invoiceId = createAndSubmitInvoice(smeToken, "27AABCU9603R1ZN", "INV-1001", "10000.00", "1800.00", "11800.00");

        // 2. SME requests confirmation
        RequestConfirmationDto requestDto = new RequestConfirmationDto("debtor.ap@bigbuyer.com");
        MvcResult requestResult = mockMvc.perform(post("/api/v1/sme/invoices/" + invoiceId + "/confirmation-request")
                        .header("Authorization", "Bearer " + smeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.invitationUrl", containsString("#token=")))
                .andReturn();

        JsonNode requestNode = objectMapper.readTree(requestResult.getResponse().getContentAsString());
        String invitationUrl = requestNode.path("data").path("invitationUrl").asText();
        String rawToken = invitationUrl.substring(invitationUrl.indexOf("#token=") + 7);

        // Verify Outbox Event created
        List<OutboxEvent> outboxEvents = outboxEventRepository.findByAggregateId(UUID.fromString(invoiceId));
        assertThat(outboxEvents).isNotEmpty();
        assertThat(outboxEvents.get(0).getEventType()).isEqualTo("BUYER_CONFIRMATION_REQUESTED");

        // 3. Buyer exchanges raw token for confirmation session
        TokenExchangeRequest exchangeReq = new TokenExchangeRequest(rawToken);
        MvcResult exchangeResult = mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                        .header("X-Forwarded-For", "203.0.113.195")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(exchangeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.confirmationSessionToken").isNotEmpty())
                .andExpect(jsonPath("$.data.invoiceNumber").value("INV-1001"))
                .andExpect(jsonPath("$.data.totalAmount").value("11800.00"))
                .andReturn();

        JsonNode exchangeNode = objectMapper.readTree(exchangeResult.getResponse().getContentAsString());
        String sessionToken = exchangeNode.path("data").path("confirmationSessionToken").asText();

        // 4. Buyer downloads invoice document using session token
        mockMvc.perform(get("/api/v1/buyer/confirmations/document")
                        .header("X-Confirmation-Session", sessionToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist()); // PDF binary stream

        // 5. Buyer responds with CONFIRMED
        SubmitConfirmationRequest confirmReq = new SubmitConfirmationRequest(
                "CONFIRMED", null, "Goods received and matched with Purchase Order PO-5512", "debtor.ap@bigbuyer.com"
        );
        String idempotencyKey = "resp-key-" + UUID.randomUUID();

        MvcResult confirmResult = mockMvc.perform(post("/api/v1/buyer/confirmations/respond")
                        .header("X-Confirmation-Session", sessionToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Forwarded-For", "203.0.113.195")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.action").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.invoiceStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.evidenceSha256").isNotEmpty())
                .andReturn();

        // 6. Assert DB state: Invoice is VERIFIED and bound to verified_confirmation_id
        Invoice invoice = invoiceRepository.findById(UUID.fromString(invoiceId)).orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo("VERIFIED");
        assertThat(invoice.getVerifiedConfirmationId()).isNotNull();

        // 7. Idempotent replay: retrying with exact same session token and key returns identical outcome
        mockMvc.perform(post("/api/v1/buyer/confirmations/respond")
                        .header("X-Confirmation-Session", sessionToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Forwarded-For", "203.0.113.195")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.action").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.invoiceStatus").value("VERIFIED"));

        // 8. Replay with mismatched payload yields HTTP 409 Conflict
        SubmitConfirmationRequest alteredReq = new SubmitConfirmationRequest(
                "CONFIRMED", null, "Altered notes payload", "debtor.ap@bigbuyer.com"
        );
        mockMvc.perform(post("/api/v1/buyer/confirmations/respond")
                        .header("X-Confirmation-Session", sessionToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Forwarded-For", "203.0.113.195")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(alteredReq)))
                .andExpect(status().isConflict());
    }

    @Test
    void buyerDispute_transitionsInvoiceToRejected() throws Exception {
        String smeToken = registerSme("sme2@supplier.com", "Supplier Two Pvt Ltd");
        String invoiceId = createAndSubmitInvoice(smeToken, "27AABCU9603R1ZN", "INV-2002", "20000.00", "3600.00", "23600.00");

        // SME requests confirmation
        RequestConfirmationDto requestDto = new RequestConfirmationDto("debtor2@bigbuyer.com");
        MvcResult requestResult = mockMvc.perform(post("/api/v1/sme/invoices/" + invoiceId + "/confirmation-request")
                        .header("Authorization", "Bearer " + smeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode requestNode = objectMapper.readTree(requestResult.getResponse().getContentAsString());
        String invitationUrl = requestNode.path("data").path("invitationUrl").asText();
        String rawToken = invitationUrl.substring(invitationUrl.indexOf("#token=") + 7);

        // Exchange session
        TokenExchangeRequest exchangeReq = new TokenExchangeRequest(rawToken);
        MvcResult exchangeResult = mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(exchangeReq)))
                .andExpect(status().isOk())
                .andReturn();

        String sessionToken = objectMapper.readTree(exchangeResult.getResponse().getContentAsString())
                .path("data").path("confirmationSessionToken").asText();

        // Submit Dispute
        SubmitConfirmationRequest disputeReq = new SubmitConfirmationRequest(
                "DISPUTED", "GOODS_DEFECTIVE_OR_DAMAGED", "Batch rejected due to defect in delivery", "debtor2@bigbuyer.com"
        );

        mockMvc.perform(post("/api/v1/buyer/confirmations/respond")
                        .header("X-Confirmation-Session", sessionToken)
                        .header("Idempotency-Key", "disp-key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disputeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("DISPUTED"))
                .andExpect(jsonPath("$.data.invoiceStatus").value("REJECTED"));

        Invoice invoice = invoiceRepository.findById(UUID.fromString(invoiceId)).orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo("REJECTED");
        assertThat(invoice.getRejectionReason()).contains("Batch rejected due to defect in delivery");
    }

    @Test
    void smeCancellation_revokesBuyerSessions() throws Exception {
        String smeToken = registerSme("sme3@supplier.com", "Supplier Three Pvt Ltd");
        String invoiceId = createAndSubmitInvoice(smeToken, "27AABCU9603R1ZN", "INV-3003", "15000.00", "2700.00", "17700.00");

        // SME requests confirmation
        RequestConfirmationDto requestDto = new RequestConfirmationDto("debtor3@bigbuyer.com");
        MvcResult requestResult = mockMvc.perform(post("/api/v1/sme/invoices/" + invoiceId + "/confirmation-request")
                        .header("Authorization", "Bearer " + smeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDto)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode requestNode = objectMapper.readTree(requestResult.getResponse().getContentAsString());
        String requestId = requestNode.path("data").path("requestId").asText();
        String invitationUrl = requestNode.path("data").path("invitationUrl").asText();
        String rawToken = invitationUrl.substring(invitationUrl.indexOf("#token=") + 7);

        // Buyer exchanges token
        TokenExchangeRequest exchangeReq = new TokenExchangeRequest(rawToken);
        MvcResult exchangeResult = mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(exchangeReq)))
                .andExpect(status().isOk())
                .andReturn();

        String sessionToken = objectMapper.readTree(exchangeResult.getResponse().getContentAsString())
                .path("data").path("confirmationSessionToken").asText();

        // SME cancels confirmation
        mockMvc.perform(post("/api/v1/sme/invoices/" + invoiceId + "/confirmation-request/cancel?requestId=" + requestId)
                        .header("Authorization", "Bearer " + smeToken))
                .andExpect(status().isOk());

        // Buyer attempts to respond with session -> session was revoked!
        SubmitConfirmationRequest confirmReq = new SubmitConfirmationRequest(
                "CONFIRMED", null, "Trying to confirm cancelled request", "debtor3@bigbuyer.com"
        );
        mockMvc.perform(post("/api/v1/buyer/confirmations/respond")
                        .header("X-Confirmation-Session", sessionToken)
                        .header("Idempotency-Key", "canc-key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmReq)))
                .andExpect(status().isGone());
    }

    @Test
    void rateLimiting_failedExchangeAttemptsTriggerProgressiveBackoff() throws Exception {
        String testIp = "198.51.100." + (int) (Math.random() * 200 + 10);
        TokenExchangeRequest invalidReq = new TokenExchangeRequest("completely-invalid-token");

        // 3 failed attempts
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                            .header("X-Forwarded-For", testIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidReq)))
                    .andExpect(status().isNotFound());
        }

        // 4th attempt should be blocked with 429 Too Many Requests
        mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                        .header("X-Forwarded-For", testIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isTooManyRequests());
    }

    private String registerSme(String email, String orgName) throws Exception {
        RegisterRequest registerRequest = new RegisterRequest(email, "Password@123", "SME Admin", orgName);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    private String createAndSubmitInvoice(String smeToken, String buyerGstin, String invoiceNumber, String taxable, String tax, String total) throws Exception {
        // Create draft
        CreateInvoiceRequest createReq = new CreateInvoiceRequest(
                "Big Buyer Corp",
                buyerGstin,
                null,
                invoiceNumber,
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                "INR",
                taxable,
                tax,
                total
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", "inv-key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String invoiceId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // Upload PDF document
        byte[] pdfContent = "%PDF-1.4 sample invoice content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "invoice-" + invoiceNumber + ".pdf", "application/pdf", pdfContent);

        mockMvc.perform(multipart("/api/v1/invoices/" + invoiceId + "/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + smeToken))
                .andExpect(status().isCreated());

        // Submit invoice for verification
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/submit")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", "sub-key-" + UUID.randomUUID()))
                .andExpect(status().isOk());

        return invoiceId;
    }
}
