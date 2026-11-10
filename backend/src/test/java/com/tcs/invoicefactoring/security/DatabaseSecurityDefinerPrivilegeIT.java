package com.tcs.invoicefactoring.security;

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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public class DatabaseSecurityDefinerPrivilegeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("privilege_test_db")
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
    static void createUnprivilegedRole() throws Exception {
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            st.execute("DO $$ BEGIN " +
                    "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon_test_user') THEN " +
                    "  CREATE ROLE anon_test_user WITH NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT; " +
                    "END IF; " +
                    "END $$;");
        }
    }

    @Test
    void publicRole_executionDenied_onAllBuyerConfirmationFunctions() throws Exception {
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            String[] functions = {
                    "fn_issue_buyer_confirmation_request",
                    "fn_exchange_buyer_confirmation_token",
                    "fn_acquire_session_lease",
                    "fn_submit_buyer_confirmation_response",
                    "fn_cancel_buyer_confirmation_request",
                    "fn_resend_buyer_confirmation_request",
                    "fn_mark_request_expired",
                    "fn_claim_outbox_events"
            };

            for (String func : functions) {
                ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM information_schema.routine_privileges " +
                                "WHERE routine_name = '" + func + "' AND grantee = 'PUBLIC'");
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }
        }

        // Direct call as unprivileged user must fail with permission denied
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET ROLE anon_test_user");
                assertThatThrownBy(() -> st.executeQuery("SELECT * FROM fn_mark_request_expired(10)"))
                        .hasMessageContaining("permission denied for function fn_mark_request_expired");
            }
        }
    }

    @Test
    void appWorkerRole_hasExecuteOnly_onTheEightFunctions() throws Exception {
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            String[] functions = {
                    "fn_issue_buyer_confirmation_request",
                    "fn_exchange_buyer_confirmation_token",
                    "fn_acquire_session_lease",
                    "fn_submit_buyer_confirmation_response",
                    "fn_cancel_buyer_confirmation_request",
                    "fn_resend_buyer_confirmation_request",
                    "fn_mark_request_expired",
                    "fn_claim_outbox_events"
            };

            for (String func : functions) {
                ResultSet rs = st.executeQuery(
                        "SELECT privilege_type FROM information_schema.routine_privileges " +
                                "WHERE routine_name = '" + func + "' AND grantee = 'app_worker'");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("privilege_type")).isEqualTo("EXECUTE");
            }
        }
    }

    @Test
    void buyerConfirmationDefiner_tablePrivileges_enforceLeastPrivilege() throws Exception {
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            // Definer must NOT have DELETE on invoices or confirmation tables
            ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.table_privileges " +
                            "WHERE grantee = 'buyer_confirmation_definer' AND privilege_type = 'DELETE'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(0);

            // Definer must NOT have UPDATE on financial amount columns of invoices
            rs = st.executeQuery(
                    "SELECT column_name FROM information_schema.column_privileges " +
                            "WHERE grantee = 'buyer_confirmation_definer' AND table_name = 'invoices' AND privilege_type = 'UPDATE'");
            while (rs.next()) {
                String col = rs.getString("column_name");
                assertThat(col).doesNotContain("total_amount", "taxable_amount", "tax_amount");
            }
        }
    }

    @Test
    void definerFunctions_enforceRuntimeTenantChecks_againstCallerGuc() throws Exception {
        UUID callerOrgId = UUID.randomUUID();
        UUID otherSmeOrgId = UUID.randomUUID();

        try (Connection conn = getWorkerConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET LOCAL app.tenant_id = '" + callerOrgId + "'");
                st.execute("SET LOCAL app.tenant_type = 'SME'");

                assertThatThrownBy(() -> st.executeQuery(String.format(
                        "SELECT * FROM fn_issue_buyer_confirmation_request('%s', '%s', 'buyer@test.com', 'dummyhash12345678901234567890123456789012345678901234567890123456', 'payload', 1)",
                        UUID.randomUUID(), otherSmeOrgId
                ))).hasMessageContaining("TENANT_ACCESS_DENIED");
            }
        }
    }

    @Test
    void definerFunctions_immutableSearchPath_pgCatalogPublicPgTemp() throws Exception {
        try (Connection conn = getOwnerConnection(); Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT proname, array_to_string(proconfig, ',') as config FROM pg_proc " +
                            "WHERE proname IN (" +
                            "'fn_issue_buyer_confirmation_request', " +
                            "'fn_exchange_buyer_confirmation_token', " +
                            "'fn_acquire_session_lease', " +
                            "'fn_submit_buyer_confirmation_response', " +
                            "'fn_cancel_buyer_confirmation_request', " +
                            "'fn_resend_buyer_confirmation_request', " +
                            "'fn_mark_request_expired', " +
                            "'fn_claim_outbox_events')");

            int count = 0;
            while (rs.next()) {
                count++;
                assertThat(rs.getString("config")).contains("search_path=pg_catalog, public, pg_temp");
            }
            assertThat(count).isEqualTo(8);
        }
    }
}
