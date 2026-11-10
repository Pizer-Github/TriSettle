package com.tcs.invoicefactoring.invoice.confirmation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public class InvoiceVerificationTriggerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("trigger_test_db")
            .withUsername("app_owner")
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

    private static final UUID SME_ORG_ID = UUID.randomUUID();
    private static final UUID BUYER_ORG_ID = UUID.randomUUID();
    private static final UUID SME_USER_ID = UUID.randomUUID();
    private static final UUID BUYER_USER_ID = UUID.randomUUID();

    private static final UUID INV_PENDING_ID = UUID.randomUUID();
    private static final UUID INV_VERIFIED_ID = UUID.randomUUID();
    private static final UUID DOC_PENDING_ID = UUID.randomUUID();
    private static final UUID DOC_VERIFIED_ID = UUID.randomUUID();
    private static final UUID REQ_ID = UUID.randomUUID();
    private static final UUID RESP_CONFIRMED_ID = UUID.randomUUID();
    private static final UUID RESP_DISPUTED_ID = UUID.randomUUID();

    private static final String VALID_CHECKSUM = "a1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef";
    private static final String PENDING_CHECKSUM = "b1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef";

    private static String sha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Connection getOwnerConnection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection getWorkerConnection() throws Exception {
        Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Statement st = conn.createStatement()) {
            st.execute("SET ROLE app_worker");
        }
        return conn;
    }

    @BeforeAll
    static void setupTestData() throws Exception {
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(false);

            // Seed orgs & users
            try (Statement st = conn.createStatement()) {
                st.execute("DO $$ BEGIN " +
                        "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pawnshop_owner') THEN " +
                        "  CREATE ROLE pawnshop_owner WITH NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT; " +
                        "  GRANT app_worker TO pawnshop_owner; " +
                        "  GRANT USAGE ON SCHEMA public TO pawnshop_owner; " +
                        "  GRANT SELECT, UPDATE ON public.invoices TO pawnshop_owner; " +
                        "END IF; " +
                        "END $$;");

                st.executeUpdate(String.format("INSERT INTO organizations (id, name, org_type) VALUES ('%s', 'SME Trigger Org', 'SME'), ('%s', 'Buyer Trigger Org', 'BUYER')", SME_ORG_ID, BUYER_ORG_ID));
                st.executeUpdate(String.format("INSERT INTO users (id, email, password_hash, full_name) VALUES ('%s', 'sme@trg.local', '$2a$10$dummy', 'SME'), ('%s', 'buyer@trg.local', '$2a$10$dummy', 'Buyer')", SME_USER_ID, BUYER_USER_ID));
                st.executeUpdate(String.format("INSERT INTO user_organizations (user_id, org_id, role) VALUES ('%s', '%s', 'SME'), ('%s', '%s', 'BUYER')", SME_USER_ID, SME_ORG_ID, BUYER_USER_ID, BUYER_ORG_ID));
                st.executeUpdate(String.format("INSERT INTO organization_tax_profiles (org_id, gstin, is_verified, verified_by_user_id, status) VALUES ('%s', '27AAACT2727Q1ZW', true, '%s', 'ACTIVE')", BUYER_ORG_ID, BUYER_USER_ID));

                // Invoices (inserted in DRAFT status first so documents can be attached)
                st.executeUpdate(String.format("INSERT INTO invoices (id, sme_org_id, buyer_gstin, buyer_name, buyer_pan, invoice_number, invoice_date, due_date, currency, total_amount, taxable_amount, tax_amount, status, created_by) " +
                        "VALUES ('%s', '%s', '27AAACT2727Q1ZW', 'Buyer Trigger Org', 'AAACT2727Q', 'INV-TRG-001', '2026-01-01', '2026-02-01', 'INR', 118000.00, 100000.00, 18000.00, 'DRAFT', '%s')", INV_PENDING_ID, SME_ORG_ID, SME_USER_ID));

                st.executeUpdate(String.format("INSERT INTO invoices (id, sme_org_id, buyer_gstin, buyer_name, buyer_pan, invoice_number, invoice_date, due_date, currency, total_amount, taxable_amount, tax_amount, status, created_by) " +
                        "VALUES ('%s', '%s', '27AAACT2727Q1ZW', 'Buyer Trigger Org', 'AAACT2727Q', 'INV-TRG-002', '2026-01-01', '2026-02-01', 'INR', 118000.00, 100000.00, 18000.00, 'DRAFT', '%s')", INV_VERIFIED_ID, SME_ORG_ID, SME_USER_ID));

                // Documents (sme_org_id and uploaded_by attached while invoice is in DRAFT)
                st.executeUpdate(String.format("INSERT INTO invoice_documents (id, invoice_id, sme_org_id, file_name, storage_path, file_size_bytes, mime_type, sha256_checksum, is_active, uploaded_by) " +
                        "VALUES ('%s', '%s', '%s', 'inv1.pdf', '/inv1.pdf', 1024, 'application/pdf', '%s', true, '%s')", DOC_PENDING_ID, INV_PENDING_ID, SME_ORG_ID, PENDING_CHECKSUM, SME_USER_ID));

                st.executeUpdate(String.format("INSERT INTO invoice_documents (id, invoice_id, sme_org_id, file_name, storage_path, file_size_bytes, mime_type, sha256_checksum, is_active, uploaded_by) " +
                        "VALUES ('%s', '%s', '%s', 'inv2.pdf', '/inv2.pdf', 1024, 'application/pdf', '%s', true, '%s')", DOC_VERIFIED_ID, INV_VERIFIED_ID, SME_ORG_ID, VALID_CHECKSUM, SME_USER_ID));

                // Transition to PENDING_VERIFICATION
                st.executeUpdate("UPDATE invoices SET status = 'PENDING_VERIFICATION'");

                // Requests (with valid 64-character hash)
                st.executeUpdate(String.format("INSERT INTO buyer_confirmation_requests (id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                        "VALUES ('%s', '%s', '%s', '%s', '27AAACT2727Q1ZW', 'buyer@trg.local', '1234567890123456789012345678901234567890123456789012345678901234', 'PENDING', NOW() + INTERVAL '7 days')", REQ_ID, INV_VERIFIED_ID, SME_ORG_ID, BUYER_ORG_ID));

                // Response (CONFIRMED)
                String canonConfirmed = String.format("{\"action\":\"CONFIRMED\",\"document_sha256\":\"%s\"}", VALID_CHECKSUM);
                st.executeUpdate(String.format(
                        "INSERT INTO buyer_confirmation_responses (id, request_id, invoice_id, sme_org_id, action, auth_channel, identity_assurance, client_ip, user_agent_hash, canonical_evidence_text, evidence_payload, evidence_sha256) " +
                                "VALUES ('%s', '%s', '%s', '%s', 'CONFIRMED', 'TOKEN_LINK', 'BEARER_TOKEN_POSSESSION', '192.168.1.1', 'uahash', '%s', '%s'::jsonb, encode(digest('%s', 'sha256'), 'hex'))",
                        RESP_CONFIRMED_ID, REQ_ID, INV_VERIFIED_ID, SME_ORG_ID, canonConfirmed, canonConfirmed, canonConfirmed
                ));

                // Transition INV_VERIFIED_ID to VERIFIED (using owner connection which is permitted)
                st.executeUpdate(String.format("UPDATE invoices SET status = 'VERIFIED', verified_confirmation_id = '%s', last_buyer_response_id = '%s' WHERE id = '%s'",
                        RESP_CONFIRMED_ID, RESP_CONFIRMED_ID, INV_VERIFIED_ID));
            }

            conn.commit();
        }
    }

    @Test
    void appWorker_directStatusTransitionToVerified_prohibitedByTrigger() throws Exception {
        try (Connection conn = getWorkerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.tenant_id = '" + SME_ORG_ID + "'");
                st.execute("SET LOCAL app.tenant_type = 'SME'");

                assertThatThrownBy(() -> st.executeUpdate("UPDATE invoices SET status = 'VERIFIED' WHERE id = '" + INV_PENDING_ID + "'"))
                        .hasMessageContaining("DIRECT_INVOICE_VERIFICATION_PROHIBITED");
            }
        }
    }

    @Test
    void appWorker_directStatusTransitionToRejected_prohibitedByTrigger() throws Exception {
        try (Connection conn = getWorkerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.tenant_id = '" + SME_ORG_ID + "'");
                st.execute("SET LOCAL app.tenant_type = 'SME'");

                assertThatThrownBy(() -> st.executeUpdate("UPDATE invoices SET status = 'REJECTED' WHERE id = '" + INV_PENDING_ID + "'"))
                        .hasMessageContaining("DIRECT_INVOICE_VERIFICATION_PROHIBITED");
            }
        }
    }

    @Test
    void appWorker_directBindingOfConfirmationId_prohibitedByTrigger() throws Exception {
        try (Connection conn = getWorkerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.tenant_id = '" + SME_ORG_ID + "'");
                st.execute("SET LOCAL app.tenant_type = 'SME'");

                assertThatThrownBy(() -> st.executeUpdate("UPDATE invoices SET verified_confirmation_id = gen_random_uuid() WHERE id = '" + INV_PENDING_ID + "'"))
                        .hasMessageContaining("DIRECT_INVOICE_VERIFICATION_PROHIBITED");
            }
        }
    }

    @Test
    void authorizedDefiner_transitionToVerified_withValidConfirmedResponse_succeeds() throws Exception {
        UUID validRespId = UUID.randomUUID();
        UUID validReqId = UUID.randomUUID();

        // Run transition as buyer_confirmation_definer role
        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // Request for INV_PENDING_ID
                st.executeUpdate(String.format("INSERT INTO buyer_confirmation_requests (id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                        "VALUES ('%s', '%s', '%s', '%s', '27AAACT2727Q1ZW', 'buyer@trg.local', '%s', 'PENDING', NOW() + INTERVAL '7 days')",
                        validReqId, INV_PENDING_ID, SME_ORG_ID, BUYER_ORG_ID, sha256("validtoken123")));

                // Response with matching document checksum (PENDING_CHECKSUM)
                String canonConfirmed = String.format("{\"action\":\"CONFIRMED\",\"document_sha256\":\"%s\"}", PENDING_CHECKSUM);
                st.executeUpdate(String.format(
                        "INSERT INTO buyer_confirmation_responses (id, request_id, invoice_id, sme_org_id, action, auth_channel, identity_assurance, client_ip, user_agent_hash, canonical_evidence_text, evidence_payload, evidence_sha256) " +
                                "VALUES ('%s', '%s', '%s', '%s', 'CONFIRMED', 'TOKEN_LINK', 'BEARER_TOKEN_POSSESSION', '192.168.1.1', 'uahash', '%s', '%s'::jsonb, encode(digest('%s', 'sha256'), 'hex'))",
                        validRespId, validReqId, INV_PENDING_ID, SME_ORG_ID, canonConfirmed, canonConfirmed, canonConfirmed
                ));

                st.execute("SET ROLE buyer_confirmation_definer");

                // Transitioning to VERIFIED with valid response ID and matching document checksum succeeds
                int updated = st.executeUpdate(String.format("UPDATE invoices SET status = 'VERIFIED', verified_confirmation_id = '%s' WHERE id = '%s'",
                        validRespId, INV_PENDING_ID));
                assertThat(updated).isEqualTo(1);
            }
            conn.rollback();
        }
    }

    @Test
    void authorizedDefiner_transitionToVerified_missingConfirmationId_rejectedByTrigger() throws Exception {
        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE buyer_confirmation_definer");

                assertThatThrownBy(() -> st.executeUpdate("UPDATE invoices SET status = 'VERIFIED', verified_confirmation_id = NULL WHERE id = '" + INV_PENDING_ID + "'"))
                        .hasMessageContaining("VERIFIED_INVOICE_MISSING_CONFIRMATION");
            }
            conn.rollback();
        }
    }

    @Test
    void authorizedDefiner_transitionToVerified_checksumMismatch_rejectedByTrigger() throws Exception {
        UUID mismatchedRespId = UUID.randomUUID();
        UUID mismatchedReqId = UUID.randomUUID();

        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // Request for INV_PENDING_ID
                st.executeUpdate(String.format("INSERT INTO buyer_confirmation_requests (id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                        "VALUES ('%s', '%s', '%s', '%s', '27AAACT2727Q1ZW', 'buyer@trg.local', '%s', 'PENDING', NOW() + INTERVAL '7 days')",
                        mismatchedReqId, INV_PENDING_ID, SME_ORG_ID, BUYER_ORG_ID, sha256("mismatchtoken123")));

                // Response with mismatched document checksum
                String canonMismatch = "{\"action\":\"CONFIRMED\",\"document_sha256\":\"0000000000000000000000000000000000000000000000000000000000000000\"}";
                st.executeUpdate(String.format(
                        "INSERT INTO buyer_confirmation_responses (id, request_id, invoice_id, sme_org_id, action, auth_channel, identity_assurance, client_ip, user_agent_hash, canonical_evidence_text, evidence_payload, evidence_sha256) " +
                                "VALUES ('%s', '%s', '%s', '%s', 'CONFIRMED', 'TOKEN_LINK', 'BEARER_TOKEN_POSSESSION', '192.168.1.1', 'uahash', '%s', '%s'::jsonb, encode(digest('%s', 'sha256'), 'hex'))",
                        mismatchedRespId, mismatchedReqId, INV_PENDING_ID, SME_ORG_ID, canonMismatch, canonMismatch, canonMismatch
                ));

                st.execute("SET ROLE buyer_confirmation_definer");

                assertThatThrownBy(() -> st.executeUpdate(String.format("UPDATE invoices SET status = 'VERIFIED', verified_confirmation_id = '%s' WHERE id = '%s'",
                        mismatchedRespId, INV_PENDING_ID)))
                        .hasMessageContaining("DOCUMENT_CHECKSUM_EVIDENCE_MISMATCH");
            }
            conn.rollback();
        }
    }

    @Test
    void verifiedInvoice_permanentImmutability_enforcedAcrossAllRoles() throws Exception {
        // Even table owner cannot change status of VERIFIED invoice
        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                assertThatThrownBy(() -> st.executeUpdate("UPDATE invoices SET status = 'DRAFT' WHERE id = '" + INV_VERIFIED_ID + "'"))
                        .hasMessageContaining("VERIFIED_INVOICE_IMMUTABLE");

                conn.rollback();

                assertThatThrownBy(() -> st.executeUpdate("UPDATE invoices SET verified_confirmation_id = gen_random_uuid() WHERE id = '" + INV_VERIFIED_ID + "'"))
                        .hasMessageContaining("VERIFIED_INVOICE_CONFIRMATION_IMMUTABLE");
            }
            conn.rollback();
        }
    }

    @Test
    void unintendedRoleContainingOwner_directTransitionToVerified_prohibitedByTrigger() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE pawnshop_owner");
                st.execute("SET LOCAL app.tenant_id = '" + SME_ORG_ID + "'");
                st.execute("SET LOCAL app.tenant_type = 'SME'");

                assertThatThrownBy(() -> st.executeUpdate("UPDATE invoices SET status = 'VERIFIED' WHERE id = '" + INV_PENDING_ID + "'"))
                        .hasMessageContaining("DIRECT_INVOICE_VERIFICATION_PROHIBITED");
            }
        }
    }

    @Test
    void tableOwner_transitionToVerified_missingConfirmationOrChecksum_failsUnconditionally() throws Exception {
        try (Connection conn = getOwnerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // Table owner (app_owner) cannot set status = 'VERIFIED' without verified_confirmation_id
                assertThatThrownBy(() -> st.executeUpdate("UPDATE invoices SET status = 'VERIFIED', verified_confirmation_id = NULL WHERE id = '" + INV_PENDING_ID + "'"))
                        .hasMessageContaining("VERIFIED_INVOICE_MISSING_CONFIRMATION");

                conn.rollback();

                // Table owner (app_owner) cannot set status = 'VERIFIED' with bogus confirmation ID
                assertThatThrownBy(() -> st.executeUpdate("UPDATE invoices SET status = 'VERIFIED', verified_confirmation_id = gen_random_uuid() WHERE id = '" + INV_PENDING_ID + "'"))
                        .hasMessageContaining("INVALID_CONFIRMATION_BINDING");
            }
            conn.rollback();
        }
    }
}
