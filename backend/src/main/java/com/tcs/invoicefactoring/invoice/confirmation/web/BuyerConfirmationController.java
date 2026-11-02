package com.tcs.invoicefactoring.invoice.confirmation.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.common.api.ApiResponse;
import com.tcs.invoicefactoring.invoice.confirmation.domain.BuyerConfirmationSession;
import com.tcs.invoicefactoring.invoice.confirmation.dto.BuyerConfirmationResponseDto;
import com.tcs.invoicefactoring.invoice.confirmation.dto.SessionLeaseResult;
import com.tcs.invoicefactoring.invoice.confirmation.dto.SubmitConfirmationRequest;
import com.tcs.invoicefactoring.invoice.confirmation.dto.TokenExchangeRequest;
import com.tcs.invoicefactoring.invoice.confirmation.dto.TokenExchangeResponseDto;
import com.tcs.invoicefactoring.invoice.confirmation.exception.ConfirmationSessionExpiredException;
import com.tcs.invoicefactoring.invoice.confirmation.repository.BuyerConfirmationSessionRepository;
import com.tcs.invoicefactoring.invoice.confirmation.service.BuyerConfirmationMutationService;
import com.tcs.invoicefactoring.invoice.domain.InvoiceDocument;
import com.tcs.invoicefactoring.invoice.exception.DocumentRequiredException;
import com.tcs.invoicefactoring.invoice.repository.InvoiceDocumentRepository;
import com.tcs.invoicefactoring.invoice.storage.DocumentStorageService;
import com.tcs.invoicefactoring.security.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/buyer/confirmations")
public class BuyerConfirmationController {

    private final BuyerConfirmationMutationService mutationService;
    private final BuyerConfirmationSessionRepository sessionRepository;
    private final InvoiceDocumentRepository documentRepository;
    private final DocumentStorageService storageService;
    private final ObjectMapper objectMapper;

    public BuyerConfirmationController(
            BuyerConfirmationMutationService mutationService,
            BuyerConfirmationSessionRepository sessionRepository,
            InvoiceDocumentRepository documentRepository,
            DocumentStorageService storageService,
            ObjectMapper objectMapper
    ) {
        this.mutationService = mutationService;
        this.sessionRepository = sessionRepository;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/exchange")
    public ResponseEntity<ApiResponse<TokenExchangeResponseDto>> exchangeToken(
            @Valid @RequestBody TokenExchangeRequest request,
            HttpServletRequest httpServletRequest
    ) {
        String clientIp = resolveClientIp(httpServletRequest);
        String userAgent = httpServletRequest.getHeader("User-Agent");

        TenantContextHolder.TenantContext context = TenantContextHolder.get();
        String authChannel;
        UUID portalUserId = null;
        UUID buyerOrgId = null;

        if (context != null && "BUYER".equals(context.role())) {
            authChannel = "PORTAL_USER";
            portalUserId = context.userId();
            buyerOrgId = context.orgId();
        } else {
            authChannel = "TOKEN_LINK";
        }

        TokenExchangeResponseDto responseDto = mutationService.exchangeToken(
                request.token(),
                clientIp,
                userAgent,
                authChannel,
                portalUserId,
                buyerOrgId
        );
        return ResponseEntity.ok(ApiResponse.ok(responseDto));
    }

    @GetMapping("/document")
    public ResponseEntity<InputStreamResource> downloadInvoiceDocument(
            @RequestHeader("X-Confirmation-Session") String sessionToken
    ) {
        String sessionTokenHash = computeSha256(sessionToken);
        BuyerConfirmationSession session = sessionRepository.findBySessionTokenHash(sessionTokenHash)
                .orElseThrow(() -> new ConfirmationSessionExpiredException("Confirmation session not found"));

        if (!"ACTIVE".equals(session.getStatus()) && !"CONSUMED".equals(session.getStatus())) {
            throw new ConfirmationSessionExpiredException("Confirmation session is no longer active");
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw new ConfirmationSessionExpiredException("Confirmation session has expired");
        }

        InvoiceDocument activeDoc = documentRepository.findByInvoiceIdAndActiveTrue(session.getInvoiceId())
                .orElseThrow(() -> new DocumentRequiredException("No active document found for invoice"));

        InputStream stream = storageService.getDocumentStream(activeDoc.getStoragePath());

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + activeDoc.getFileName() + "\"")
                .body(new InputStreamResource(stream));
    }

    @PostMapping("/respond")
    public ResponseEntity<ApiResponse<BuyerConfirmationResponseDto>> respondToConfirmation(
            @RequestHeader("X-Confirmation-Session") String sessionToken,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitConfirmationRequest request,
            HttpServletRequest httpServletRequest
    ) {
        String clientIp = resolveClientIp(httpServletRequest);
        String userAgent = httpServletRequest.getHeader("User-Agent");

        TenantContextHolder.TenantContext context = TenantContextHolder.get();
        String authChannel;
        UUID portalUserId = null;
        UUID buyerOrgId = null;

        if (context != null && "BUYER".equals(context.role())) {
            authChannel = "PORTAL_USER";
            portalUserId = context.userId();
            buyerOrgId = context.orgId();
        } else {
            authChannel = "TOKEN_LINK";
        }

        String requestHash = computeSha256(serialize(request));

        // Step 1: Acquire lease on session (with idempotency and replay handling)
        SessionLeaseResult leaseResult = mutationService.acquireSessionLease(sessionToken, idempotencyKey, requestHash, 30);
        if (leaseResult.replay()) {
            BuyerConfirmationResponseDto cachedDto = deserializeResponseDto(leaseResult.cachedBody());
            return ResponseEntity.ok(ApiResponse.ok(cachedDto));
        }

        // Step 2: Submit response atomically
        BuyerConfirmationResponseDto responseDto = mutationService.respondToConfirmation(
                sessionToken,
                leaseResult.fencingGeneration(),
                idempotencyKey,
                request,
                clientIp,
                userAgent,
                authChannel,
                portalUserId,
                buyerOrgId
        );

        return ResponseEntity.ok(ApiResponse.ok(responseDto));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Serialization failure", e);
        }
    }

    private BuyerConfirmationResponseDto deserializeResponseDto(String json) {
        try {
            return objectMapper.readValue(json, BuyerConfirmationResponseDto.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Deserialization failure", e);
        }
    }
}
