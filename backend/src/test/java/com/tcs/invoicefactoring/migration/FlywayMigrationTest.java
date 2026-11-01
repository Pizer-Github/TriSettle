package com.tcs.invoicefactoring.migration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Flyway migration tests.
 * Verifies that all migrations apply cleanly to a fresh PostgreSQL database.
 *
 * Uses Testcontainers to spin up a real PostgreSQL 16 instance.
 * Tests run against an ephemeral database — no state is persisted between test runs.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("test_db")
            .withUsername("test_owner")
            .withPassword("test_password");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Runtime datasource (app_worker simulation — same credentials in test)
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Flyway uses the same credentials in test (no separate owner role in test container)
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Test
    void contextLoads_withAllMigrationsApplied() {
        // If this test passes, the Spring context started successfully,
        // which means Flyway ran all migrations without error.
        // The Spring context load itself validates this.
    }
}
