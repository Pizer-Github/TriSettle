package com.tcs.invoicefactoring.invoice.confirmation.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenExchangeRequest(
        @NotBlank(message = "Token is required")
        String token
) {}
