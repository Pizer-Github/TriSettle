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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class InvoiceDocumentUploadIT {

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

    @Test
    void uploadDocument_validPdf_attachesDocumentWithVersion1() throws Exception {
        String token = registerSme("sme-doc1@example.com", "SME Doc 1 Org");
        String invoiceId = createInvoice(token);

        byte[] pdfBytes = "%PDF-1.4 sample invoice content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", pdfBytes);

        mockMvc.perform(multipart("/api/v1/invoices/{id}/documents", invoiceId)
                        .file(file)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.sha256Checksum").isNotEmpty());
    }

    @Test
    void uploadDocument_replacementPdf_incrementsVersionAndPreservesPrevious() throws Exception {
        String token = registerSme("sme-doc2@example.com", "SME Doc 2 Org");
        String invoiceId = createInvoice(token);

        byte[] pdf1 = "%PDF-1.4 version 1 content unique".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file1 = new MockMultipartFile("file", "inv_v1.pdf", "application/pdf", pdf1);

        mockMvc.perform(multipart("/api/v1/invoices/{id}/documents", invoiceId)
                        .file(file1)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1));

        byte[] pdf2 = "%PDF-1.4 version 2 replacement content unique".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file2 = new MockMultipartFile("file", "inv_v2.pdf", "application/pdf", pdf2);

        mockMvc.perform(multipart("/api/v1/invoices/{id}/documents", invoiceId)
                        .file(file2)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.active").value(true));

        // Query invoice detail to verify history
        mockMvc.perform(get("/api/v1/invoices/{id}", invoiceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeDocument.version").value(2))
                .andExpect(jsonPath("$.data.documentHistory", hasSize(2)));
    }

    @Test
    void uploadDocument_globalHashDuplicateAcrossDifferentSmes_returns409WithoutLeakingOtherSmeData() throws Exception {
        String token1 = registerSme("sme-global1@example.com", "SME 1 Org");
        String invoiceId1 = createInvoice(token1);

        byte[] sharedPdf = ("%PDF-1.4 shared content hash " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file1 = new MockMultipartFile("file", "contract.pdf", "application/pdf", sharedPdf);

        mockMvc.perform(multipart("/api/v1/invoices/{id}/documents", invoiceId1)
                        .file(file1)
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());

        String token2 = registerSme("sme-global2@example.com", "SME 2 Org");
        String invoiceId2 = createInvoice(token2);

        MockMultipartFile file2 = new MockMultipartFile("file", "duplicate.pdf", "application/pdf", sharedPdf);

        mockMvc.perform(multipart("/api/v1/invoices/{id}/documents", invoiceId2)
                        .file(file2)
                        .header("Authorization", "Bearer " + token2)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_DOCUMENT"))
                // Assert no leak of SME 1 information
                .andExpect(jsonPath("$.error.message").value("An invoice document with identical cryptographic content already exists on the network"))
                .andExpect(jsonPath("$.error.message", not(containsString("SME 1 Org"))))
                .andExpect(jsonPath("$.error.message", not(containsString(invoiceId1))));
    }

    @Test
    void uploadDocument_invalidMagicBytes_returns400BadRequest() throws Exception {
        String token = registerSme("sme-magic@example.com", "SME Magic Org");
        String invoiceId = createInvoice(token);

        byte[] nonPdf = "MZ invalid windows executable disguised as pdf".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "malicious.pdf", "application/pdf", nonPdf);

        mockMvc.perform(multipart("/api/v1/invoices/{id}/documents", invoiceId)
                        .file(file)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
