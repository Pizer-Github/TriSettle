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
    INTERNAL_ERROR("An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR),

    // ── Idempotency & Concurrency ──────────────────────────────────────────────
    IDEMPOTENCY_CONFLICT("Idempotency conflict", HttpStatus.CONFLICT),
    STALE_IDEMPOTENCY_LEASE("Idempotency lease is stale or was reclaimed", HttpStatus.CONFLICT),

    // ── Invoices & Documents ───────────────────────────────────────────────────
    INVOICE_NOT_FOUND("Invoice not found", HttpStatus.NOT_FOUND),
    DUPLICATE_INVOICE("An invoice with this number already exists for this buyer", HttpStatus.CONFLICT),
    DUPLICATE_DOCUMENT("An invoice document with identical cryptographic content already exists on the network", HttpStatus.CONFLICT),
    INVALID_INVOICE_STATE("Invoice is not in the required state for this operation", HttpStatus.CONFLICT),
    DOCUMENT_REQUIRED("An active document is required before this operation can proceed", HttpStatus.BAD_REQUEST),
    PAYLOAD_TOO_LARGE("File size exceeds the allowed limit", HttpStatus.PAYLOAD_TOO_LARGE),

    // ── Buyer Confirmation ─────────────────────────────────────────────────────
    BUYER_CONFIRMATION_INVALID("Buyer confirmation request or token is invalid", HttpStatus.BAD_REQUEST),
    CONFIRMATION_EXPIRED("Confirmation request or session has expired", HttpStatus.GONE),
    RATE_LIMIT_EXCEEDED("Rate limit exceeded for confirmation attempts", HttpStatus.TOO_MANY_REQUESTS),
    BUYER_NOT_AUTHORIZED("Buyer organization or user is not authorized for this invoice", HttpStatus.FORBIDDEN);

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
