package com.tcs.invoicefactoring.security;

import com.tcs.invoicefactoring.identity.domain.User;
import com.tcs.invoicefactoring.identity.repository.UserRepository;
import io.jsonwebtoken.Claims;
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
 * Security rules:
 * 1. Extract and validate JWT from Authorization header.
 * 2. Load the user from the DB to verify they still exist and are active.
 * 3. Establish TenantContextHolder for the duration of the HTTP request.
 * 4. Ensure TenantContextHolder and MDC are ALWAYS cleared in the finally block
 *    after downstream request processing (including exceptions), preventing thread-leakage.
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
            if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
                String token = authHeader.substring(BEARER_PREFIX.length());

                try {
                    Claims claims = jwtService.validateAndExtract(token);
                    UUID userId = jwtService.extractUserId(claims);

                    if (SecurityContextHolder.getContext().getAuthentication() == null) {
                        Optional<User> userOpt = userRepository.findById(userId);
                        if (userOpt.isPresent() && userOpt.get().isEnabled()) {
                            User user = userOpt.get();
                            UUID orgId = jwtService.extractOrgId(claims);
                            String role = jwtService.extractRole(claims);

                            MDC.put("userId", userId.toString());
                            MDC.put("orgId", orgId.toString());

                            // Establish thread-local tenant context for this request
                            TenantContextHolder.set(userId, orgId, role);

                            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                            var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        } else {
                            log.warn("JWT references unknown or disabled user={} path={}", userId, request.getRequestURI());
                        }
                    }
                } catch (JwtException ex) {
                    log.debug("JWT validation skipped or failed: {}", ex.getMessage());
                }
            }

            // Downstream filter-chain executes INSIDE the try block,
            // ensuring TenantContextHolder remains active for the entire request
            filterChain.doFilter(request, response);

        } finally {
            // Guaranteed cleanup on all paths, including unhandled exceptions,
            // preventing tenant context leakage on pooled servlet threads
            TenantContextHolder.clear();
            MDC.remove("userId");
            MDC.remove("orgId");
        }
    }
}
