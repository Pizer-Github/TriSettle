package com.tcs.invoicefactoring.invoice.confirmation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SubmitConfirmationRequest(
        @NotBlank(message = "Action is required")
        @Pattern(regexp = "^(CONFIRMED|DISPUTED|REJECTED)$", message = "Action must be CONFIRMED, DISPUTED, or REJECTED")
        String action,

        String disputeCategory,

        @Size(max = 2000, message = "Notes cannot exceed 2000 characters")
        String notes,

        String claimedEmail
) {}
