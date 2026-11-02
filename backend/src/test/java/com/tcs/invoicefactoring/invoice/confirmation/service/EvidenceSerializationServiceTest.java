package com.tcs.invoicefactoring.invoice.confirmation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceSerializationServiceTest {

    private EvidenceSerializationService service;

    @BeforeEach
    void setUp() {
        service = new EvidenceSerializationService();
    }

    @Test
    void canonicalJson_keysAreSortedLexicographically() {
        UUID invoiceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID smeOrgId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID requestId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID portalUserId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID buyerOrgId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        Instant timestamp = Instant.parse("2026-11-03T10:15:30Z");
        LocalDate invoiceDate = LocalDate.parse("2026-11-01");
        LocalDate dueDate = LocalDate.parse("2026-12-01");

        String canonical = service.buildCanonicalEvidence(
                "CONFIRMED",
                "PORTAL_USER",
                "27AABCU9603R1ZM",
                buyerOrgId,
                null,
                timestamp,
                null,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                dueDate,
                "AUTHENTICATED_CORPORATE_PORTAL",
                invoiceDate,
                invoiceId,
                "INV-2026-001",
                "Matched against Purchase Order",
                portalUserId,
                requestId,
                smeOrgId,
                new BigDecimal("1800.00"),
                new BigDecimal("10000.00"),
                new BigDecimal("11800.00")
        );

        // Verify key ordering
        int idxAction = canonical.indexOf("\"action\"");
        int idxAuth = canonical.indexOf("\"auth_channel\"");
        int idxGstin = canonical.indexOf("\"buyer_gstin\"");
        int idxDocSha = canonical.indexOf("\"document_sha256\"");
        int idxTotal = canonical.indexOf("\"total_amount\"");

        assertThat(idxAction).isLessThan(idxAuth);
        assertThat(idxAuth).isLessThan(idxGstin);
        assertThat(idxGstin).isLessThan(idxDocSha);
        assertThat(idxDocSha).isLessThan(idxTotal);

        // Verify quoted decimal formatting
        assertThat(canonical).contains("\"taxable_amount\":\"10000.00\"");
        assertThat(canonical).contains("\"tax_amount\":\"1800.00\"");
        assertThat(canonical).contains("\"total_amount\":\"11800.00\"");

        // Verify digest
        String sha256 = service.computeSha256(canonical);
        assertThat(sha256).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void canonicalJson_deterministicDigest() {
        UUID invoiceId = UUID.randomUUID();
        UUID smeOrgId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant now = Instant.now();

        String c1 = service.buildCanonicalEvidence(
                "CONFIRMED", "TOKEN_LINK", "27AABCU9603R1ZM", null, "test@example.com", now, null,
                "abc", LocalDate.now(), "BEARER_TOKEN_POSSESSION", LocalDate.now(), invoiceId, "INV-1",
                "ok", null, requestId, smeOrgId, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN
        );

        String c2 = service.buildCanonicalEvidence(
                "CONFIRMED", "TOKEN_LINK", "27AABCU9603R1ZM", null, "test@example.com", now, null,
                "abc", LocalDate.now(), "BEARER_TOKEN_POSSESSION", LocalDate.now(), invoiceId, "INV-1",
                "ok", null, requestId, smeOrgId, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN
        );

        assertThat(c1).isEqualTo(c2);
        assertThat(service.computeSha256(c1)).isEqualTo(service.computeSha256(c2));
    }
}
