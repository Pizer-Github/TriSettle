package com.tcs.invoicefactoring.common.idempotency;

import com.tcs.invoicefactoring.identity.domain.Organization;
import com.tcs.invoicefactoring.identity.domain.User;
import com.tcs.invoicefactoring.identity.domain.UserOrganization;
import com.tcs.invoicefactoring.identity.repository.OrganizationRepository;
import com.tcs.invoicefactoring.identity.repository.UserOrganizationRepository;
import com.tcs.invoicefactoring.identity.repository.UserRepository;
import com.tcs.invoicefactoring.security.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class IdempotencyFencingIT {

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
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyKeyRepository idempotencyRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserOrganizationRepository userOrgRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            Organization org = new Organization();
            org.setName("Idempotency Test Org " + UUID.randomUUID());
            org.setOrgType(Organization.OrgType.SME);
            org = organizationRepository.save(org);
            tenantId = org.getId();

            User user = new User();
            user.setEmail("user-" + UUID.randomUUID() + "@example.com");
            user.setPasswordHash(passwordEncoder.encode("Password123!"));
            user.setFullName("Idempotency Tester");
            user = userRepository.save(user);
            userId = user.getId();

            UserOrganization uo = new UserOrganization();
            uo.setUser(user);
            uo.setOrganization(org);
            uo.setRole("SME");
            userOrgRepository.save(uo);

            return null;
        });

        TenantContextHolder.set(userId, tenantId, "SME");
    }

    @Test
    void fencingToken_rejectsStaleWorker_whenLeaseWasReclaimedBySecondWorker() {
        String endpoint = "POST /api/v1/invoices";
        String key = "lease-test-" + UUID.randomUUID();
        String hash = "hash-123";

        // Worker 1 acquires lease with Token T1
        IdempotencyLease lease1 = idempotencyService.acquireLease(tenantId, userId, endpoint, key, hash);
        UUID token1 = lease1.getFencingToken();
        assertThat(token1).isNotNull();

        // Simulate lease expiration in DB
        transactionTemplate.execute(status -> {
            IdempotencyKey record = idempotencyRepository.findById(lease1.getKeyId()).orElseThrow();
            record.setLeaseExpiresAt(Instant.now().minusSeconds(10));
            idempotencyRepository.save(record);
            return null;
        });

        // Worker 2 reclaims the expired lease with new Token T2
        IdempotencyLease lease2 = idempotencyService.acquireLease(tenantId, userId, endpoint, key, hash);
        UUID token2 = lease2.getFencingToken();
        assertThat(token2).isNotEqualTo(token1);

        // Worker 1 now attempts Phase 2 using the stale Token T1
        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            idempotencyService.verifyFencingTokenAndLock(tenantId, userId, endpoint, key, token1);
            return null;
        })).isInstanceOf(StaleIdempotencyLeaseException.class);
    }
}
