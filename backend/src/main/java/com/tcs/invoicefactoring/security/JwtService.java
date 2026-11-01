package com.tcs.invoicefactoring.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT generation and validation service.
 *
 * Security rules:
 * - Secret loaded from environment variable — never hardcoded.
 * - Token contains: userId, orgId, role. The orgId in the token is used only
 *   as a hint — authorization code ALWAYS re-validates org membership from the DB.
 * - Passwords and sensitive data are NEVER included in token claims.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_ORG_ID  = "orgId";
    private static final String CLAIM_ROLE    = "role";

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms:3600000}") long expirationMs
    ) {
        if (secret == null || secret.length() < 64) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 64 characters. Set the JWT_SECRET environment variable.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * Generate a signed JWT for the authenticated user.
     *
     * @param userId  the user's UUID
     * @param orgId   the user's active organisation UUID
     * @param role    the user's role string (e.g. "SME", "FINANCIER", "ADMIN")
     * @return signed JWT string
     */
    public String generateToken(UUID userId, UUID orgId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_USER_ID, userId.toString())
                .claim(CLAIM_ORG_ID, orgId.toString())
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validate a token and extract its claims.
     *
     * @param token the JWT string (without "Bearer " prefix)
     * @return parsed Claims
     * @throws ExpiredJwtException if the token has expired
     * @throws JwtException        if the token is invalid
     */
    public Claims validateAndExtract(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.get(CLAIM_USER_ID, String.class));
    }

    public UUID extractOrgId(Claims claims) {
        return UUID.fromString(claims.get(CLAIM_ORG_ID, String.class));
    }

    public String extractRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }
}
