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
 * - Public self-service registration is strictly restricted to SMEs.
 * - Passwords are NEVER logged.
 * - The role in the JWT is sourced strictly from DB membership records (never from request payloads).
 * - Org membership is verified from the DB on every login.
 * - All mutations are within @Transactional boundaries.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String DEFAULT_PUBLIC_ROLE = "SME";

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
     * Register a new SME user and organisation.
     * Public registration ALWAYS provisions Organization.OrgType.SME and role "SME".
     * Privileged roles (ADMIN, FINANCIER) cannot be provisioned through this endpoint.
     */
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        log.info("Registering new SME user: email={}", request.email());

        if (userRepository.existsByEmail(request.email())) {
            throw new AppException(ErrorCode.USER_ALREADY_EXISTS);
        }

        // Create SME organisation strictly — no client-supplied org_type
        Organization org = new Organization();
        org.setName(request.organizationName());
        org.setOrgType(Organization.OrgType.SME);
        organizationRepository.save(org);

        // Create user — hash password with BCrypt (NEVER store raw password)
        User user = new User();
        user.setEmail(request.email().toLowerCase().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName().trim());
        user.setActive(true);
        userRepository.save(user);

        // Create membership with SME role strictly
        UserOrganization membership = new UserOrganization();
        membership.setUser(user);
        membership.setOrganization(org);
        membership.setRole(DEFAULT_PUBLIC_ROLE);
        membership.setActive(true);
        userOrgRepository.save(membership);

        log.info("SME user registered successfully: userId={} orgId={} role={}",
                user.getId(), org.getId(), DEFAULT_PUBLIC_ROLE);

        // Issue initial JWT with verified DB context
        String token = jwtService.generateToken(user.getId(), org.getId(), DEFAULT_PUBLIC_ROLE);
        return new LoginResponse(token, user.getId(), org.getId(), DEFAULT_PUBLIC_ROLE, user.getFullName());
    }

    /**
     * Authenticate a user and issue a JWT.
     * The JWT orgId and role are sourced from trusted DB membership — not from request parameters.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // Use a generic error message — do not reveal whether email exists
        User user = userRepository.findByEmail(request.email().toLowerCase().trim())
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
}
