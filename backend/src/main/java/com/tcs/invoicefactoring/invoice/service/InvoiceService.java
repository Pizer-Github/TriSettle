package com.tcs.invoicefactoring.invoice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.common.idempotency.IdempotencyLease;
import com.tcs.invoicefactoring.common.idempotency.IdempotencyService;
import com.tcs.invoicefactoring.invoice.domain.Invoice;
import com.tcs.invoicefactoring.invoice.domain.InvoiceDocument;
import com.tcs.invoicefactoring.invoice.dto.CreateInvoiceRequest;
import com.tcs.invoicefactoring.invoice.dto.InvoiceDetailDto;
import com.tcs.invoicefactoring.invoice.dto.InvoiceDocumentDto;
import com.tcs.invoicefactoring.invoice.dto.InvoiceDto;
import com.tcs.invoicefactoring.invoice.exception.DuplicateDocumentException;
import com.tcs.invoicefactoring.invoice.exception.InvoiceNotFoundException;
import com.tcs.invoicefactoring.invoice.repository.InvoiceDocumentRepository;
import com.tcs.invoicefactoring.invoice.repository.InvoiceRepository;
import com.tcs.invoicefactoring.invoice.storage.DocumentStorageService;
import com.tcs.invoicefactoring.invoice.storage.StoredDocumentInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * High-level orchestration service for invoices and document lifecycle.
 * Delegates transactional business mutations to {@link InvoiceMutationService}
 * so that invocations pass through Spring AOP proxies (@Transactional and TenantRlsAspect).
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceDocumentRepository documentRepository;
    private final DocumentStorageService storageService;
    private final IdempotencyService idempotencyService;
    private final InvoiceMutationService mutationService;
    private final ObjectMapper objectMapper;

    public InvoiceService(
            InvoiceRepository invoiceRepository,
            InvoiceDocumentRepository documentRepository,
            DocumentStorageService storageService,
            IdempotencyService idempotencyService,
            InvoiceMutationService mutationService,
            ObjectMapper objectMapper
    ) {
        this.invoiceRepository = invoiceRepository;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.idempotencyService = idempotencyService;
        this.mutationService = mutationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a draft invoice protected by two-phase fencing idempotency.
     */
    public InvoiceDto createDraft(UUID smeOrgId, UUID userId, CreateInvoiceRequest request, String idempotencyKey) {
        String canonical = String.join(":",
                request.buyerGstin() != null ? request.buyerGstin().trim().toUpperCase() : "",
                request.invoiceNumber() != null ? request.invoiceNumber().trim() : "",
                request.invoiceDate() != null ? request.invoiceDate().toString() : "",
                request.dueDate() != null ? request.dueDate().toString() : "",
                request.taxableAmount() != null ? request.taxableAmount().trim() : "",
                request.taxAmount() != null ? request.taxAmount().trim() : "",
                request.totalAmount() != null ? request.totalAmount().trim() : ""
        );
        String requestHash = computeSha256(canonical);
        String endpoint = "POST /api/v1/invoices";

        IdempotencyLease lease = idempotencyService.acquireLease(smeOrgId, userId, endpoint, idempotencyKey, requestHash);
        if (lease.isCached()) {
            return deserializeInvoiceDto(lease.getCachedResponseBody());
        }

        try {
            return mutationService.createDraft(smeOrgId, userId, request, endpoint, idempotencyKey, lease.getFencingToken());
        } catch (Exception ex) {
            idempotencyService.releaseLease(smeOrgId, userId, endpoint, idempotencyKey, lease.getFencingToken());
            throw ex;
        }
    }

    /**
     * Uploads and attaches a PDF document to an invoice, creating a new active version.
     */
    public InvoiceDocumentDto uploadDocument(UUID smeOrgId, UUID userId, UUID invoiceId, InputStream fileStream, String filename, String idempotencyKey) {
        UUID docUuid = UUID.randomUUID();
        StoredDocumentInfo docInfo = storageService.storeDocument(smeOrgId, invoiceId, docUuid, fileStream, filename);

        if (documentRepository.existsBySha256Checksum(docInfo.sha256Checksum())) {
            storageService.deleteQuietly(docInfo.absolutePath());
            throw new DuplicateDocumentException();
        }

        String canonical = invoiceId + ":" + docInfo.sha256Checksum() + ":" + filename + ":" + docInfo.fileSizeBytes();
        String requestHash = computeSha256(canonical);
        String endpoint = "POST /api/v1/invoices/" + invoiceId + "/documents";

        IdempotencyLease lease = idempotencyService.acquireLease(smeOrgId, userId, endpoint, idempotencyKey, requestHash);
        if (lease.isCached()) {
            return deserializeDocumentDto(lease.getCachedResponseBody());
        }

        try {
            return mutationService.uploadDocument(smeOrgId, userId, invoiceId, docInfo, filename, endpoint, idempotencyKey, lease.getFencingToken());
        } catch (Exception ex) {
            storageService.deleteQuietly(docInfo.absolutePath());
            idempotencyService.releaseLease(smeOrgId, userId, endpoint, idempotencyKey, lease.getFencingToken());
            throw ex;
        }
    }

    /**
     * Submits an invoice for verification (explicit action transitioning DRAFT -> PENDING_VERIFICATION).
     */
    public InvoiceDto submitInvoice(UUID smeOrgId, UUID userId, UUID invoiceId, String idempotencyKey) {
        String canonical = invoiceId + ":SUBMIT";
        String requestHash = computeSha256(canonical);
        String endpoint = "POST /api/v1/invoices/" + invoiceId + "/submit";

        IdempotencyLease lease = idempotencyService.acquireLease(smeOrgId, userId, endpoint, idempotencyKey, requestHash);
        if (lease.isCached()) {
            return deserializeInvoiceDto(lease.getCachedResponseBody());
        }

        try {
            return mutationService.submitInvoice(smeOrgId, userId, invoiceId, endpoint, idempotencyKey, lease.getFencingToken());
        } catch (Exception ex) {
            idempotencyService.releaseLease(smeOrgId, userId, endpoint, idempotencyKey, lease.getFencingToken());
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public InvoiceDetailDto getInvoiceDetail(UUID callerOrgId, String callerRole, UUID invoiceId) {
        Invoice invoice;
        if ("ADMIN".equals(callerRole) || "PLATFORM_OPS".equals(callerRole)) {
            invoice = invoiceRepository.findById(invoiceId)
                    .orElseThrow(() -> new InvoiceNotFoundException("Invoice [" + invoiceId + "] not found"));
        } else {
            invoice = invoiceRepository.findByIdAndSmeOrgId(invoiceId, callerOrgId)
                    .orElseThrow(() -> new InvoiceNotFoundException("Invoice [" + invoiceId + "] not found"));
        }

        List<InvoiceDocument> documents = documentRepository.findByInvoiceIdOrderByVersionDesc(invoiceId);
        InvoiceDocumentDto activeDoc = documents.stream()
                .filter(InvoiceDocument::isActive)
                .findFirst()
                .map(mutationService::toDocumentDto)
                .orElse(null);

        List<InvoiceDocumentDto> history = documents.stream().map(mutationService::toDocumentDto).toList();

        return new InvoiceDetailDto(mutationService.toInvoiceDto(invoice), activeDoc, history);
    }

    @Transactional(readOnly = true)
    public Page<InvoiceDto> listInvoices(UUID smeOrgId, String status, Pageable pageable) {
        Page<Invoice> page;
        if (status != null && !status.isBlank()) {
            page = invoiceRepository.findBySmeOrgIdAndStatus(smeOrgId, status.trim().toUpperCase(), pageable);
        } else {
            page = invoiceRepository.findBySmeOrgId(smeOrgId, pageable);
        }
        return page.map(mutationService::toInvoiceDto);
    }

    @Transactional(readOnly = true)
    public InputStream getDocumentStream(UUID callerOrgId, String callerRole, UUID invoiceId, UUID documentId) {
        InvoiceDocument doc;
        if ("ADMIN".equals(callerRole) || "PLATFORM_OPS".equals(callerRole)) {
            doc = documentRepository.findByIdAndInvoiceId(documentId, invoiceId)
                    .orElseThrow(() -> new InvoiceNotFoundException("Invoice document [" + documentId + "] not found for invoice [" + invoiceId + "]"));
        } else {
            doc = documentRepository.findByIdAndInvoiceIdAndSmeOrgId(documentId, invoiceId, callerOrgId)
                    .orElseThrow(() -> new InvoiceNotFoundException("Invoice document [" + documentId + "] not found for invoice [" + invoiceId + "]"));
        }

        return storageService.getDocumentStream(doc.getStoragePath());
    }

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private InvoiceDto deserializeInvoiceDto(String json) {
        try {
            return objectMapper.readValue(json, InvoiceDto.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Deserialization failure", e);
        }
    }

    private InvoiceDocumentDto deserializeDocumentDto(String json) {
        try {
            return objectMapper.readValue(json, InvoiceDocumentDto.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Deserialization failure", e);
        }
    }
}
