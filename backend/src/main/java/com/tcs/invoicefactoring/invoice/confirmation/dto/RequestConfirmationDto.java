package com.tcs.invoicefactoring.invoice.confirmation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RequestConfirmationDto(
        @NotBlank(message = "Recipient email is required")
        @Email(message = "Recipient email must be a valid email address")
        String recipientEmail
) {}
