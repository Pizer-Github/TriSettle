package com.tcs.invoicefactoring.invoice.confirmation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.invoice.confirmation.dto.SubmitConfirmationRequest;
import com.tcs.invoicefactoring.invoice.confirmation.dto.TokenExchangeRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
public class BuyerConfirmationExpiryPersistenceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("expiry_test_db")
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

    private static final UUID SME_ORG_ID = UUID.randomUUID();
    private static final UUID BUYER_ORG_ID = UUID.randomUUID();
    private static final UUID SME_USER_ID = UUID.randomUUID();
    private static final UUID BUYER_USER_ID = UUID.randomUUID();

    private static final UUID INVOICE_SQL_EXP_ID = UUID.randomUUID();
    private static final UUID REQ_SQL_EXP_ID = UUID.randomUUID();
    private static final String RAW_TOKEN_SQL = "sqlrawtoken123456789012345678901234";

    private static final UUID INVOICE_HTTP_EXP_ID = UUID.randomUUID();
    private static final UUID REQ_HTTP_EXP_ID = UUID.randomUUID();
    private static final String RAW_TOKEN_HTTP = "httprawtoken12345678901234567890123";

    private static final UUID INVOICE_SESS_EXP_ID = UUID.randomUUID();
    private static final UUID REQ_SESS_EXP_ID = UUID.randomUUID();
    private static final UUID SESS_EXP_ID = UUID.randomUUID();
    private static final String RAW_SESS_TOKEN_HTTP = "httpsessionrawtoken1234567890123456";

    private static Connection getOwnerConnection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection getIndependentConnection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeAll
    static void setupExpiredData() throws Exception {
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(String.format("INSERT INTO organizations (id, name, org_type) VALUES ('%s', 'SME Exp Org', 'SME'), ('%s', 'Buyer Exp Org', 'BUYER')", SME_ORG_ID, BUYER_ORG_ID));
                st.executeUpdate(String.format("INSERT INTO users (id, email, password_hash, full_name) VALUES ('%s', 'sme@exp.local', '$2a$10$dummy', 'SME'), ('%s', 'buyer@exp.local', '$2a$10$dummy', 'Buyer')", SME_USER_ID, BUYER_USER_ID));
                st.executeUpdate(String.format("INSERT INTO user_organizations (user_id, org_id, role) VALUES ('%s', '%s', 'SME'), ('%s', '%s', 'BUYER')", SME_USER_ID, SME_ORG_ID, BUYER_USER_ID, BUYER_ORG_ID));
                st.executeUpdate(String.format("INSERT INTO organization_tax_profiles (org_id, gstin, is_verified, verified_by_user_id, status) VALUES ('%s', '27AAACT2727Q1ZW', true, '%s', 'ACTIVE')", BUYER_ORG_ID, BUYER_USER_ID));

                // 1. For direct SQL exchange test: expired request
                st.executeUpdate(String.format("INSERT INTO invoices (id, sme_org_id, buyer_gstin, buyer_name, buyer_pan, invoice_number, invoice_date, due_date, currency, total_amount, taxable_amount, tax_amount, status, created_by) " +
                        "VALUES ('%s', '%s', '27AAACT2727Q1ZW', 'Buyer Exp Org', 'AAACT2727Q', 'INV-EXP-001', '2026-01-01', '2026-02-01', 'INR', 118000.00, 100000.00, 18000.00, 'PENDING_VERIFICATION', '%s')", INVOICE_SQL_EXP_ID, SME_ORG_ID, SME_USER_ID));

                st.executeUpdate(String.format("INSERT INTO buyer_confirmation_requests (id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                        "VALUES ('%s', '%s', '%s', '%s', '27AAACT2727Q1ZW', 'buyer@exp.local', '%s', 'PENDING', NOW() - INTERVAL '1 hour')", REQ_SQL_EXP_ID, INVOICE_SQL_EXP_ID, SME_ORG_ID, BUYER_ORG_ID, sha256(RAW_TOKEN_SQL)));

                // 2. For HTTP exchange test: expired request
                st.executeUpdate(String.format("INSERT INTO invoices (id, sme_org_id, buyer_gstin, buyer_name, buyer_pan, invoice_number, invoice_date, due_date, currency, total_amount, taxable_amount, tax_amount, status, created_by) " +
                        "VALUES ('%s', '%s', '27AAACT2727Q1ZW', 'Buyer Exp Org', 'AAACT2727Q', 'INV-EXP-002', '2026-01-01', '2026-02-01', 'INR', 118000.00, 100000.00, 18000.00, 'PENDING_VERIFICATION', '%s')", INVOICE_HTTP_EXP_ID, SME_ORG_ID, SME_USER_ID));

                st.executeUpdate(String.format("INSERT INTO buyer_confirmation_requests (id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                        "VALUES ('%s', '%s', '%s', '%s', '27AAACT2727Q1ZW', 'buyer@exp.local', '%s', 'PENDING', NOW() - INTERVAL '2 hours')", REQ_HTTP_EXP_ID, INVOICE_HTTP_EXP_ID, SME_ORG_ID, BUYER_ORG_ID, sha256(RAW_TOKEN_HTTP)));

                // 3. For Session & Response HTTP test: active request, but session with past expires_at (inserted in DRAFT first for document attachment)
                st.executeUpdate(String.format("INSERT INTO invoices (id, sme_org_id, buyer_gstin, buyer_name, buyer_pan, invoice_number, invoice_date, due_date, currency, total_amount, taxable_amount, tax_amount, status, created_by) " +
                        "VALUES ('%s', '%s', '27AAACT2727Q1ZW', 'Buyer Exp Org', 'AAACT2727Q', 'INV-EXP-003', '2026-01-01', '2026-02-01', 'INR', 118000.00, 100000.00, 18000.00, 'DRAFT', '%s')", INVOICE_SESS_EXP_ID, SME_ORG_ID, SME_USER_ID));

                st.executeUpdate(String.format("INSERT INTO invoice_documents (id, invoice_id, sme_org_id, file_name, storage_path, file_size_bytes, mime_type, sha256_checksum, is_active, uploaded_by) " +
                        "VALUES ('%s', '%s', '%s', 'doc.pdf', '/doc.pdf', 1024, 'application/pdf', 'dummychecksum', true, '%s')", UUID.randomUUID(), INVOICE_SESS_EXP_ID, SME_ORG_ID, SME_USER_ID));

                st.executeUpdate(String.format("UPDATE invoices SET status = 'PENDING_VERIFICATION' WHERE id = '%s'", INVOICE_SESS_EXP_ID));

                st.executeUpdate(String.format("INSERT INTO buyer_confirmation_requests (id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                        "VALUES ('%s', '%s', '%s', '%s', '27AAACT2727Q1ZW', 'buyer@exp.local', '%s', 'PENDING', NOW() + INTERVAL '7 days')", REQ_SESS_EXP_ID, INVOICE_SESS_EXP_ID, SME_ORG_ID, BUYER_ORG_ID, sha256("tokenhashsess111")));

                st.executeUpdate(String.format("INSERT INTO buyer_confirmation_sessions (id, session_token_hash, request_id, invoice_id, sme_org_id, auth_channel, status, expires_at, client_ip, user_agent_hash) " +
                        "VALUES ('%s', '%s', '%s', '%s', '%s', 'TOKEN_LINK', 'ACTIVE', NOW() - INTERVAL '5 minutes', '192.168.1.1', 'uahash')",
                        SESS_EXP_ID, sha256(RAW_SESS_TOKEN_HTTP), REQ_SESS_EXP_ID, INVOICE_SESS_EXP_ID, SME_ORG_ID));
            }
            conn.commit();
        }
    }

    @Test
    void directSql_fnExchangeToken_expiredRequest_commitsExpiredState() throws Exception {
        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE app_worker");
                ResultSet rs = st.executeQuery(String.format(
                        "SELECT * FROM fn_exchange_buyer_confirmation_token('%s', 'newsesshash', '192.168.1.20', 'uahash', 'TOKEN_LINK', NULL, NULL)",
                        sha256(RAW_TOKEN_SQL)
                ));
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("outcome_status")).isEqualTo("EXPIRED");
                assertThat(rs.getObject("session_id")).isNull();
            }
        }

        // Verify state is permanently committed through independent connection
        try (Connection conn = getIndependentConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT status FROM buyer_confirmation_requests WHERE id = '" + REQ_SQL_EXP_ID + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("EXPIRED");
        }
    }

    @Test
    void directSql_fnAcquireLease_expiredSession_commitsExpiredState() throws Exception {
        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE app_worker");
                ResultSet rs = st.executeQuery(String.format(
                        "SELECT * FROM fn_acquire_session_lease('%s', 'idemp-exp-1', 'reqhash', 30)",
                        sha256(RAW_SESS_TOKEN_HTTP)
                ));
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("lease_outcome")).isEqualTo("EXPIRED");
            }
        }

        // Verify state is permanently committed through independent connection
        try (Connection conn = getIndependentConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT status FROM buyer_confirmation_sessions WHERE id = '" + SESS_EXP_ID + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("EXPIRED");
        }
    }

    @Test
    void httpPath_tokenExchange_afterExpiry_returns410AndPersistsExpiredStatus() throws Exception {
        TokenExchangeRequest req = new TokenExchangeRequest(RAW_TOKEN_HTTP);

        mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("CONFIRMATION_EXPIRED"));

        // Verify database persistence via independent connection
        try (Connection conn = getIndependentConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT status FROM buyer_confirmation_requests WHERE id = '" + REQ_HTTP_EXP_ID + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("EXPIRED");
        }
    }

    @Test
    void httpPath_acquireLeaseAndSubmit_afterExpiry_returns410AndPersistsExpiredStatus() throws Exception {
        SubmitConfirmationRequest req = new SubmitConfirmationRequest(
                "CONFIRMED", null, "All good", "buyer@exp.local"
        );

        mockMvc.perform(post("/api/v1/buyer/confirmations/respond")
                        .header("X-Confirmation-Session", RAW_SESS_TOKEN_HTTP)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("CONFIRMATION_EXPIRED"));

        // Verify database persistence via independent connection
        try (Connection conn = getIndependentConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT status FROM buyer_confirmation_sessions WHERE id = '" + SESS_EXP_ID + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("EXPIRED");
        }
    }

    @Test
    void expiryReaperTask_fnMarkRequestExpired_batchesAndExpiresRequests() throws Exception {
        UUID batchInvId = UUID.randomUUID();
        UUID batchReqId = UUID.randomUUID();

        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(String.format("INSERT INTO invoices (id, sme_org_id, buyer_gstin, buyer_name, buyer_pan, invoice_number, invoice_date, due_date, currency, total_amount, taxable_amount, tax_amount, status, created_by) " +
                        "VALUES ('%s', '%s', '27AAACT2727Q1ZW', 'Buyer Exp Org', 'AAACT2727Q', 'INV-REAPER-001', '2026-01-01', '2026-02-01', 'INR', 118000.00, 100000.00, 18000.00, 'PENDING_VERIFICATION', '%s')", batchInvId, SME_ORG_ID, SME_USER_ID));

                st.executeUpdate(String.format("INSERT INTO buyer_confirmation_requests (id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                        "VALUES ('%s', '%s', '%s', '%s', '27AAACT2727Q1ZW', 'buyer@exp.local', '%s', 'PENDING', NOW() - INTERVAL '3 hours')", batchReqId, batchInvId, SME_ORG_ID, BUYER_ORG_ID, sha256("tokenhashreaper111")));

                st.execute("SET ROLE app_worker");
                ResultSet rs = st.executeQuery("SELECT fn_mark_request_expired(50)");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(1);
            }
        }

        try (Connection conn = getIndependentConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT status FROM buyer_confirmation_requests WHERE id = '" + batchReqId + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("EXPIRED");
        }
    }
}
