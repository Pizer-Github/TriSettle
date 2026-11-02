package com.tcs.invoicefactoring.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.identity.dto.LoginRequest;
import com.tcs.invoicefactoring.identity.dto.RegisterRequest;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class InvoiceTenantIsolationIT {

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
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

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

    private String getAdminToken() throws Exception {
        LoginRequest req = new LoginRequest("admin@platform.local", "Admin@TestBootstrap2026!");
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
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

    private String attachDocument(String token, String invoiceId) throws Exception {
        byte[] pdfBytes = ("%PDF-1.4 invoice pdf " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", pdfBytes);

        MvcResult res = mockMvc.perform(multipart("/api/v1/invoices/{id}/documents", invoiceId)
                        .file(file)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return root.path("data").path("id").asText();
    }

    @Test
    void sme1_cannotRead_sme2_invoice_returns404() throws Exception {
        String token1 = registerSme("sme-iso1@example.com", "SME Org 1");
        String token2 = registerSme("sme-iso2@example.com", "SME Org 2");

        String invoiceId2 = createInvoice(token2);

        // SME 1 attempts to read SME 2's invoice
        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceId2)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isNotFound());
    }

    @Test
    void sme1_cannotDownload_sme2_document_returns404() throws Exception {
        String token1 = registerSme("sme-iso3@example.com", "SME Org 3");
        String token2 = registerSme("sme-iso4@example.com", "SME Org 4");

        String invoiceId2 = createInvoice(token2);
        String docId2 = attachDocument(token2, invoiceId2);

        // SME 1 attempts to download SME 2's document
        mockMvc.perform(get("/api/v1/invoices/{id}/documents/{docId}/download", invoiceId2, docId2)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isNotFound());
    }

    @Test
    void sme1_cannotSubmit_sme2_invoice_returns404() throws Exception {
        String token1 = registerSme("sme-iso5@example.com", "SME Org 5");
        String token2 = registerSme("sme-iso6@example.com", "SME Org 6");

        String invoiceId2 = createInvoice(token2);
        attachDocument(token2, invoiceId2);

        // SME 1 attempts to submit SME 2's invoice
        mockMvc.perform(post("/api/v1/invoices/{id}/submit", invoiceId2)
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void admin_canView_sme1_invoice_returns200() throws Exception {
        String tokenSme = registerSme("sme-adminview@example.com", "SME Admin View Org");
        String adminToken = getAdminToken();

        String invoiceId = createInvoice(tokenSme);

        // Platform Admin queries SME's invoice
        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoice.id").value(invoiceId));
    }

    @Test
    void missingContext_failsClosedUnderRls() {
        transactionTemplate.execute(status -> {
            entityManager.createNativeQuery("SET ROLE app_worker").executeUpdate();
            List<?> invoices = entityManager.createNativeQuery("SELECT id FROM invoices").getResultList();
            assertThat(invoices).isEmpty();
            entityManager.createNativeQuery("RESET ROLE").executeUpdate();
            return null;
        });
    }
}
