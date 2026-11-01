package com.tcs.invoicefactoring.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standard error response envelope.
 * Internal exception details and stack traces are NEVER included.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        boolean success,
        String errorCode,
        String message,
        List<FieldError> fieldErrors,
        String correlationId,
        Instant timestamp
) {
    public record FieldError(String field, String message) {}

    public static ApiError of(String errorCode, String message, String correlationId) {
        return new ApiError(false, errorCode, message, null, correlationId, Instant.now());
    }

    public static ApiError ofValidation(String message, List<FieldError> fieldErrors, String correlationId) {
        return new ApiError(false, "VALIDATION_ERROR", message, fieldErrors, correlationId, Instant.now());
    }
}
