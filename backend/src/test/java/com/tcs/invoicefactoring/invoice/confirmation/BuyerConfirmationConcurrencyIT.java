package com.tcs.invoicefactoring.invoice.confirmation;

import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationRequest;
import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationSession;
import com.tcs.invoicefactoring.invoice.confirmation.dto.SubmitConfirmationRequest;
import com.tcs.invoicefactoring.invoice.confirmation.dto.TokenExchangeResponseDto;
import com.tcs.invoicefactoring.invoice.confirmation.exception.ConfirmationSessionExpiredException;
import com.tcs.invoicefactoring.invoice.confirmation.exception.InvalidConfirmationTokenException;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationRequestRepository;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationSessionRepository;
import com.tcs.invoicefactoring.invoice.confirmation.service.BuyerConfirmationMutationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public class BuyerConfirmationConcurrencyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("concurrency_test_db")
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
    private BuyerConfirmationMutationService mutationService;

    @Autowired
    private BuyerConfirmationRequestRepository requestRepository;

    @Autowired
    private BuyerConfirmationSessionRepository sessionRepository;

    private static final UUID SME_ORG_ID = UUID.randomUUID();
    private static final UUID BUYER_ORG_ID = UUID.randomUUID();
    private static final UUID SME_USER_ID = UUID.randomUUID();
    private static final UUID BUYER_USER_ID = UUID.randomUUID();

    private static Connection getConnection() throws Exception {
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

    @BeforeEach
    void seedBaseOrgs() throws Exception {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM buyer_confirmation_responses; " +
                    "DELETE FROM buyer_confirmation_sessions; " +
                    "DELETE FROM buyer_confirmation_requests; " +
                    "DELETE FROM invoice_documents; " +
                    "DELETE FROM invoices; " +
                    "DELETE FROM organization_tax_profiles; " +
                    "DELETE FROM user_organizations; " +
                    "DELETE FROM users; " +
                    "DELETE FROM organizations;");

            st.executeUpdate(String.format("INSERT INTO organizations (id, name, org_type) VALUES ('%s', 'SME Conc Org', 'SME'), ('%s', 'Buyer Conc Org', 'BUYER')", SME_ORG_ID, BUYER_ORG_ID));
            st.executeUpdate(String.format("INSERT INTO users (id, email, password_hash, full_name) VALUES ('%s', 'sme@conc.local', '$2a$10$dummy', 'SME'), ('%s', 'buyer@conc.local', '$2a$10$dummy', 'Buyer')", SME_USER_ID, BUYER_USER_ID));
            st.executeUpdate(String.format("INSERT INTO user_organizations (user_id, org_id, role) VALUES ('%s', '%s', 'SME'), ('%s', '%s', 'BUYER')", SME_USER_ID, SME_ORG_ID, BUYER_USER_ID, BUYER_ORG_ID));
            st.executeUpdate(String.format("INSERT INTO organization_tax_profiles (org_id, gstin, is_verified, verified_by_user_id, status) VALUES ('%s', '27AAACT2727Q1ZW', true, '%s', 'ACTIVE')", BUYER_ORG_ID, BUYER_USER_ID));
        }
    }

    private void seedInvoiceAndRequest(UUID invoiceId, UUID requestId, String rawToken) throws Exception {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(String.format("INSERT INTO invoices (id, sme_org_id, buyer_gstin, buyer_name, buyer_pan, invoice_number, invoice_date, due_date, currency, total_amount, taxable_amount, tax_amount, status) " +
                    "VALUES ('%s', '%s', '27AAACT2727Q1ZW', 'Buyer Conc Org', 'AAACT2727Q', 'INV-%s', '2026-01-01', '2026-02-01', 'INR', 118000.00, 100000.00, 18000.00, 'PENDING_VERIFICATION')", invoiceId, SME_ORG_ID, UUID.randomUUID().toString().substring(0, 8)));

            st.executeUpdate(String.format("INSERT INTO buyer_confirmation_requests (id, invoice_id, sme_org_id, buyer_org_id, buyer_gstin, recipient_email, secure_token_hash, status, expires_at) " +
                    "VALUES ('%s', '%s', '%s', '%s', '27AAACT2727Q1ZW', 'buyer@conc.local', '%s', 'PENDING', NOW() + INTERVAL '7 days')", requestId, invoiceId, SME_ORG_ID, BUYER_ORG_ID, sha256(rawToken)));
        }
    }

    @Test
    void concurrentCancellationAndExchange_serializesCleanlyUnderInvoiceAndRequestLocks() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String rawToken = "concurrenttoken12345678901234567890123456789012345678901234567890123456";
        seedInvoiceAndRequest(invoiceId, requestId, rawToken);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicBoolean cancelSucceeded = new AtomicBoolean(false);
        AtomicBoolean exchangeSucceeded = new AtomicBoolean(false);
        AtomicReference<Exception> cancelEx = new AtomicReference<>();
        AtomicReference<Exception> exchangeEx = new AtomicReference<>();

        // Thread 1: SME Cancellation
        executor.submit(() -> {
            try {
                startLatch.await();
                mutationService.cancelRequest(SME_ORG_ID, SME_USER_ID, requestId);
                cancelSucceeded.set(true);
            } catch (Exception e) {
                cancelEx.set(e);
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Debtor Token Exchange
        executor.submit(() -> {
            try {
                startLatch.await();
                mutationService.exchangeToken(rawToken, "192.168.1.1", "agent", "TOKEN_LINK", null, null);
                exchangeSucceeded.set(true);
            } catch (Exception e) {
                exchangeEx.set(e);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        executor.shutdown();

        // Either cancel won, or exchange won first:
        // In either case: no deadlocks!
        BuyerConfirmationRequest finalReq = requestRepository.findById(requestId).orElseThrow();
        assertThat(finalReq.getStatus()).isEqualTo("CANCELLED");

        // Any sessions created for this request must be REVOKED
        List<BuyerConfirmationSession> sessions = sessionRepository.findByRequestId(requestId);
        for (BuyerConfirmationSession sess : sessions) {
            assertThat(sess.getStatus()).isEqualTo("REVOKED");
        }
    }

    @Test
    void usabilityDenial_cancelledRequest_deniesSubsequentTokenExchange() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String rawToken = "usabilitytoken1234567890123456789012345678901234567890123456789012345";
        seedInvoiceAndRequest(invoiceId, requestId, rawToken);

        // Cancel request first
        mutationService.cancelRequest(SME_ORG_ID, SME_USER_ID, requestId);

        // Attempt exchange must be rejected
        assertThatThrownBy(() -> mutationService.exchangeToken(rawToken, "192.168.1.1", "agent", "TOKEN_LINK", null, null))
                .isInstanceOf(InvalidConfirmationTokenException.class)
                .hasMessageContaining("no longer pending");
    }

    @Test
    void usabilityDenial_revokedSession_deniesLeaseAcquisitionAndResponseSubmission() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String rawToken = "usabilityrevoketoken12345678901234567890123456789012345678901234567";
        seedInvoiceAndRequest(invoiceId, requestId, rawToken);

        // Exchange token to acquire active session
        TokenExchangeResponseDto exchangeDto = mutationService.exchangeToken(rawToken, "192.168.1.1", "agent", "TOKEN_LINK", null, null);
        String rawSessionToken = exchangeDto.confirmationSessionToken();

        // Now cancel the parent request (which revokes active session)
        mutationService.cancelRequest(SME_ORG_ID, SME_USER_ID, requestId);

        // Attempting to acquire lease with revoked session must fail
        assertThatThrownBy(() -> mutationService.acquireSessionLease(rawSessionToken, "idemp-key-revoked", "reqhash", 30))
                .isInstanceOf(ConfirmationSessionExpiredException.class)
                .hasMessageContaining("no longer active");

        // Attempting to respond with revoked session must fail
        SubmitConfirmationRequest submitReq = new SubmitConfirmationRequest("CONFIRMED", null, null, null);
        assertThatThrownBy(() -> mutationService.respondToConfirmation(
                rawSessionToken, 1L, "idemp-key-revoked", submitReq, "192.168.1.1", "agent", "TOKEN_LINK", null, null))
                .isInstanceOf(ConfirmationSessionExpiredException.class)
                .hasMessageContaining("not active");
    }
}
