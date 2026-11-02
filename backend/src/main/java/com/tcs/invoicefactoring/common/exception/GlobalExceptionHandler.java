package com.tcs.invoicefactoring.common.exception;

import com.tcs.invoicefactoring.common.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Centralized exception handler.
 * IMPORTANT: Never expose internal exception messages or stack traces in API responses.
 * Log full detail server-side; return sanitized messages to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ── Application exceptions ────────────────────────────────────────────────

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiError> handleAppException(AppException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Application exception [{}]: {} — path={}",
                ex.getErrorCode(), ex.getMessage(), request.getRequestURI());
        ApiError error = ApiError.of(ex.getErrorCode().name(), ex.getMessage(), correlationId);
        return ResponseEntity.status(ex.getHttpStatus()).body(error);
    }

    // ── Buyer Confirmation exceptions ─────────────────────────────────────────

    @ExceptionHandler(com.tcs.invoicefactoring.invoice.confirmation.exception.RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(com.tcs.invoicefactoring.invoice.confirmation.exception.RateLimitExceededException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Rate limit exceeded — path={} correlationId={}", request.getRequestURI(), correlationId);
        ApiError error = ApiError.of(ErrorCode.RATE_LIMIT_EXCEEDED.name(), ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
    }

    @ExceptionHandler(com.tcs.invoicefactoring.invoice.confirmation.exception.ConfirmationSessionExpiredException.class)
    public ResponseEntity<ApiError> handleConfirmationExpired(com.tcs.invoicefactoring.invoice.confirmation.exception.ConfirmationSessionExpiredException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Confirmation expired — path={} correlationId={}", request.getRequestURI(), correlationId);
        ApiError error = ApiError.of(ErrorCode.CONFIRMATION_EXPIRED.name(), ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.GONE).body(error);
    }

    @ExceptionHandler({
            com.tcs.invoicefactoring.invoice.confirmation.exception.ConfirmationLeaseException.class,
            com.tcs.invoicefactoring.invoice.confirmation.exception.FencingGenerationException.class
    })
    public ResponseEntity<ApiError> handleConfirmationConcurrency(RuntimeException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Confirmation concurrency/fencing conflict — path={} correlationId={}", request.getRequestURI(), correlationId);
        ApiError error = ApiError.of(ErrorCode.IDEMPOTENCY_CONFLICT.name(), ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(com.tcs.invoicefactoring.invoice.confirmation.exception.BuyerTaxProfileException.class)
    public ResponseEntity<ApiError> handleBuyerTaxProfile(com.tcs.invoicefactoring.invoice.confirmation.exception.BuyerTaxProfileException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Buyer tax profile unauthorized — path={} correlationId={}", request.getRequestURI(), correlationId);
        ApiError error = ApiError.of(ErrorCode.BUYER_NOT_AUTHORIZED.name(), ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(com.tcs.invoicefactoring.invoice.confirmation.exception.InvalidConfirmationTokenException.class)
    public ResponseEntity<ApiError> handleInvalidToken(com.tcs.invoicefactoring.invoice.confirmation.exception.InvalidConfirmationTokenException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Invalid confirmation token — path={} correlationId={}", request.getRequestURI(), correlationId);
        ApiError error = ApiError.of(ErrorCode.BUYER_CONFIRMATION_INVALID.name(), ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(com.tcs.invoicefactoring.invoice.confirmation.exception.BuyerConfirmationException.class)
    public ResponseEntity<ApiError> handleGenericBuyerConfirmation(com.tcs.invoicefactoring.invoice.confirmation.exception.BuyerConfirmationException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Buyer confirmation error — path={} correlationId={}", request.getRequestURI(), correlationId);
        ApiError error = ApiError.of(ErrorCode.BUYER_CONFIRMATION_INVALID.name(), ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // ── Bean Validation failures ──────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        log.debug("Validation error on path={}: {} field(s) failed", request.getRequestURI(), fieldErrors.size());
        ApiError error = ApiError.ofValidation("Request validation failed", fieldErrors, correlationId);
        return ResponseEntity.badRequest().body(error);
    }

    // ── Spring Security exceptions ────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Access denied — path={} correlationId={}", request.getRequestURI(), correlationId);
        // Return 403 with a generic message — never leak why access was denied
        ApiError error = ApiError.of(ErrorCode.ACCESS_DENIED.name(),
                ErrorCode.ACCESS_DENIED.getDefaultMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Bad credentials attempt — path={}", request.getRequestURI());
        ApiError error = ApiError.of(ErrorCode.INVALID_CREDENTIALS.name(),
                ErrorCode.INVALID_CREDENTIALS.getDefaultMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> handleDisabled(DisabledException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Disabled account attempt — path={}", request.getRequestURI());
        ApiError error = ApiError.of(ErrorCode.ACCOUNT_DISABLED.name(),
                ErrorCode.ACCOUNT_DISABLED.getDefaultMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiError> handleLocked(LockedException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        log.warn("Locked account attempt — path={}", request.getRequestURI());
        ApiError error = ApiError.of(ErrorCode.ACCOUNT_LOCKED.name(),
                ErrorCode.ACCOUNT_LOCKED.getDefaultMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    // ── Data Integrity & Constraint Violations ───────────────────────────────

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        String message = ex.getMessage() != null ? ex.getMessage() : "";

        if (message.contains("uq_global_document_sha256")) {
            log.warn("Global document hash collision detected — path={} correlationId={}", request.getRequestURI(), correlationId);
            ApiError error = ApiError.of(ErrorCode.DUPLICATE_DOCUMENT.name(),
                    ErrorCode.DUPLICATE_DOCUMENT.getDefaultMessage(), correlationId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }

        if (message.contains("uq_sme_buyer_invoice_number")) {
            log.warn("Duplicate invoice number collision — path={} correlationId={}", request.getRequestURI(), correlationId);
            ApiError error = ApiError.of(ErrorCode.DUPLICATE_INVOICE.name(),
                    ErrorCode.DUPLICATE_INVOICE.getDefaultMessage(), correlationId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }

        if (message.contains("chk_invoices_amount_math")) {
            log.warn("Check constraint chk_invoices_amount_math violation — path={} correlationId={}", request.getRequestURI(), correlationId);
            ApiError error = ApiError.of(ErrorCode.VALIDATION_ERROR.name(),
                    "Total amount must exactly equal taxable amount plus tax amount", correlationId);
            return ResponseEntity.badRequest().body(error);
        }

        if (message.contains("chk_invoices_due_date")) {
            log.warn("Check constraint chk_invoices_due_date violation — path={} correlationId={}", request.getRequestURI(), correlationId);
            ApiError error = ApiError.of(ErrorCode.VALIDATION_ERROR.name(),
                    "Due date cannot be before invoice date", correlationId);
            return ResponseEntity.badRequest().body(error);
        }

        log.error("Data integrity violation — path={} correlationId={}", request.getRequestURI(), correlationId, ex);
        ApiError error = ApiError.of(ErrorCode.CONFLICT.name(), "A data integrity constraint was violated", correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    // ── Catch-all ─────────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = MDC.get("correlationId");
        // Log full stack trace server-side
        log.error("Unexpected error — path={} correlationId={}", request.getRequestURI(), correlationId, ex);
        // Return generic message — NEVER expose internal details
        ApiError error = ApiError.of(ErrorCode.INTERNAL_ERROR.name(),
                ErrorCode.INTERNAL_ERROR.getDefaultMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
