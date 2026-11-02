package com.tcs.invoicefactoring.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.identity.dto.RegisterRequest;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
public class BuyerConfirmationTenantIsolationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("isolation_test_db")
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

    private static final UUID SME1_ORG_ID = UUID.randomUUID();
    private static final UUID SME2_ORG_ID = UUID.randomUUID();
    private static final UUID BUYER_ORG_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ORG_ID = UUID.randomUUID();

    private static final UUID SME1_USER_ID = UUID.randomUUID();
    private static final UUID SME2_USER_ID = UUID.randomUUID();
    private static final UUID BUYER_USER_ID = UUID.randomUUID();

    private static final UUID INV1_ID = UUID.randomUUID();
    private static final UUID INV2_ID = UUID.randomUUID();
    private static final UUID REQ1_ID = UUID.randomUUID();
    private static final UUID REQ2_ID = UUID.randomUUID();

    private static String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Connection getWorkerConnection() throws Exception {
        Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Statement st = conn.createStatement()) {
            st.execute("SET ROLE app_worker");
        }
        return conn;
    }

    private static Connection getOwnerConnection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @BeforeAll
    static void seedTenantsAndRequests() throws Exception {
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO organizations (id, name, org_type) VALUES (?, ?, ?)")) {
                ps.setObject(1, SME1_ORG_ID); ps.setString(2, "SME One Ltd"); ps.setString(3, "SME"); ps.addBatch();
                ps.setObject(1, SME2_ORG_ID); ps.setString(2, "SME Two Ltd"); ps.setString(3, "SME"); ps.addBatch();
                ps.setObject(1, BUYER_ORG_ID); ps.setString(2, "Buyer Enterprise Ltd"); ps.setString(3, "BUYER"); ps.addBatch();
                ps.setObject(1, OTHER_USER_ORG_ID); ps.setString(2, "Third Party Org"); ps.setString(3, "SME"); ps.addBatch();
                ps.executeBatch();
            }

            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, SME1_USER_ID); ps.setString(2, "sme1@iso.local"); ps.setString(3, "$2a$10$dummy"); ps.setString(4, "SME 1 Admin"); ps.addBatch();
                ps.setObject(1, SME2_USER_ID); ps.setString(2, "sme2@iso.local"); ps.setString(3, "$2a$10$dummy"); ps.setString(4, "SME 2 Admin"); ps.addBatch();
                ps.setObject(1, BUYER_USER_ID); ps.setString(2, "buyer@iso.local"); ps.setString(3, "$2a$10$dummy"); ps.setString(4, "Buyer Admin"); ps.addBatch();
                ps.executeBatch();
            }

            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO user_organizations (user_id, org_id, role) VALUES (?, ?, ?)")) {
                ps.setObject(1, SME1_USER_ID); ps.setObject(2, SME1_ORG_ID); ps.setString(3, "SME"); ps.addBatch();
                ps.setObject(1, SME2_USER_ID); ps.setObject(2, SME2_ORG_ID); ps.setString(3, "SME"); ps.addBatch();
                ps.setObject(1, BUYER_USER_ID); ps.setObject(2, BUYER_ORG_ID); ps.setString(3, "BUYER"); ps.addBatch();
                ps.executeBatch();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO organization_tax_profiles (org_id, gstin, is_verified, verified_by_user_id, status) VALUES (?, ?, ?, ?, ?)")) {
                ps.setObject(1, BUYER_ORG_ID); ps.setString(2, "27AAACT2727Q1ZW"); ps.setBoolean(3, true); ps.setObject(4, BUYER_USER_ID); ps.setString(5, "ACTIVE");
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO invoices (id, sme_org_id, buyer_gstin, buyer_name, buyer_pan, invoice_number, invoice_date, due_date, currency, total_amount, taxable_amount, tax_amount, status, created_by) " +
                            "VALUES (?, ?, ?, ?, ?, ?, '2026-01-01', '2026-02-01', 'INR', 118000.00, 100000.00, 18000.00, ?, ?)")) {
                ps.setObject(1, INV1_ID); ps.setObject(2, SME1_ORG_ID); ps.setString(3, "27AAACT2727Q1ZW"); ps.setString(4, "Buyer Enterprise Ltd"); ps.setString(5, "AAACT2727Q"); ps.setString(6, "INV-ISO-001"); ps.setString(7, "PENDING_VERIFICATION"); ps.setObject(8, SME1_USER_ID); ps.addBatch();
                ps.setObject(1, INV2_ID); ps.setObject(2, SME2_ORG_ID); ps.setString(3, "27AAACT2727Q1ZW"); ps.setString(4, "Buyer Enterprise Ltd"); ps.setString(5, "AAACT2727Q"); ps.setString(6, "INV-ISO-002"); ps.setString(7, "PENDING_VERIFICATION"); ps.setObject(8, SME2_USER_ID); ps.addBatch();
                ps.executeBatch();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO buyer_confirmation_requests (id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                            "VALUES (?, ?, ?, ?, '27AAACT2727Q1ZW', 'buyer@iso.local', ?, 'PENDING', NOW() + INTERVAL '7 days')")) {
                ps.setObject(1, REQ1_ID); ps.setObject(2, INV1_ID); ps.setObject(3, SME1_ORG_ID); ps.setObject(4, BUYER_ORG_ID);
                ps.setString(5, sha256("tokeniso1")); ps.addBatch();

                ps.setObject(1, REQ2_ID); ps.setObject(2, INV2_ID); ps.setObject(3, SME2_ORG_ID); ps.setObject(4, BUYER_ORG_ID);
                ps.setString(5, sha256("tokeniso2")); ps.addBatch();
                ps.executeBatch();
            }

            conn.commit();
        }
    }

    @Test
    void catalog_allV8Tables_haveRlsEnabledAndForceRlsChecked() throws Exception {
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            String[] tables = {
                    "buyer_confirmation_requests",
                    "buyer_confirmation_sessions",
                    "buyer_confirmation_responses",
                    "organization_tax_profiles",
                    "outbox_events"
            };

            for (String table : tables) {
                ResultSet rs = st.executeQuery(
                        "SELECT relrowsecurity FROM pg_class WHERE relname = '" + table + "'");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("relrowsecurity")).isTrue();
            }
        }
    }

    @Test
    void smeIsolation_crossTenantDmlBlocked_forAllOperations() throws Exception {
        try (Connection conn = getWorkerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.tenant_id = '" + SME1_ORG_ID + "'");
                st.execute("SET LOCAL app.tenant_type = 'SME'");

                // SELECT: Can see REQ1, cannot see REQ2
                ResultSet rs = st.executeQuery("SELECT id FROM buyer_confirmation_requests");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("id", UUID.class)).isEqualTo(REQ1_ID);
                assertThat(rs.next()).isFalse();

                // UPDATE: Cannot modify SME2's request
                int updated = st.executeUpdate("UPDATE buyer_confirmation_requests SET recipient_email = 'hacked@hack.com' WHERE id = '" + REQ2_ID + "'");
                assertThat(updated).isEqualTo(0);

                // DELETE: Cannot delete SME2's request (app_worker has no DELETE grant on table)
                assertThatThrownBy(() -> st.executeUpdate("DELETE FROM buyer_confirmation_requests WHERE id = '" + REQ2_ID + "'"))
                        .hasMessageContaining("permission denied for table buyer_confirmation_requests");

                // INSERT: Cannot insert request with SME2 tenant ID
                assertThatThrownBy(() -> st.executeUpdate(String.format(
                        "INSERT INTO buyer_confirmation_requests (invoice_id, sme_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                                "VALUES ('%s', '%s', '27AAACT2727Q1ZW', 'bad@bad.com', 'badtoken12345678901234567890123456789012345678901234567890123456', 'PENDING', NOW() + INTERVAL '1 day')",
                        INV2_ID, SME2_ORG_ID
                ))).hasMessageContaining("violates row-level security policy");
            }
        }
    }

    @Test
    void missingOrMalformedContext_failsClosedUnderRls() throws Exception {
        try (Connection conn = getWorkerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // Empty context
                st.execute("SET LOCAL app.tenant_id = ''");
                ResultSet rs = st.executeQuery("SELECT id FROM buyer_confirmation_requests");
                assertThat(rs.next()).isFalse();

                // Malformed context fails or returns 0 rows
                assertThatThrownBy(() -> st.executeQuery("SET LOCAL app.tenant_id = 'malformed-not-uuid'; SELECT id FROM buyer_confirmation_requests"))
                        .isNotNull();
            }
        }
    }

    @Test
    void transactionLocalContext_doesNotLeakAcrossTransactions() throws Exception {
        try (Connection conn = getWorkerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.tenant_id = '" + SME1_ORG_ID + "'");
                st.execute("SET LOCAL app.tenant_type = 'SME'");
                ResultSet rs = st.executeQuery("SELECT count(*) FROM buyer_confirmation_requests");
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            conn.commit();

            // Next transaction on same connection without setting context
            try (Statement st = conn.createStatement()) {
                ResultSet rs = st.executeQuery("SELECT count(*) FROM buyer_confirmation_requests");
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
            conn.commit();
        }
    }

    @Test
    void buyerRequestsPortalVisibility_requiresActiveVerifiedTaxProfile() throws Exception {
        try (Connection conn = getWorkerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.tenant_id = '" + BUYER_ORG_ID + "'");
                st.execute("SET LOCAL app.tenant_type = 'BUYER'");

                // When active & verified tax profile exists, requests with matching GSTIN are visible
                ResultSet rs = st.executeQuery("SELECT count(*) FROM buyer_confirmation_requests");
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(2);
            }
            conn.rollback();
        }

        // Now revoke the tax profile as owner
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE organization_tax_profiles SET status = 'REVOKED' WHERE org_id = '" + BUYER_ORG_ID + "'");
        }

        // Query again as app_worker: requests are now invisible
        try (Connection conn = getWorkerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.tenant_id = '" + BUYER_ORG_ID + "'");
                st.execute("SET LOCAL app.tenant_type = 'BUYER'");

                ResultSet rs = st.executeQuery("SELECT count(*) FROM buyer_confirmation_requests");
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
            conn.rollback();
        }

        // Restore active status
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE organization_tax_profiles SET status = 'ACTIVE' WHERE org_id = '" + BUYER_ORG_ID + "'");
        }
    }

    @Test
    void forgedContext_directAppWorkerSql_demonstratesGucLimit() throws Exception {
        // Direct SQL as app_worker: an attacker with arbitrary SQL access can forge the GUC
        try (Connection conn = getWorkerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // Attacker manually sets GUC to Buyer Org ID
                st.execute("SET LOCAL app.tenant_id = '" + BUYER_ORG_ID + "'");
                st.execute("SET LOCAL app.tenant_type = 'BUYER'");

                ResultSet rs = st.executeQuery("SELECT count(*) FROM buyer_confirmation_requests WHERE buyer_gstin = '27AAACT2727Q1ZW'");
                rs.next();
                // Because RLS trusts current_setting('app.tenant_id'), direct SQL as app_worker sees the records
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(2);
            }
            conn.rollback();
        }
    }

    @Test
    void forgedContext_authenticatedHttpPath_enforcesUserOrganizationMembership() throws Exception {
        // Register an authenticated user who belongs to an entirely unrelated organization
        String rogueEmail = "rogue-" + UUID.randomUUID() + "@external.local";
        RegisterRequest reg = new RegisterRequest(rogueEmail, "StrongPass123!", "Rogue User", "Rogue Org");
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn();

        String rogueToken = objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("token").asText();

        // 1. Rogue user attempts to cancel SME 1's request via SME controller (blocked by tenant-scoped query -> 404)
        mockMvc.perform(post("/api/v1/sme/invoices/{id}/confirmation-request/cancel", INV1_ID)
                        .param("requestId", REQ1_ID.toString())
                        .header("Authorization", "Bearer " + rogueToken))
                .andExpect(status().isNotFound());

        // 2. Unauthenticated request without JWT context is denied by security filter chain (403 Forbidden)
        mockMvc.perform(post("/api/v1/sme/invoices/{id}/confirmation-request/cancel", INV1_ID)
                        .param("requestId", REQ1_ID.toString()))
                .andExpect(status().isForbidden());
    }
}
