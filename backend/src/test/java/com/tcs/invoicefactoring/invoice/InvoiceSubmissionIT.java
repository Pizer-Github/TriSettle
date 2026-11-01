package com.tcs.invoicefactoring.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.identity.dto.RegisterRequest;
import com.tcs.invoicefactoring.invoice.dto.CreateInvoiceRequest;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class InvoiceSubmissionIT {

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

    private String registerSme(String email, String orgName) throws Exception {
        RegisterRequest reg = new RegisterRequest(email, "StrongPass123!", "SME User", orgName);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private String createInvoice(String token) throws Exception {
        CreateInvoiceRequest req = new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZW",
                "AAACT2727Q",
                "INV-" + UUID.randomUUID(),
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                "INR",
                "100000.00",
                "18000.00",
                "118000.00"
        );
        MvcResult res = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return root.path("data").path("id").asText();
    }

    private void attachDocument(String token, String invoiceId) throws Exception {
        byte[] pdfBytes = ("%PDF-1.4 invoice pdf " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", pdfBytes);

        mockMvc.perform(multipart("/api/v1/invoices/{id}/documents", invoiceId)
                        .file(file)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
    }

    @Test
    @Transactional
    void submitInvoice_withActiveDocument_transitionsToPendingVerification() throws Exception {
        String token = registerSme("sme-submit1@example.com", "SME Submit 1 Org");
        String invoiceId = createInvoice(token);
        attachDocument(token, invoiceId);

        mockMvc.perform(post("/api/v1/invoices/{id}/submit", invoiceId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.data.submittedAt").isNotEmpty());
    }

    @Test
    @Transactional
    void submitInvoice_withoutDocument_returns400BadRequest() throws Exception {
        String token = registerSme("sme-submit2@example.com", "SME Submit 2 Org");
        String invoiceId = createInvoice(token); // No document attached

        mockMvc.perform(post("/api/v1/invoices/{id}/submit", invoiceId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_REQUIRED"));
    }

    @Test
    @Transactional
    void submitInvoice_alreadySubmitted_returns409Conflict() throws Exception {
        String token = registerSme("sme-submit3@example.com", "SME Submit 3 Org");
        String invoiceId = createInvoice(token);
        attachDocument(token, invoiceId);

        // Submit once
        mockMvc.perform(post("/api/v1/invoices/{id}/submit", invoiceId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        // Attempt second submission with different key
        mockMvc.perform(post("/api/v1/invoices/{id}/submit", invoiceId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_INVOICE_STATE"));
    }

    @Test
    @Transactional
    void uploadDocument_afterSubmission_returns409Conflict() throws Exception {
        String token = registerSme("sme-submit4@example.com", "SME Submit 4 Org");
        String invoiceId = createInvoice(token);
        attachDocument(token, invoiceId);

        // Submit invoice
        mockMvc.perform(post("/api/v1/invoices/{id}/submit", invoiceId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        // Attempt to upload replacement document after submission
        byte[] pdf2 = ("%PDF-1.4 post-submit replacement " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file2 = new MockMultipartFile("file", "replace.pdf", "application/pdf", pdf2);

        mockMvc.perform(multipart("/api/v1/invoices/{id}/documents", invoiceId)
                        .file(file2)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_INVOICE_STATE"));
    }
}
