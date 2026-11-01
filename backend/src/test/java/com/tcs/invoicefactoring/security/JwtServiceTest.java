package com.tcs.invoicefactoring.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtService}.
 * Verifies token generation, claim extraction, signature validation, and tamper detection.
 * Focused unit test suite: independent of database, Flyway migrations, and Spring context.
 */
class JwtServiceTest {

    private static final String TEST_SECRET =
            "ci-dummy-jwt-secret-key-that-is-at-least-64-characters-long-for-hmac-sha256-signing";
    private static final long EXPIRATION_MS = 3600000;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(TEST_SECRET, EXPIRATION_MS);
    }

    @Test
    void generateToken_thenValidate_returnsCorrectClaims() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String role = "SME";

        String token = jwtService.generateToken(userId, orgId, role);

        assertThat(token).isNotBlank();

        Claims claims = jwtService.validateAndExtract(token);
        assertThat(jwtService.extractUserId(claims)).isEqualTo(userId);
        assertThat(jwtService.extractOrgId(claims)).isEqualTo(orgId);
        assertThat(jwtService.extractRole(claims)).isEqualTo(role);
    }

    @Test
    void validateToken_withTamperedToken_throwsJwtException() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, orgId, "SME");

        // Tamper with the signature
        String tamperedToken = token.substring(0, token.lastIndexOf('.') + 1) + "tampered_signature";

        assertThatThrownBy(() -> jwtService.validateAndExtract(tamperedToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void constructor_withShortSecret_throwsIllegalArgumentException() {
        String shortSecret = "too-short-secret-less-than-64-characters";
        assertThatThrownBy(() -> new JwtService(shortSecret, EXPIRATION_MS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 64 characters");
    }

    @Test
    void constructor_withNullSecret_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new JwtService(null, EXPIRATION_MS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
