package com.tcs.invoicefactoring.identity.service;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;
import com.tcs.invoicefactoring.identity.dto.LoginRequest;
import com.tcs.invoicefactoring.identity.dto.LoginResponse;
import com.tcs.invoicefactoring.identity.dto.RegisterRequest;
import com.tcs.invoicefactoring.identity.repository.AuthUserProjection;
import com.tcs.invoicefactoring.identity.repository.SmeRegistrationProjection;
import com.tcs.invoicefactoring.identity.repository.UserRepository;
import com.tcs.invoicefactoring.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Authentication service — registration, login, and token issuance.
 *
 * Security architecture:
 * - Public self-service registration is strictly restricted to SMEs.
 * - Controlled database functions (SECURITY DEFINER) execute registration and pre-auth lookups,
 *   preserving strict table RLS without granting app_worker general bypass permissions.
 * - Passwords are NEVER logged.
 * - The role in the JWT is sourced strictly from trusted DB records.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Register a new SME user and organisation.
     * Enforces SME role and organisation type at the database level.
     */
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        log.info("Registering new SME user: email={}", request.email());

        String normalizedEmail = request.email().toLowerCase().trim();
        String hashedPassword = passwordEncoder.encode(request.password());

        try {
            SmeRegistrationProjection result = userRepository.registerSme(
                    normalizedEmail,
                    hashedPassword,
                    request.fullName().trim(),
                    request.organizationName().trim()
            );

            UUID userId = result.getUserId();
            UUID orgId = result.getOrgId();
            String role = result.getRole();

            log.info("SME user registered successfully: userId={} orgId={} role={}", userId, orgId, role);

            String token = jwtService.generateToken(userId, orgId, role);
            return new LoginResponse(token, userId, orgId, role, request.fullName().trim());

        } catch (DataIntegrityViolationException ex) {
            throw new AppException(ErrorCode.USER_ALREADY_EXISTS);
        } catch (Exception ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("USER_ALREADY_EXISTS")) {
                throw new AppException(ErrorCode.USER_ALREADY_EXISTS);
            }
            throw ex;
        }
    }

    /**
     * Authenticate a user and issue a JWT.
     * Uses narrowly scoped auth lookup function to resolve credentials and tenant membership.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = request.email().toLowerCase().trim();

        // Narrowly scoped authentication lookup (returns only the record matching the email)
        AuthUserProjection authUser = userRepository.findAuthUserByEmail(normalizedEmail)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_CREDENTIALS));

        if (Boolean.FALSE.equals(authUser.getIsActive())) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED);
        }

        // Verify password against stored BCrypt hash
        if (!passwordEncoder.matches(request.password(), authUser.getPasswordHash())) {
            log.warn("Failed login attempt for email={}", normalizedEmail);
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        UUID userId = authUser.getUserId();
        UUID orgId = authUser.getOrgId();
        String role = authUser.getRole();

        log.info("User logged in: userId={} orgId={} role={}", userId, orgId, role);

        String token = jwtService.generateToken(userId, orgId, role);
        return new LoginResponse(token, userId, orgId, role, authUser.getFullName());
    }
}
