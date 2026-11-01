package com.tcs.invoicefactoring.identity.service;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;
import com.tcs.invoicefactoring.identity.domain.Organization;
import com.tcs.invoicefactoring.identity.domain.User;
import com.tcs.invoicefactoring.identity.domain.UserOrganization;
import com.tcs.invoicefactoring.identity.dto.LoginRequest;
import com.tcs.invoicefactoring.identity.dto.LoginResponse;
import com.tcs.invoicefactoring.identity.dto.RegisterRequest;
import com.tcs.invoicefactoring.identity.repository.OrganizationRepository;
import com.tcs.invoicefactoring.identity.repository.UserOrganizationRepository;
import com.tcs.invoicefactoring.identity.repository.UserRepository;
import com.tcs.invoicefactoring.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication service — registration, login, and token issuance.
 *
 * Security rules:
 * - Passwords are NEVER logged.
 * - The role in the JWT is sourced from the DB membership record (not the request).
 * - Org membership is verified from the DB on every login.
 * - All mutations are within @Transactional boundaries.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final UserOrganizationRepository userOrgRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            UserOrganizationRepository userOrgRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.userOrgRepository = userOrgRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Register a new user and organisation.
     * Creates the user, the organisation, and the user-organisation membership in one transaction.
     */
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        log.info("Registering new user: email={}", request.email());

        if (userRepository.existsByEmail(request.email())) {
            throw new AppException(ErrorCode.USER_ALREADY_EXISTS);
        }

        // Create organisation
        Organization org = new Organization();
        org.setName(request.organizationName());
        org.setOrgType(request.organizationType());
        organizationRepository.save(org);

        // Create user — hash password with BCrypt (NEVER store raw password)
        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setActive(true);
        userRepository.save(user);

        // Create membership with role derived from org type
        String role = deriveRoleFromOrgType(request.organizationType());
        UserOrganization membership = new UserOrganization();
        membership.setUser(user);
        membership.setOrganization(org);
        membership.setRole(role);
        membership.setActive(true);
        userOrgRepository.save(membership);

        log.info("User registered successfully: userId={} orgId={} role={}",
                user.getId(), org.getId(), role);

        // Issue initial JWT
        String token = jwtService.generateToken(user.getId(), org.getId(), role);
        return new LoginResponse(token, user.getId(), org.getId(), role, user.getFullName());
    }

    /**
     * Authenticate a user and issue a JWT.
     * The JWT orgId and role are sourced from the DB membership — not from the request.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // Use a generic error message — do not reveal whether email exists
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_CREDENTIALS));

        if (!user.isEnabled()) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED);
        }

        // Verify password against stored BCrypt hash
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Failed login attempt for email={}", request.email());
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        // Load organisation membership from DB (trusted source — not JWT, not request body)
        UserOrganization membership = userOrgRepository
                .findFirstActiveMembership(user.getId())
                .orElseThrow(() -> new AppException(ErrorCode.ORG_NOT_FOUND,
                        "No active organisation membership found for this user"));

        String role = membership.getRole();
        java.util.UUID orgId = membership.getOrganization().getId();

        log.info("User logged in: userId={} orgId={} role={}", user.getId(), orgId, role);

        String token = jwtService.generateToken(user.getId(), orgId, role);
        return new LoginResponse(token, user.getId(), orgId, role, user.getFullName());
    }

    private String deriveRoleFromOrgType(Organization.OrgType orgType) {
        return switch (orgType) {
            case SME -> "SME";
            case FINANCIER -> "FINANCIER";
            case BUYER -> "BUYER";
            case PLATFORM -> "ADMIN";
        };
    }
}
