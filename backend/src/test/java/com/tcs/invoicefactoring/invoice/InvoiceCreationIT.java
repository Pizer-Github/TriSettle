package com.tcs.invoicefactoring.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.identity.dto.RegisterRequest;
import com.tcs.invoicefactoring.invoice.dto.CreateInvoiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class InvoiceCreationIT {

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

    private String smeToken;

    @BeforeEach
    void setUp() throws Exception {
        if (smeToken == null) {
            String email = "sme-" + UUID.randomUUID() + "@example.com";
            RegisterRequest reg = new RegisterRequest(email, "StrongPass123!", "SME Director", "SME Corp Pvt Ltd");
            MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reg)))
                    .andExpect(status().isCreated())
                    .andReturn();
            JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
            smeToken = root.path("data").path("token").asText();
        }
    }

    @Test
    void createInvoice_validRequest_returns201WithDraftStatusAndExactAmounts() throws Exception {
        CreateInvoiceRequest req = new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZW",
                "AAACT2727Q",
                "INV-" + UUID.randomUUID(),
                LocalDate.now(),
                LocalDate.now().plusDays(45),
                "INR",
                "100000.00",
                "18000.00",
                "118000.00"
        );

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.taxableAmount").value("100000.00"))
                .andExpect(jsonPath("$.data.taxAmount").value("18000.00"))
                .andExpect(jsonPath("$.data.totalAmount").value("118000.00"));
    }

    @Test
    void createInvoice_duplicateInvoiceNumber_returns409Conflict() throws Exception {
        String invoiceNum = "DUP-INV-" + UUID.randomUUID();
        CreateInvoiceRequest req = new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZW",
                "AAACT2727Q",
                invoiceNum,
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                "INR",
                "50000.00",
                "9000.00",
                "59000.00"
        );

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // Attempting to create invoice with same buyer and invoice number with different idempotency key
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_INVOICE"));
    }

    @Test
    void createInvoice_idempotencyReplay_returnsCachedResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        CreateInvoiceRequest req = new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZW",
                "AAACT2727Q",
                "IDEM-INV-" + UUID.randomUUID(),
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                "INR",
                "50000.00",
                "9000.00",
                "59000.00"
        );

        MvcResult first = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(first.getResponse().getContentAsString()).isEqualTo(second.getResponse().getContentAsString());
    }

    @Test
    void createInvoice_idempotencyMismatchedPayload_returns409Conflict() throws Exception {
        String key = UUID.randomUUID().toString();
        CreateInvoiceRequest req1 = new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZW",
                "AAACT2727Q",
                "INV-" + UUID.randomUUID(),
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                "INR",
                "50000.00",
                "9000.00",
                "59000.00"
        );

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        CreateInvoiceRequest req2 = new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZW",
                "AAACT2727Q",
                "DIFFERENT-INV",
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                "INR",
                "50000.00",
                "9000.00",
                "59000.00"
        );

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void createInvoice_invalidGstinChecksum_returns400() throws Exception {
        CreateInvoiceRequest req = new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZX", // invalid checksum digit X (expected W)
                "AAACT2727Q",
                "INV-" + UUID.randomUUID(),
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                "INR",
                "50000.00",
                "9000.00",
                "59000.00"
        );

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void createInvoice_dueDateBeforeInvoiceDate_returns400() throws Exception {
        CreateInvoiceRequest req = new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZW",
                "AAACT2727Q",
                "INV-" + UUID.randomUUID(),
                LocalDate.now(),
                LocalDate.now().minusDays(5),
                "INR",
                "50000.00",
                "9000.00",
                "59000.00"
        );

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createInvoice_amountMismatch_returns400() throws Exception {
        CreateInvoiceRequest req = new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZW",
                "AAACT2727Q",
                "INV-" + UUID.randomUUID(),
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                "INR",
                "50000.00",
                "9000.00",
                "60000.00" // mismatch! 50k + 9k = 59k != 60k
        );

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", "Bearer " + smeToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
