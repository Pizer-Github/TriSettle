package com.tcs.invoicefactoring.invoice.confirmation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    @DisplayName("RFC 8785 Section 3.2.2: Object key sorting follows UTF-16 code units")
    void rfc8785_section322_objectKeySorting() {
        // Test vector from RFC 8785 Section 3.2.2
        String input = "{\n" +
                "  \"\\u20ac\": \"Euro Sign\",\n" +
                "  \"\\r\": \"Carriage Return\",\n" +
                "  \"1\": \"One\",\n" +
                "  \"true\": \"True\",\n" +
                "  \"a\": \"Letter A\",\n" +
                "  \"\\u0080\": \"Control Character\"\n" +
                "}";

        String expected = "{\"\\r\":\"Carriage Return\",\"1\":\"One\",\"a\":\"Letter A\",\"true\":\"True\",\"\u0080\":\"Control Character\",\"\u20ac\":\"Euro Sign\"}";
        String actual = service.canonicalize(input);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("RFC 8785 Section 3.2.1: Whitespace is stripped outside string literals")
    void rfc8785_whitespaceStripping() {
        String input = "{\n  \"b\" :   2 , \n  \"a\" : 1 \n }";
        String expected = "{\"a\":1,\"b\":2}";
        assertThat(service.canonicalize(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("RFC 8785 Section 3.2.2.2: Forward slashes are not escaped")
    void rfc8785_forwardSlashNotEscaped() {
        String input = "{\"url\": \"https://example.com/api/v1/invoices\"}";
        String expected = "{\"url\":\"https://example.com/api/v1/invoices\"}";
        assertThat(service.canonicalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "RFC 8785 Appendix B: {0} canonicalizes to {1}")
    @CsvSource(delimiter = ';', value = {
            "{\"n\": 0} ; {\"n\":0}",
            "{\"n\": 1e23} ; {\"n\":1e+23}",
            "{\"n\": 1e21} ; {\"n\":1e+21}",
            "{\"n\": 0.000001} ; {\"n\":0.000001}",
            "{\"n\": 9.999999999999997e-7} ; {\"n\":9.999999999999997e-7}",
            "{\"n\": 333333333.3333332} ; {\"n\":333333333.3333332}"
    })
    void rfc8785_appendixB_numberSerialization(String rawInput, String expectedCanonical) {
        assertThat(service.canonicalize(rawInput)).isEqualTo(expectedCanonical);
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
                "27AAACT2727Q1ZW",
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
                "CONFIRMED", "TOKEN_LINK", "27AAACT2727Q1ZW", null, "test@example.com", now, null,
                "abc", LocalDate.now(), "BEARER_TOKEN_POSSESSION", LocalDate.now(), invoiceId, "INV-1",
                "ok", null, requestId, smeOrgId, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN
        );

        String c2 = service.buildCanonicalEvidence(
                "CONFIRMED", "TOKEN_LINK", "27AAACT2727Q1ZW", null, "test@example.com", now, null,
                "abc", LocalDate.now(), "BEARER_TOKEN_POSSESSION", LocalDate.now(), invoiceId, "INV-1",
                "ok", null, requestId, smeOrgId, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN
        );

        assertThat(c1).isEqualTo(c2);
        assertThat(service.computeSha256(c1)).isEqualTo(service.computeSha256(c2));
    }
}
