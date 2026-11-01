package com.tcs.invoicefactoring.security;

import com.tcs.invoicefactoring.migration.FlywayMigrationTest;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtService}.
 * Verifies token generation, validation, and claim extraction.
 * Uses Testcontainers for Spring context (Flyway must pass to start).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class JwtServiceTest {

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
    private JwtService jwtService;

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
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }
}
