package com.tcs.invoicefactoring.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.identity.dto.LoginRequest;
import com.tcs.invoicefactoring.identity.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for authentication endpoints and security boundaries.
 * Includes regression tests for privilege escalation prevention.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AuthControllerIT {

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

    @Test
    @Transactional
    void register_withValidRequest_returns201WithSmeToken() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "smeuser@example.com",
                "SecurePassword123!",
                "SME Founder",
                "Acme Supplies Pvt Ltd"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.userId").isNotEmpty())
                .andExpect(jsonPath("$.data.orgId").isNotEmpty())
                .andExpect(jsonPath("$.data.role").value("SME"))
                .andExpect(jsonPath("$.data.fullName").value("SME Founder"));
    }

    @Test
    @Transactional
    void register_attemptingPrivilegeEscalation_alwaysProvisionsSmeOnly() throws Exception {
        // Attacker attempts to inject PLATFORM org_type and ADMIN role via raw JSON payload
        Map<String, Object> maliciousPayload = Map.of(
                "email", "attacker@evil.com",
                "password", "AttackerPassword123!",
                "fullName", "Malicious Actor",
                "organizationName", "Fake Platform Org",
                "organizationType", "PLATFORM",
                "role", "ADMIN"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousPayload)))
                .andExpect(status().isCreated())
                // Assert that the injected administrative role was strictly rejected
                .andExpect(jsonPath("$.data.role").value("SME"))
                .andExpect(jsonPath("$.data.role").value(not("ADMIN")));
    }

    @Test
    @Transactional
    void register_thenLogin_returnsVerifiedSmeToken() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest(
                "logintest@example.com",
                "SecurePassword123!",
                "Login Test User",
                "Login Test Org"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest("logintest@example.com", "SecurePassword123!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.role").value("SME"));
    }

    @Test
    void adminAuthentication_worksThroughControlledProvisioningOnly() throws Exception {
        // Authenticate with seeded administrator credentials (from V5 migration)
        LoginRequest adminLogin = new LoginRequest("admin@platform.local", "Admin@Secure2026!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void login_withInvalidCredentials_returns401WithoutLeakingInternals() throws Exception {
        LoginRequest request = new LoginRequest("nonexistent@example.com", "WrongPassword1!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value(not(containsString("stack"))))
                .andExpect(jsonPath("$.message").value(not(containsString("Exception"))));
    }

    @Test
    void register_withInvalidEmail_returns400WithFieldError() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "not-an-email",
                "SecurePassword123!",
                "Test User",
                "Test Org"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));
    }

    @Test
    @Transactional
    void register_withDuplicateEmail_returns409() throws Exception {
        RegisterRequest request = new RegisterRequest(
                "duplicate@example.com",
                "SecurePassword123!",
                "Test User",
                "Test Org"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("USER_ALREADY_EXISTS"));
    }

    @Test
    void actuatorHealth_isPublic_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_withoutToken_returns401Or403() throws Exception {
        mockMvc.perform(get("/api/v1/protected"))
                .andExpect(status().is4xxClientError());
    }
}
