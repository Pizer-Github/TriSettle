package com.tcs.invoicefactoring.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Application-specific error codes with their default HTTP status.
 * Add new codes here as modules are implemented in later milestones.
 */
public enum ErrorCode {

    // ── Identity / Auth ──────────────────────────────────────────────────────
    INVALID_CREDENTIALS("Invalid credentials", HttpStatus.UNAUTHORIZED),
    ACCOUNT_DISABLED("Account is disabled", HttpStatus.FORBIDDEN),
    ACCOUNT_LOCKED("Account is locked", HttpStatus.FORBIDDEN),
    TOKEN_EXPIRED("Authentication token has expired", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID("Authentication token is invalid", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED("Access denied", HttpStatus.FORBIDDEN),
    USER_NOT_FOUND("User not found", HttpStatus.NOT_FOUND),
    USER_ALREADY_EXISTS("A user with this email already exists", HttpStatus.CONFLICT),
    ORG_NOT_FOUND("Organization not found", HttpStatus.NOT_FOUND),

    // ── Generic ───────────────────────────────────────────────────────────────
    VALIDATION_ERROR("Validation failed", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND("Resource not found", HttpStatus.NOT_FOUND),
    CONFLICT("Conflict", HttpStatus.CONFLICT),
    INTERNAL_ERROR("An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(String defaultMessage, HttpStatus httpStatus) {
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
