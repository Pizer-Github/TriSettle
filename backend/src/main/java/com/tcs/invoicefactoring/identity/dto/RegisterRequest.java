package com.tcs.invoicefactoring.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public SME user and organization registration request.
 *
 * Security rules:
 * - Public self-service registration is strictly restricted to SMEs.
 * - Neither role nor organizationType is accepted from the client.
 * - FINANCIER, BUYER, and PLATFORM organizations must be provisioned via
 *   controlled, authenticated administrative onboarding workflows.
 */
public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 10, max = 128, message = "Password must be between 10 and 128 characters")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(min = 2, max = 255, message = "Full name must be between 2 and 255 characters")
        String fullName,

        @NotBlank(message = "Company name is required")
        @Size(min = 2, max = 255, message = "Company name must be between 2 and 255 characters")
        String organizationName
) {}
