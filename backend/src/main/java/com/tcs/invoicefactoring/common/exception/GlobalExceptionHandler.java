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
