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
import com.tcs.invoicefactoring.invoice.exception.*;
import com.tcs.invoicefactoring.invoice.repository.InvoiceDocumentRepository;
import com.tcs.invoicefactoring.invoice.repository.InvoiceRepository;
import com.tcs.invoicefactoring.invoice.storage.DocumentStorageService;
import com.tcs.invoicefactoring.invoice.storage.StoredDocumentInfo;
import com.tcs.invoicefactoring.invoice.validation.FinancialAmountValidator;
import com.tcs.invoicefactoring.invoice.validation.GstinValidator;
import com.tcs.invoicefactoring.invoice.validation.InvalidFinancialAmountException;
import com.tcs.invoicefactoring.invoice.validation.PanValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceDocumentRepository documentRepository;
    private final DocumentStorageService storageService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public InvoiceService(
            InvoiceRepository invoiceRepository,
            InvoiceDocumentRepository documentRepository,
            DocumentStorageService storageService,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper
    ) {
        this.invoiceRepository = invoiceRepository;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.idempotencyService = idempotencyService;
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
            return createDraftInternal(smeOrgId, userId, request, endpoint, idempotencyKey, lease.getFencingToken());
        } catch (Exception ex) {
            idempotencyService.releaseLease(smeOrgId, userId, endpoint, idempotencyKey, lease.getFencingToken());
            throw ex;
        }
    }

    @Transactional
    public InvoiceDto createDraftInternal(UUID smeOrgId, UUID userId, CreateInvoiceRequest request, String endpoint, String idempotencyKey, UUID fencingToken) {
        idempotencyService.verifyFencingTokenAndLock(smeOrgId, userId, endpoint, idempotencyKey, fencingToken);

        String normalizedGstin = request.buyerGstin().trim().toUpperCase();
        String normalizedInvoiceNumber = request.invoiceNumber().trim();

        GstinValidator.validate(normalizedGstin);
        PanValidator.validatePanMatchesGstin(request.buyerPan(), normalizedGstin);

        if (request.dueDate().isBefore(request.invoiceDate())) {
            throw new InvalidFinancialAmountException("Due date [" + request.dueDate() + "] cannot be before invoice date [" + request.invoiceDate() + "]");
        }

        BigDecimal taxable = FinancialAmountValidator.parseAndValidate(request.taxableAmount(), "taxableAmount", false);
        BigDecimal tax = FinancialAmountValidator.parseAndValidate(request.taxAmount(), "taxAmount", true);
        BigDecimal total = FinancialAmountValidator.parseAndValidate(request.totalAmount(), "totalAmount", false);
        FinancialAmountValidator.validateAmountMath(taxable, tax, total);

        if (invoiceRepository.existsBySmeOrgIdAndBuyerGstinAndInvoiceNumber(smeOrgId, normalizedGstin, normalizedInvoiceNumber)) {
            throw new DuplicateInvoiceException("An invoice with number [" + normalizedInvoiceNumber + "] already exists for buyer GSTIN [" + normalizedGstin + "]");
        }

        Invoice invoice = new Invoice();
        invoice.setSmeOrgId(smeOrgId);
        invoice.setCreatedBy(userId);
        invoice.setBuyerName(request.buyerName().trim());
        invoice.setBuyerGstin(normalizedGstin);
        invoice.setBuyerPan(request.buyerPan() != null && !request.buyerPan().isBlank() ? request.buyerPan().trim().toUpperCase() : null);
        invoice.setInvoiceNumber(normalizedInvoiceNumber);
        invoice.setInvoiceDate(request.invoiceDate());
        invoice.setDueDate(request.dueDate());
        invoice.setCurrency("INR");
        invoice.setTaxableAmount(taxable);
        invoice.setTaxAmount(tax);
        invoice.setTotalAmount(total);
        invoice.setStatus("DRAFT");

        invoice = invoiceRepository.save(invoice);
        InvoiceDto dto = toInvoiceDto(invoice);

        String serialized = serialize(dto);
        idempotencyService.completeSuccess(smeOrgId, userId, endpoint, idempotencyKey, fencingToken, 201, serialized);

        return dto;
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
            return uploadDocumentInternal(smeOrgId, userId, invoiceId, docInfo, filename, endpoint, idempotencyKey, lease.getFencingToken());
        } catch (Exception ex) {
            idempotencyService.releaseLease(smeOrgId, userId, endpoint, idempotencyKey, lease.getFencingToken());
            throw ex;
        }
    }

    @Transactional
    public InvoiceDocumentDto uploadDocumentInternal(UUID smeOrgId, UUID userId, UUID invoiceId, StoredDocumentInfo docInfo, String filename, String endpoint, String idempotencyKey, UUID fencingToken) {
        idempotencyService.verifyFencingTokenAndLock(smeOrgId, userId, endpoint, idempotencyKey, fencingToken);

        Invoice invoice = invoiceRepository.findForUpdate(invoiceId, smeOrgId)
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice [" + invoiceId + "] not found"));

        if (!"DRAFT".equals(invoice.getStatus())) {
            throw new InvalidInvoiceStateException("Cannot attach document: invoice is in status [" + invoice.getStatus() + "], but must be DRAFT");
        }

        int maxVersion = documentRepository.findMaxVersionByInvoiceId(invoiceId);

        // Deactivate previous active document without deleting its historical physical file or row
        documentRepository.findByInvoiceIdAndActiveTrue(invoiceId).ifPresent(activeDoc -> {
            activeDoc.setActive(false);
            documentRepository.save(activeDoc);
        });

        InvoiceDocument newDoc = new InvoiceDocument();
        newDoc.setInvoiceId(invoiceId);
        newDoc.setSmeOrgId(smeOrgId);
        newDoc.setUploadedBy(userId);
        newDoc.setFileName(filename);
        newDoc.setFileSizeBytes(docInfo.fileSizeBytes());
        newDoc.setMimeType(docInfo.mimeType());
        newDoc.setStoragePath(docInfo.relativeStoragePath());
        newDoc.setSha256Checksum(docInfo.sha256Checksum());
        newDoc.setVersion(maxVersion + 1);
        newDoc.setActive(true);

        newDoc = documentRepository.save(newDoc);
        InvoiceDocumentDto dto = toDocumentDto(newDoc);

        String serialized = serialize(dto);
        idempotencyService.completeSuccess(smeOrgId, userId, endpoint, idempotencyKey, fencingToken, 201, serialized);

        return dto;
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
            return submitInvoiceInternal(smeOrgId, userId, invoiceId, endpoint, idempotencyKey, lease.getFencingToken());
        } catch (Exception ex) {
            idempotencyService.releaseLease(smeOrgId, userId, endpoint, idempotencyKey, lease.getFencingToken());
            throw ex;
        }
    }

    @Transactional
    public InvoiceDto submitInvoiceInternal(UUID smeOrgId, UUID userId, UUID invoiceId, String endpoint, String idempotencyKey, UUID fencingToken) {
        idempotencyService.verifyFencingTokenAndLock(smeOrgId, userId, endpoint, idempotencyKey, fencingToken);

        Invoice invoice = invoiceRepository.findForUpdate(invoiceId, smeOrgId)
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice [" + invoiceId + "] not found"));

        if (!"DRAFT".equals(invoice.getStatus())) {
            throw new InvalidInvoiceStateException("Cannot submit invoice: invoice is in status [" + invoice.getStatus() + "], but must be DRAFT");
        }

        Optional<InvoiceDocument> activeDoc = documentRepository.findByInvoiceIdAndActiveTrue(invoiceId);
        if (activeDoc.isEmpty()) {
            throw new DocumentRequiredException("An active invoice document must be uploaded before submitting for verification");
        }

        invoice.setStatus("PENDING_VERIFICATION");
        invoice.setSubmittedAt(Instant.now());
        invoice.setUpdatedAt(Instant.now());

        invoice = invoiceRepository.save(invoice);
        InvoiceDto dto = toInvoiceDto(invoice);

        String serialized = serialize(dto);
        idempotencyService.completeSuccess(smeOrgId, userId, endpoint, idempotencyKey, fencingToken, 200, serialized);

        return dto;
    }

    @Transactional(readOnly = true)
    public InvoiceDetailDto getInvoiceDetail(UUID smeOrgId, UUID invoiceId) {
        Invoice invoice = invoiceRepository.findByIdAndSmeOrgId(invoiceId, smeOrgId)
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice [" + invoiceId + "] not found"));

        List<InvoiceDocument> documents = documentRepository.findByInvoiceIdOrderByVersionDesc(invoiceId);
        InvoiceDocumentDto activeDoc = documents.stream()
                .filter(InvoiceDocument::isActive)
                .findFirst()
                .map(this::toDocumentDto)
                .orElse(null);

        List<InvoiceDocumentDto> history = documents.stream().map(this::toDocumentDto).toList();

        return new InvoiceDetailDto(toInvoiceDto(invoice), activeDoc, history);
    }

    @Transactional(readOnly = true)
    public Page<InvoiceDto> listInvoices(UUID smeOrgId, String status, Pageable pageable) {
        Page<Invoice> page;
        if (status != null && !status.isBlank()) {
            page = invoiceRepository.findBySmeOrgIdAndStatus(smeOrgId, status.trim().toUpperCase(), pageable);
        } else {
            page = invoiceRepository.findBySmeOrgId(smeOrgId, pageable);
        }
        return page.map(this::toInvoiceDto);
    }

    @Transactional(readOnly = true)
    public InputStream getDocumentStream(UUID smeOrgId, UUID invoiceId, UUID documentId) {
        InvoiceDocument doc = documentRepository.findByIdAndInvoiceIdAndSmeOrgId(documentId, invoiceId, smeOrgId)
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice document [" + documentId + "] not found for invoice [" + invoiceId + "]"));

        return storageService.getDocumentStream(doc.getStoragePath());
    }

    private InvoiceDto toInvoiceDto(Invoice inv) {
        return new InvoiceDto(
                inv.getId(),
                inv.getSmeOrgId(),
                inv.getBuyerName(),
                inv.getBuyerGstin(),
                inv.getBuyerPan(),
                inv.getInvoiceNumber(),
                inv.getInvoiceDate(),
                inv.getDueDate(),
                inv.getCurrency(),
                inv.getTaxableAmount().toPlainString(),
                inv.getTaxAmount().toPlainString(),
                inv.getTotalAmount().toPlainString(),
                inv.getStatus(),
                inv.getRejectionReason(),
                inv.getSubmittedAt(),
                inv.getCreatedAt(),
                inv.getUpdatedAt()
        );
    }

    private InvoiceDocumentDto toDocumentDto(InvoiceDocument doc) {
        return new InvoiceDocumentDto(
                doc.getId(),
                doc.getInvoiceId(),
                doc.getVersion(),
                doc.isActive(),
                doc.getFileName(),
                doc.getFileSizeBytes(),
                doc.getMimeType(),
                doc.getSha256Checksum(),
                doc.getUploadedAt()
        );
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize response DTO", e);
        }
    }

    private InvoiceDto deserializeInvoiceDto(String json) {
        try {
            return objectMapper.readValue(json, InvoiceDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached invoice DTO", e);
        }
    }

    private InvoiceDocumentDto deserializeDocumentDto(String json) {
        try {
            return objectMapper.readValue(json, InvoiceDocumentDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached document DTO", e);
        }
    }

    private String computeSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
