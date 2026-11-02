package com.tcs.invoicefactoring.invoice.confirmation.web;

import com.tcs.invoicefactoring.common.api.ApiResponse;
import com.tcs.invoicefactoring.invoice.confirmation.dto.BuyerConfirmationRequestDto;
import com.tcs.invoicefactoring.invoice.confirmation.dto.RequestConfirmationDto;
import com.tcs.invoicefactoring.invoice.confirmation.service.BuyerConfirmationMutationService;
import com.tcs.invoicefactoring.security.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sme/invoices/{id}/confirmation-request")
public class SmeBuyerConfirmationController {

    private final BuyerConfirmationMutationService mutationService;

    @Value("${app.frontend.base-url:http://localhost:3000}")
    private String defaultBaseUrl;

    public SmeBuyerConfirmationController(BuyerConfirmationMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @PostMapping
    @PreAuthorize("hasRole('SME')")
    public ResponseEntity<ApiResponse<BuyerConfirmationRequestDto>> requestConfirmation(
            @PathVariable UUID id,
            @Valid @RequestBody RequestConfirmationDto requestDto,
            HttpServletRequest request
    ) {
        TenantContextHolder.TenantContext context = getAuthenticatedContext();
        String baseUrl = resolveBaseUrl(request);
        BuyerConfirmationRequestDto dto = mutationService.requestConfirmation(
                context.orgId(),
                context.userId(),
                id,
                requestDto.recipientEmail(),
                baseUrl
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(dto));
    }

    @PostMapping("/cancel")
    @PreAuthorize("hasRole('SME')")
    public ResponseEntity<ApiResponse<Void>> cancelConfirmation(
            @PathVariable UUID id,
            @RequestParam UUID requestId
    ) {
        TenantContextHolder.TenantContext context = getAuthenticatedContext();
        mutationService.cancelRequest(context.orgId(), context.userId(), requestId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/resend")
    @PreAuthorize("hasRole('SME')")
    public ResponseEntity<ApiResponse<BuyerConfirmationRequestDto>> resendConfirmation(
            @PathVariable UUID id,
            @RequestParam UUID priorRequestId,
            @RequestBody(required = false) RequestConfirmationDto requestDto,
            HttpServletRequest request
    ) {
        TenantContextHolder.TenantContext context = getAuthenticatedContext();
        String baseUrl = resolveBaseUrl(request);
        String recipient = requestDto != null ? requestDto.recipientEmail() : null;
        BuyerConfirmationRequestDto dto = mutationService.resendRequest(
                context.orgId(),
                context.userId(),
                priorRequestId,
                recipient,
                baseUrl
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(dto));
    }

    private TenantContextHolder.TenantContext getAuthenticatedContext() {
        TenantContextHolder.TenantContext context = TenantContextHolder.get();
        if (context == null || context.orgId() == null || context.userId() == null) {
            throw new AccessDeniedException("No authenticated tenant context available");
        }
        return context;
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return origin;
        }
        return defaultBaseUrl;
    }
}
