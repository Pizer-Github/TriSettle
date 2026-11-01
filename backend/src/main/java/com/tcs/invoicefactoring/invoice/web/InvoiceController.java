package com.tcs.invoicefactoring.invoice.web;

import com.tcs.invoicefactoring.common.api.ApiResponse;
import com.tcs.invoicefactoring.invoice.dto.CreateInvoiceRequest;
import com.tcs.invoicefactoring.invoice.dto.InvoiceDetailDto;
import com.tcs.invoicefactoring.invoice.dto.InvoiceDocumentDto;
import com.tcs.invoicefactoring.invoice.dto.InvoiceDto;
import com.tcs.invoicefactoring.invoice.service.InvoiceService;
import com.tcs.invoicefactoring.security.TenantContextHolder;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping
    @PreAuthorize("hasRole('SME')")
    public ResponseEntity<ApiResponse<InvoiceDto>> createInvoice(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateInvoiceRequest request
    ) {
        TenantContextHolder.TenantContext context = getAuthenticatedContext();
        InvoiceDto dto = invoiceService.createDraft(context.orgId(), context.userId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(dto));
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasRole('SME')")
    public ResponseEntity<ApiResponse<InvoiceDocumentDto>> uploadDocument(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        TenantContextHolder.TenantContext context = getAuthenticatedContext();
        String key = (idempotencyKey != null && !idempotencyKey.isBlank()) ? idempotencyKey : "upload-" + id + "-" + UUID.randomUUID();
        try (InputStream in = file.getInputStream()) {
            InvoiceDocumentDto docDto = invoiceService.uploadDocument(
                    context.orgId(),
                    context.userId(),
                    id,
                    in,
                    file.getOriginalFilename() != null ? file.getOriginalFilename() : "invoice.pdf",
                    key
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(docDto));
        }
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('SME')")
    public ResponseEntity<ApiResponse<InvoiceDto>> submitInvoice(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        TenantContextHolder.TenantContext context = getAuthenticatedContext();
        InvoiceDto dto = invoiceService.submitInvoice(context.orgId(), context.userId(), id, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @GetMapping
    @PreAuthorize("hasRole('SME')")
    public ResponseEntity<ApiResponse<Page<InvoiceDto>>> listInvoices(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        TenantContextHolder.TenantContext context = getAuthenticatedContext();
        Page<InvoiceDto> page = invoiceService.listInvoices(context.orgId(), status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(page));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SME') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<InvoiceDetailDto>> getInvoice(@PathVariable UUID id) {
        TenantContextHolder.TenantContext context = getAuthenticatedContext();
        InvoiceDetailDto detail = invoiceService.getInvoiceDetail(context.orgId(), id);
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    @GetMapping("/{id}/documents/{documentId}/download")
    @PreAuthorize("hasRole('SME') or hasRole('ADMIN')")
    public ResponseEntity<InputStreamResource> downloadDocument(
            @PathVariable UUID id,
            @PathVariable UUID documentId
    ) {
        TenantContextHolder.TenantContext context = getAuthenticatedContext();
        InputStream stream = invoiceService.getDocumentStream(context.orgId(), id, documentId);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice-" + id + ".pdf\"")
                .body(new InputStreamResource(stream));
    }

    private TenantContextHolder.TenantContext getAuthenticatedContext() {
        TenantContextHolder.TenantContext context = TenantContextHolder.get();
        if (context == null || context.orgId() == null || context.userId() == null) {
            throw new AccessDeniedException("No authenticated tenant context available");
        }
        return context;
    }
}
