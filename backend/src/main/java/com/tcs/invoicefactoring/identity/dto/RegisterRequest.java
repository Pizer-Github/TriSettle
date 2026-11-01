package com.tcs.invoicefactoring.identity.dto;

import com.tcs.invoicefactoring.identity.domain.Organization;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * User registration request DTO.
 * Used by the initial user provisioning endpoint.
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

        @NotBlank(message = "Organisation name is required")
        @Size(min = 2, max = 255)
        String organizationName,

        @NotNull(message = "Organisation type is required")
        Organization.OrgType organizationType
) {}
