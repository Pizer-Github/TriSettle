package com.tcs.invoicefactoring.identity.service;

import com.tcs.invoicefactoring.identity.domain.Organization;
import com.tcs.invoicefactoring.identity.domain.User;
import com.tcs.invoicefactoring.identity.domain.UserOrganization;
import com.tcs.invoicefactoring.identity.repository.OrganizationRepository;
import com.tcs.invoicefactoring.identity.repository.UserOrganizationRepository;
import com.tcs.invoicefactoring.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Secure, environment-driven platform administrator bootstrap.
 *
 * Security rules:
 * 1. No hardcoded or predictable administrator passwords exist in migrations or source code.
 * 2. Initial administrator credentials must be supplied via environment variables (BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PASSWORD).
 * 3. Idempotent: If an administrator account already exists, bootstrap creation is skipped and existing credentials are preserved.
 * 4. Administrator creation is auditable: explicitly logged without disclosing the password.
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private static final UUID PLATFORM_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final UserOrganizationRepository userOrgRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.bootstrap.email:}")
    private String bootstrapEmail;

    @Value("${app.admin.bootstrap.password:}")
    private String bootstrapPassword;

    public AdminBootstrapRunner(
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            UserOrganizationRepository userOrgRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.userOrgRepository = userOrgRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapEmail == null || bootstrapEmail.isBlank() || bootstrapPassword == null || bootstrapPassword.isBlank()) {
            log.info("Administrator bootstrap: No initial administrator credentials supplied in environment; bootstrap skipped.");
            return;
        }

        String normalizedEmail = bootstrapEmail.toLowerCase().trim();

        // Idempotency check: Do not recreate or overwrite if user already exists
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.info("Administrator bootstrap: Administrator account [{}] already exists; existing credentials preserved.", normalizedEmail);
            return;
        }

        // Locate Platform Organization seeded by V5 migration
        Optional<Organization> platformOrgOpt = organizationRepository.findById(PLATFORM_ORG_ID);
        if (platformOrgOpt.isEmpty()) {
            log.warn("Administrator bootstrap: Platform organization not found. Ensure V5 migration has executed.");
            return;
        }

        Organization platformOrg = platformOrgOpt.get();

        // Create Administrator User
        User adminUser = new User();
        adminUser.setEmail(normalizedEmail);
        adminUser.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
        adminUser.setFullName("Platform Administrator");
        adminUser.setActive(true);
        adminUser.setEmailVerified(true);
        userRepository.save(adminUser);

        // Assign ADMIN Role
        UserOrganization adminMembership = new UserOrganization();
        adminMembership.setUser(adminUser);
        adminMembership.setOrganization(platformOrg);
        adminMembership.setRole("ADMIN");
        adminMembership.setActive(true);
        userOrgRepository.save(adminMembership);

        log.info("AUDIT: Initial platform administrator successfully provisioned: userId={} email={} orgId={}",
                adminUser.getId(), normalizedEmail, platformOrg.getId());
    }
}
