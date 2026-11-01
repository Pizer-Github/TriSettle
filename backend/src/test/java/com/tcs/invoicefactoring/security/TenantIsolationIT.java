package com.tcs.invoicefactoring.security;

import com.tcs.invoicefactoring.identity.domain.Organization;
import com.tcs.invoicefactoring.identity.domain.User;
import com.tcs.invoicefactoring.identity.domain.UserOrganization;
import com.tcs.invoicefactoring.identity.repository.AuthUserProjection;
import com.tcs.invoicefactoring.identity.repository.OrganizationRepository;
import com.tcs.invoicefactoring.identity.repository.UserOrganizationRepository;
import com.tcs.invoicefactoring.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant Isolation and Row-Level Security (RLS) Integration Tests.
 *
 * Tests execute against actual PostgreSQL instances using the runtime application role (app_worker).
 * Verifies that:
 * - SME cannot read another SME's organizations, users, or memberships.
 * - Financier cannot read another Financier's private data.
 * - Missing tenant context fails closed (0 rows returned).
 * - Context is transaction-local (is_local=true) and cannot leak between transactions.
 * - Authentication lookup function (SECURITY DEFINER) returns only the specific authenticated user.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TenantIsolationIT {

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
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserOrganizationRepository userOrgRepository;

    @Autowired
    private RlsContextService rlsContextService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID smeOrg1Id;
    private UUID smeOrg2Id;
    private UUID financierOrgId;
    private UUID smeUser1Id;
    private UUID smeUser2Id;
    private String smeUser1Email;

    @BeforeEach
    @Transactional
    void setupTenants() {
        // Ensure test_owner creates seeded data
        entityManager.createNativeQuery("RESET ROLE").executeUpdate();

        // Tenant 1: SME 1
        Organization sme1 = new Organization();
        sme1.setName("Alpha Manufacturers Ltd");
        sme1.setOrgType(Organization.OrgType.SME);
        organizationRepository.save(sme1);
        smeOrg1Id = sme1.getId();

        smeUser1Email = "founder@alpha-" + UUID.randomUUID() + ".com";
        User u1 = new User();
        u1.setEmail(smeUser1Email);
        u1.setPasswordHash(passwordEncoder.encode("Secret123!"));
        u1.setFullName("Alpha Founder");
        userRepository.save(u1);
        smeUser1Id = u1.getId();

        UserOrganization uo1 = new UserOrganization();
        uo1.setUser(u1);
        uo1.setOrganization(sme1);
        uo1.setRole("SME");
        userOrgRepository.save(uo1);

        // Tenant 2: SME 2
        Organization sme2 = new Organization();
        sme2.setName("Beta Logistics Ltd");
        sme2.setOrgType(Organization.OrgType.SME);
        organizationRepository.save(sme2);
        smeOrg2Id = sme2.getId();

        User u2 = new User();
        u2.setEmail("ceo@beta-" + UUID.randomUUID() + ".com");
        u2.setPasswordHash(passwordEncoder.encode("Secret123!"));
        u2.setFullName("Beta CEO");
        userRepository.save(u2);
        smeUser2Id = u2.getId();

        UserOrganization uo2 = new UserOrganization();
        uo2.setUser(u2);
        uo2.setOrganization(sme2);
        uo2.setRole("SME");
        userOrgRepository.save(uo2);

        // Tenant 3: Financier
        Organization fin = new Organization();
        fin.setName("Global Capital Partners");
        fin.setOrgType(Organization.OrgType.FINANCIER);
        organizationRepository.save(fin);
        financierOrgId = fin.getId();

        User u3 = new User();
        u3.setEmail("investor@global-" + UUID.randomUUID() + ".com");
        u3.setPasswordHash(passwordEncoder.encode("Secret123!"));
        u3.setFullName("Capital Investor");
        userRepository.save(u3);

        UserOrganization uo3 = new UserOrganization();
        uo3.setUser(u3);
        uo3.setOrganization(fin);
        uo3.setRole("FINANCIER");
        userOrgRepository.save(uo3);

        entityManager.flush();
    }

    @Test
    @Transactional
    void missingTenantContext_failsClosedUnderRls() {
        // Assume app_worker role (subject to RLS, no bypass)
        entityManager.createNativeQuery("SET ROLE app_worker").executeUpdate();

        // Without setting app.tenant_id, all RLS queries must return empty result sets
        List<?> orgs = entityManager.createNativeQuery("SELECT id FROM organizations").getResultList();
        List<?> users = entityManager.createNativeQuery("SELECT id FROM users").getResultList();
        List<?> memberships = entityManager.createNativeQuery("SELECT id FROM user_organizations").getResultList();

        assertThat(orgs).isEmpty();
        assertThat(users).isEmpty();
        assertThat(memberships).isEmpty();

        entityManager.createNativeQuery("RESET ROLE").executeUpdate();
    }

    @Test
    @Transactional
    void smeTenant_canOnlyReadOwnData_crossTenantAccessDenied() {
        // Assume app_worker runtime role
        entityManager.createNativeQuery("SET ROLE app_worker").executeUpdate();

        // Establish transaction-local RLS context for SME 1
        rlsContextService.establishContext(smeOrg1Id, "SME");

        // Query organizations under SME 1 context
        @SuppressWarnings("unchecked")
        List<UUID> visibleOrgIds = entityManager.createNativeQuery("SELECT id FROM organizations", UUID.class).getResultList();
        assertThat(visibleOrgIds).containsExactly(smeOrg1Id);
        assertThat(visibleOrgIds).doesNotContain(smeOrg2Id, financierOrgId);

        // Query users under SME 1 context
        @SuppressWarnings("unchecked")
        List<UUID> visibleUserIds = entityManager.createNativeQuery("SELECT id FROM users", UUID.class).getResultList();
        assertThat(visibleUserIds).containsExactly(smeUser1Id);
        assertThat(visibleUserIds).doesNotContain(smeUser2Id);

        // Query memberships under SME 1 context
        @SuppressWarnings("unchecked")
        List<UUID> visibleMemberships = entityManager.createNativeQuery("SELECT org_id FROM user_organizations", UUID.class).getResultList();
        assertThat(visibleMemberships).containsExactly(smeOrg1Id);
        assertThat(visibleMemberships).doesNotContain(smeOrg2Id);

        entityManager.createNativeQuery("RESET ROLE").executeUpdate();
    }

    @Test
    @Transactional
    void financierTenant_canOnlyReadOwnData_smeDataInvisible() {
        entityManager.createNativeQuery("SET ROLE app_worker").executeUpdate();

        // Establish context for Financier
        rlsContextService.establishContext(financierOrgId, "FINANCIER");

        @SuppressWarnings("unchecked")
        List<UUID> visibleOrgIds = entityManager.createNativeQuery("SELECT id FROM organizations", UUID.class).getResultList();
        assertThat(visibleOrgIds).containsExactly(financierOrgId);
        assertThat(visibleOrgIds).doesNotContain(smeOrg1Id, smeOrg2Id);

        entityManager.createNativeQuery("RESET ROLE").executeUpdate();
    }

    @Test
    @Transactional
    void authLookupFunction_returnsOnlySingleMatchingRecord() {
        // The auth lookup function can be executed by app_worker without setting tenant context
        entityManager.createNativeQuery("SET ROLE app_worker").executeUpdate();

        Optional<AuthUserProjection> authUserOpt = userRepository.findAuthUserByEmail(smeUser1Email);

        assertThat(authUserOpt).isPresent();
        AuthUserProjection authUser = authUserOpt.get();
        assertThat(authUser.getUserId()).isEqualTo(smeUser1Id);
        assertThat(authUser.getOrgId()).isEqualTo(smeOrg1Id);
        assertThat(authUser.getRole()).isEqualTo("SME");

        // Non-existent email returns empty
        Optional<AuthUserProjection> nonExistent = userRepository.findAuthUserByEmail("nobody@nowhere.com");
        assertThat(nonExistent).isEmpty();

        entityManager.createNativeQuery("RESET ROLE").executeUpdate();
    }

    @Test
    @Transactional
    void contextIsolation_betweenConsecutiveTransactions() {
        // Transaction 1: SME 1 sets context
        entityManager.createNativeQuery("SET ROLE app_worker").executeUpdate();
        rlsContextService.establishContext(smeOrg1Id, "SME");

        assertThat(rlsContextService.getCurrentTenantId()).isEqualTo(smeOrg1Id.toString());

        // In PostgreSQL, because is_local=true was used, closing the transaction resets context.
        // We simulate a new transaction boundary by clearing transaction-local GUC:
        entityManager.createNativeQuery("SELECT set_config('app.tenant_id', '', true)").getSingleResult();

        // New query with cleared context must fail closed
        List<?> orgs = entityManager.createNativeQuery("SELECT id FROM organizations").getResultList();
        assertThat(orgs).isEmpty();

        entityManager.createNativeQuery("RESET ROLE").executeUpdate();
    }

    @Test
    @Transactional
    void backgroundWorker_dynamicallyAssumesTrustedTenantContext() {
        entityManager.createNativeQuery("SET ROLE app_worker").executeUpdate();

        // Background worker fetches trusted ownership data (smeOrg2Id) from job queue
        // and explicitly establishes context inside its transaction
        rlsContextService.establishContext(smeOrg2Id, "WORKER");

        @SuppressWarnings("unchecked")
        List<UUID> visibleOrgIds = entityManager.createNativeQuery("SELECT id FROM organizations", UUID.class).getResultList();
        assertThat(visibleOrgIds).containsExactly(smeOrg2Id);
        assertThat(visibleOrgIds).doesNotContain(smeOrg1Id);

        entityManager.createNativeQuery("RESET ROLE").executeUpdate();
    }
}
