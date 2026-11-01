package com.tcs.invoicefactoring.identity.dto;

import java.util.UUID;

/**
 * Login response DTO.
 * Contains the JWT and basic user context.
 * The JWT itself contains userId, orgId, role — no sensitive data.
 */
public record LoginResponse(
        String token,
        UUID userId,
        UUID orgId,
        String role,
        String fullName
) {}
