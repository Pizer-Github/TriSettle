package com.tcs.invoicefactoring.identity.controller;

import com.tcs.invoicefactoring.common.api.ApiResponse;
import com.tcs.invoicefactoring.identity.dto.LoginRequest;
import com.tcs.invoicefactoring.identity.dto.LoginResponse;
import com.tcs.invoicefactoring.identity.dto.RegisterRequest;
import com.tcs.invoicefactoring.identity.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication endpoints.
 * Business logic is kept entirely in {@link AuthService}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * POST /api/v1/auth/register
     * Register a new user and organisation.
     * Returns a JWT on success.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        LoginResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Registration successful", response));
    }

    /**
     * POST /api/v1/auth/login
     * Authenticate with email and password. Returns a JWT on success.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok("Login successful", response));
    }
}
