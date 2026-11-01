package com.tcs.invoicefactoring.security;

import com.tcs.invoicefactoring.identity.domain.User;
import com.tcs.invoicefactoring.identity.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT authentication filter.
 *
 * Security rules enforced:
 * 1. Extract and validate JWT from Authorization header.
 * 2. Load the user from the DB (not from JWT claims alone) to verify they still exist
 *    and are active — this prevents stale tokens from authenticating deleted users.
 * 3. Establish TenantContextHolder from TRUSTED DB data.
 * 4. Always clear TenantContextHolder in the finally block.
 * 5. NEVER log the Authorization header value or JWT token string.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                filterChain.doFilter(request, response);
                return;
            }

            // NOTE: Do NOT log the token value
            String token = authHeader.substring(BEARER_PREFIX.length());
            Claims claims;

            try {
                claims = jwtService.validateAndExtract(token);
            } catch (ExpiredJwtException ex) {
                log.debug("JWT token expired for request: {}", request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            } catch (JwtException ex) {
                log.warn("Invalid JWT token on path={}", request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            UUID userId = jwtService.extractUserId(claims);

            // If already authenticated in this request, skip
            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                filterChain.doFilter(request, response);
                return;
            }

            // Verify user exists and is active in the DB — do NOT trust JWT claims alone
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty() || !userOpt.get().isEnabled()) {
                log.warn("JWT references unknown or disabled user={} path={}",
                        userId, request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            User user = userOpt.get();
            UUID orgId = jwtService.extractOrgId(claims);
            String role = jwtService.extractRole(claims);

            // Add MDC context for structured logging (userId, orgId only — no PII beyond IDs)
            MDC.put("userId", userId.toString());
            MDC.put("orgId", orgId.toString());

            // Establish thread-local tenant context from trusted DB data
            TenantContextHolder.set(userId, orgId, role);

            // Create Spring Security authentication token
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } finally {
            // Always clear tenant context to prevent thread-local leakage
            TenantContextHolder.clear();
        }

        filterChain.doFilter(request, response);
    }
}
