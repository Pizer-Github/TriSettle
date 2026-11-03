package com.tcs.invoicefactoring.invoice.confirmation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.invoice.confirmation.dto.TokenExchangeRequest;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
public class BuyerConfirmationRateThrottlingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("throttling_test_db")
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static Connection getConnection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void throttleThreshold_threeAttemptsAllowed_fourthAttemptReturns429() throws Exception {
        String clientIp = "198.51.100.1";
        TokenExchangeRequest badReq = new TokenExchangeRequest("badtoken123456789012345678901234567890123456789012345678901234567890");

        // Attempt 1: 404, counter = 1
        mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                        .with(request -> { request.setRemoteAddr(clientIp); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isNotFound());

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT failed_attempts, throttled_until FROM confirmation_attempt_throttles WHERE client_ip = '" + clientIp + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("failed_attempts")).isEqualTo(1);
            assertThat(rs.getTimestamp("throttled_until")).isNull();
        }

        // Attempt 2: 404, counter = 2
        mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                        .with(request -> { request.setRemoteAddr(clientIp); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isNotFound());

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT failed_attempts, throttled_until FROM confirmation_attempt_throttles WHERE client_ip = '" + clientIp + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("failed_attempts")).isEqualTo(2);
            assertThat(rs.getTimestamp("throttled_until")).isNull();
        }

        // Attempt 3: 404, counter = 3, throttled_until is set to 1 minute in future
        mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                        .with(request -> { request.setRemoteAddr(clientIp); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isNotFound());

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT failed_attempts, throttled_until FROM confirmation_attempt_throttles WHERE client_ip = '" + clientIp + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("failed_attempts")).isEqualTo(3);
            assertThat(rs.getTimestamp("throttled_until")).isNotNull();
        }

        // Attempt 4: 429 Too Many Requests
        mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                        .with(request -> { request.setRemoteAddr(clientIp); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));

        // Counter remains 3 because rate check short-circuited before counter increment
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT failed_attempts FROM confirmation_attempt_throttles WHERE client_ip = '" + clientIp + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("failed_attempts")).isEqualTo(3);
        }
    }

    @Test
    void throttleReset_counterResetsOrContinues_afterThrottledUntilExpires() throws Exception {
        String clientIp = "198.51.100.2";
        TokenExchangeRequest badReq = new TokenExchangeRequest("badtoken123456789012345678901234567890123456789012345678901234567890");

        // Seed with failed_attempts = 3 and throttled_until in the past
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(String.format(
                    "INSERT INTO confirmation_attempt_throttles (client_ip, failed_attempts, throttled_until, last_attempt_at) " +
                            "VALUES ('%s', 3, NOW() - INTERVAL '1 second', NOW() - INTERVAL '1 minute')", clientIp
            ));
        }

        // Attempt 4 proceeds because throttled_until expired! On failure, counter increments to 4
        mockMvc.perform(post("/api/v1/buyer/confirmations/exchange")
                        .with(request -> { request.setRemoteAddr(clientIp); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badReq)))
                .andExpect(status().isNotFound());

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT failed_attempts, throttled_until FROM confirmation_attempt_throttles WHERE client_ip = '" + clientIp + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("failed_attempts")).isEqualTo(4);
            assertThat(rs.getTimestamp("throttled_until")).isNotNull();
        }
    }

    @Test
    void atomicCounterConcurrency_tenConcurrentThreads_exactCountTen() throws Exception {
        String clientIp = "198.51.100.3";
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
                    st.executeUpdate(String.format(
                            "INSERT INTO confirmation_attempt_throttles (client_ip, failed_attempts, throttled_until, last_attempt_at) " +
                                    "VALUES ('%s', 1, NULL, NOW()) " +
                                    "ON CONFLICT (client_ip) DO UPDATE " +
                                    "SET failed_attempts = confirmation_attempt_throttles.failed_attempts + 1, " +
                                    "    last_attempt_at = NOW()",
                            clientIp
                    ));
                } finally {
                    doneLatch.countDown();
                }
                return null;
            }));
        }

        // Release all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        for (Future<Void> future : futures) {
            future.get(); // ensure no thread threw an exception
        }

        // Query database: failed_attempts must be exactly 10 with 0 lost updates
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT failed_attempts FROM confirmation_attempt_throttles WHERE client_ip = '" + clientIp + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("failed_attempts")).isEqualTo(10);
        }

        executor.shutdown();
    }
}
