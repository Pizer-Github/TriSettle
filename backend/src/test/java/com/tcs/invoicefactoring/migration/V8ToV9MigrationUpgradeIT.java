package com.tcs.invoicefactoring.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class V8ToV9MigrationUpgradeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("upgrade_test_db")
            .withUsername("test_owner")
            .withPassword("test_password");

    private static final UUID SME_ORG_ID = UUID.randomUUID();
    private static final UUID BUYER_ORG_ID = UUID.randomUUID();
    private static final UUID FINANCIER_ORG_ID = UUID.randomUUID();
    private static final UUID SME_USER_ID = UUID.randomUUID();
    private static final UUID BUYER_USER_ID = UUID.randomUUID();
    private static final UUID INVOICE_ID = UUID.randomUUID();
    private static final UUID DOC_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID OUTBOX_ID = UUID.randomUUID();

    private static final UUID INVOICE2_ID = UUID.randomUUID();
    private static final UUID DOC2_ID = UUID.randomUUID();
    private static final UUID REQUEST2_ID = UUID.randomUUID();
    private static final UUID RESPONSE2_ID = UUID.randomUUID();

    private static String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Connection getOwnerConnection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @BeforeAll
    static void applyV1ToV8AndSeedData() throws Exception {
        // 1. Run migrations up to target V8
        Flyway flywayV8 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("8")
                .load();
        flywayV8.migrate();

        // 2. Insert representative data satisfying all V8 schema constraints
        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(false);

            // Organizations
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO organizations (id, name, org_type) VALUES (?, ?, ?)")) {
                ps.setObject(1, SME_ORG_ID); ps.setString(2, "SME Corp Ltd"); ps.setString(3, "SME"); ps.addBatch();
                ps.setObject(1, BUYER_ORG_ID); ps.setString(2, "Buyer Enterprise Ltd"); ps.setString(3, "BUYER"); ps.addBatch();
                ps.setObject(1, FINANCIER_ORG_ID); ps.setString(2, "Capital Fin Ltd"); ps.setString(3, "FINANCIER"); ps.addBatch();
                ps.executeBatch();
            }

            // Users
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)")) {
                ps.setObject(1, SME_USER_ID); ps.setString(2, "sme@corp.local"); ps.setString(3, "$2a$10$dummyhash"); ps.setString(4, "SME Admin"); ps.addBatch();
                ps.setObject(1, BUYER_USER_ID); ps.setString(2, "buyer@corp.local"); ps.setString(3, "$2a$10$dummyhash"); ps.setString(4, "Buyer Admin"); ps.addBatch();
                ps.executeBatch();
            }

            // User Organizations
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO user_organizations (user_id, org_id, role) VALUES (?, ?, ?)")) {
                ps.setObject(1, SME_USER_ID); ps.setObject(2, SME_ORG_ID); ps.setString(3, "SME"); ps.addBatch();
                ps.setObject(1, BUYER_USER_ID); ps.setObject(2, BUYER_ORG_ID); ps.setString(3, "BUYER"); ps.addBatch();
                ps.executeBatch();
            }

            // Organization Tax Profiles
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO organization_tax_profiles (org_id, gstin, is_verified, verified_by_user_id, status) VALUES (?, ?, ?, ?, ?)")) {
                ps.setObject(1, BUYER_ORG_ID); ps.setString(2, "27AAACT2727Q1ZW"); ps.setBoolean(3, true); ps.setObject(4, BUYER_USER_ID); ps.setString(5, "ACTIVE");
                ps.executeUpdate();
            }

            // Invoices in DRAFT state first so documents can be attached
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO invoices (id, sme_org_id, buyer_gstin, buyer_name, buyer_pan, invoice_number, invoice_date, due_date, currency, total_amount, taxable_amount, tax_amount, status, created_by) " +
                            "VALUES (?, ?, ?, ?, ?, ?, '2026-01-01', '2026-02-01', 'INR', 118000.00, 100000.00, 18000.00, 'DRAFT', ?)")) {
                ps.setObject(1, INVOICE_ID); ps.setObject(2, SME_ORG_ID); ps.setString(3, "27AAACT2727Q1ZW"); ps.setString(4, "Buyer Enterprise Ltd"); ps.setString(5, "AAACT2727Q"); ps.setString(6, "INV-V8-001"); ps.setObject(7, SME_USER_ID); ps.addBatch();
                ps.setObject(1, INVOICE2_ID); ps.setObject(2, SME_ORG_ID); ps.setString(3, "27AAACT2727Q1ZW"); ps.setString(4, "Buyer Enterprise Ltd"); ps.setString(5, "AAACT2727Q"); ps.setString(6, "INV-V8-002"); ps.setObject(7, SME_USER_ID); ps.addBatch();
                ps.executeBatch();
            }

            // Invoice Documents (attached while invoice is in DRAFT)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO invoice_documents (id, invoice_id, sme_org_id, file_name, storage_path, file_size_bytes, mime_type, sha256_checksum, is_active, uploaded_by) " +
                            "VALUES (?, ?, ?, 'invoice.pdf', '/storage/invoice.pdf', 2048, 'application/pdf', ?, true, ?)")) {
                ps.setObject(1, DOC_ID); ps.setObject(2, INVOICE_ID); ps.setObject(3, SME_ORG_ID); ps.setString(4, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"); ps.setObject(5, SME_USER_ID); ps.addBatch();
                ps.setObject(1, DOC2_ID); ps.setObject(2, INVOICE2_ID); ps.setObject(3, SME_ORG_ID); ps.setString(4, "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"); ps.setObject(5, SME_USER_ID); ps.addBatch();
                ps.executeBatch();
            }

            // Transition invoices to PENDING_VERIFICATION
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE invoices SET status = 'PENDING_VERIFICATION'");
            }

            // Buyer Confirmation Requests (valid 64-character SHA256 hashes)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO buyer_confirmation_requests (id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                            "VALUES (?, ?, ?, ?, '27AAACT2727Q1ZW', 'buyer@corp.local', ?, ?, NOW() + INTERVAL '7 days')")) {
                ps.setObject(1, REQUEST_ID); ps.setObject(2, INVOICE_ID); ps.setObject(3, SME_ORG_ID); ps.setObject(4, BUYER_ORG_ID);
                ps.setString(5, sha256("rawtoken1")); ps.setString(6, "PENDING"); ps.addBatch();

                ps.setObject(1, REQUEST2_ID); ps.setObject(2, INVOICE2_ID); ps.setObject(3, SME_ORG_ID); ps.setObject(4, BUYER_ORG_ID);
                ps.setString(5, sha256("rawtoken2")); ps.setString(6, "CONFIRMED"); ps.addBatch();
                ps.executeBatch();
            }

            // Buyer Confirmation Sessions (valid 64-character SHA256 hash)
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO buyer_confirmation_sessions (id, session_token_hash, request_id, invoice_id, sme_org_id, auth_channel, status, expires_at, client_ip, user_agent_hash) " +
                            "VALUES (?, ?, ?, ?, ?, 'TOKEN_LINK', 'ACTIVE', NOW() + INTERVAL '15 minutes', '192.168.1.1', 'uahash111')")) {
                ps.setObject(1, SESSION_ID); ps.setString(2, sha256("sessiontoken1"));
                ps.setObject(3, REQUEST_ID); ps.setObject(4, INVOICE_ID); ps.setObject(5, SME_ORG_ID);
                ps.executeUpdate();
            }

            // Buyer Confirmation Responses (Satisfying V8 constraints: digest matches canonical text)
            String canonicalEvidence = "{\"action\":\"CONFIRMED\",\"invoice_number\":\"INV-V8-002\"}";
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(String.format(
                        "INSERT INTO buyer_confirmation_responses (id, request_id, invoice_id, sme_org_id, action, auth_channel, identity_assurance, client_ip, user_agent_hash, canonical_evidence_text, evidence_payload, evidence_sha256) " +
                                "VALUES ('%s', '%s', '%s', '%s', 'CONFIRMED', 'TOKEN_LINK', 'BEARER_TOKEN_POSSESSION', '192.168.1.1', 'uahash', '%s', '%s'::jsonb, encode(digest('%s', 'sha256'), 'hex'))",
                        RESPONSE2_ID, REQUEST2_ID, INVOICE2_ID, SME_ORG_ID, canonicalEvidence, canonicalEvidence, canonicalEvidence
                ));
            }

            // Outbox Events
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO outbox_events (id, event_type, aggregate_type, aggregate_id, tenant_id, payload_encrypted, key_version, status) " +
                            "VALUES (?, 'BUYER_CONFIRMATION_REQUESTED', 'INVOICE', ?, ?, 'cipher_bytes', 1, 'PENDING')")) {
                ps.setObject(1, OUTBOX_ID); ps.setObject(2, INVOICE_ID); ps.setObject(3, SME_ORG_ID);
                ps.executeUpdate();
            }

            // Throttles
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO confirmation_attempt_throttles (client_ip, failed_attempts, last_attempt_at) VALUES ('192.168.1.50', 2, NOW())")) {
                ps.executeUpdate();
            }

            conn.commit();
        }
    }

    @Test
    @Order(1)
    void step1_applyV9Migration_succeedsOnPopulatedV8Database() {
        Flyway flywayV9 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("9")
                .load();
        int applied = flywayV9.migrate().migrationsExecuted;
        assertThat(applied).isEqualTo(1);
    }

    @Test
    @Order(2)
    void step2_verifyV8DataPreserved_afterV9Upgrade() throws Exception {
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            // Verify row counts (4 orgs: 1 seeded by V5 platform admin + 3 seeded by test)
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM organizations");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(4);

            rs = st.executeQuery("SELECT COUNT(*) FROM users");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(2);

            rs = st.executeQuery("SELECT COUNT(*) FROM user_organizations");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(2);

            rs = st.executeQuery("SELECT COUNT(*) FROM organization_tax_profiles");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(1);

            rs = st.executeQuery("SELECT COUNT(*) FROM invoices");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(2);

            rs = st.executeQuery("SELECT COUNT(*) FROM invoice_documents");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(2);

            rs = st.executeQuery("SELECT COUNT(*) FROM buyer_confirmation_requests");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(2);

            rs = st.executeQuery("SELECT COUNT(*) FROM buyer_confirmation_sessions");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(1);

            rs = st.executeQuery("SELECT COUNT(*) FROM buyer_confirmation_responses");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(1);

            rs = st.executeQuery("SELECT COUNT(*) FROM outbox_events");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(1);

            rs = st.executeQuery("SELECT COUNT(*) FROM confirmation_attempt_throttles");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @Order(3)
    void step3_verifyV9Additions_constraintAndTriggerAndDefinerOwner() throws Exception {
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            // Check constraint chk_resp_canonical_jsonb_match
            ResultSet rs = st.executeQuery(
                    "SELECT conname FROM pg_constraint WHERE conname = 'chk_resp_canonical_jsonb_match'");
            assertThat(rs.next()).isTrue();

            // Trigger trg_invoice_verification_authoritative_check
            rs = st.executeQuery(
                    "SELECT tgname FROM pg_trigger WHERE tgname = 'trg_invoice_verification_authoritative_check'");
            assertThat(rs.next()).isTrue();

            // All 8 functions owned by buyer_confirmation_definer
            rs = st.executeQuery(
                    "SELECT proname, r.rolname as owner, p.prosecdef, array_to_string(p.proconfig, ',') as config " +
                            "FROM pg_proc p " +
                            "JOIN pg_roles r ON p.proowner = r.oid " +
                            "WHERE proname IN (" +
                            "'fn_issue_buyer_confirmation_request', " +
                            "'fn_exchange_buyer_confirmation_token', " +
                            "'fn_acquire_session_lease', " +
                            "'fn_submit_buyer_confirmation_response', " +
                            "'fn_cancel_buyer_confirmation_request', " +
                            "'fn_resend_buyer_confirmation_request', " +
                            "'fn_mark_request_expired', " +
                            "'fn_claim_outbox_events')");

            int functionCount = 0;
            while (rs.next()) {
                functionCount++;
                assertThat(rs.getString("owner")).isEqualTo("buyer_confirmation_definer");
                assertThat(rs.getBoolean("prosecdef")).isTrue();
                assertThat(rs.getString("config")).contains("search_path=pg_catalog, public, pg_temp");
            }
            assertThat(functionCount).isEqualTo(8);
        }
    }

    @Test
    @Order(4)
    void step4_applyV10Migration_succeedsOnPopulatedV9Database() {
        Flyway flywayV10 = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("10")
                .load();
        int applied = flywayV10.migrate().migrationsExecuted;
        assertThat(applied).isEqualTo(1);
    }

    @Test
    @Order(5)
    void step5_verifyDataPreservedAndV10Signatures_afterV10Upgrade() throws Exception {
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            // Verify data row counts remained intact across V10
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM buyer_confirmation_requests");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(2);

            rs = st.executeQuery("SELECT COUNT(*) FROM buyer_confirmation_sessions");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(1);

            rs = st.executeQuery("SELECT COUNT(*) FROM buyer_confirmation_responses");
            rs.next(); assertThat(rs.getInt(1)).isEqualTo(1);

            // Verify flyway schema history shows V8, V9, V10 all successful
            rs = st.executeQuery("SELECT version, success, type FROM flyway_schema_history WHERE version IN ('8', '9', '10') ORDER BY installed_rank ASC");
            int versionCount = 0;
            while (rs.next()) {
                versionCount++;
                assertThat(rs.getBoolean("success")).isTrue();
                assertThat(rs.getString("type")).isEqualTo("SQL");
            }
            assertThat(versionCount).isEqualTo(3);
        }
    }
}
