package com.tcs.invoicefactoring.invoice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcs.invoicefactoring.common.idempotency.IdempotencyService;
import com.tcs.invoicefactoring.invoice.domain.Invoice;
import com.tcs.invoicefactoring.invoice.domain.InvoiceDocument;
import com.tcs.invoicefactoring.invoice.dto.CreateInvoiceRequest;
import com.tcs.invoicefactoring.invoice.dto.InvoiceDocumentDto;
import com.tcs.invoicefactoring.invoice.dto.InvoiceDto;
import com.tcs.invoicefactoring.invoice.exception.DocumentRequiredException;
import com.tcs.invoicefactoring.invoice.exception.DuplicateInvoiceException;
import com.tcs.invoicefactoring.invoice.exception.InvalidInvoiceStateException;
import com.tcs.invoicefactoring.invoice.exception.InvoiceNotFoundException;
import com.tcs.invoicefactoring.invoice.repository.InvoiceDocumentRepository;
import com.tcs.invoicefactoring.invoice.repository.InvoiceRepository;
import com.tcs.invoicefactoring.invoice.storage.StoredDocumentInfo;
import com.tcs.invoicefactoring.invoice.validation.FinancialAmountValidator;
import com.tcs.invoicefactoring.invoice.validation.GstinValidator;
import com.tcs.invoicefactoring.invoice.validation.InvalidFinancialAmountException;
import com.tcs.invoicefactoring.invoice.validation.PanValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional mutation service for Invoice state changes and document attachments.
 * Isolated into a dedicated Spring bean so invocations from {@link InvoiceService}
 * properly pass through the Spring AOP transactional and RLS context proxy.
 */
@Service
public class InvoiceMutationService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceDocumentRepository documentRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public InvoiceMutationService(
            InvoiceRepository invoiceRepository,
            InvoiceDocumentRepository documentRepository,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper
    ) {
        this.invoiceRepository = invoiceRepository;
        this.documentRepository = documentRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InvoiceDto createDraft(UUID smeOrgId, UUID userId, CreateInvoiceRequest request, String endpoint, String idempotencyKey, UUID fencingToken) {
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

    @Transactional
    public InvoiceDocumentDto uploadDocument(UUID smeOrgId, UUID userId, UUID invoiceId, StoredDocumentInfo docInfo, String filename, String endpoint, String idempotencyKey, UUID fencingToken) {
        idempotencyService.verifyFencingTokenAndLock(smeOrgId, userId, endpoint, idempotencyKey, fencingToken);

        Invoice invoice = invoiceRepository.findForUpdate(invoiceId, smeOrgId)
                .orElseThrow(() -> new InvoiceNotFoundException("Invoice [" + invoiceId + "] not found"));

        if (!"DRAFT".equals(invoice.getStatus())) {
            throw new InvalidInvoiceStateException("Cannot attach document: invoice is in status [" + invoice.getStatus() + "], but must be DRAFT");
        }

        int maxVersion = documentRepository.findMaxVersionByInvoiceId(invoiceId);

        // Deactivate previous active document without deleting its historical physical file or row
        // Flush the update immediately so the partial unique index uq_invoice_single_active_doc
        // does not conflict when the new active document is inserted
        documentRepository.findByInvoiceIdAndActiveTrue(invoiceId).ifPresent(activeDoc -> {
            activeDoc.setActive(false);
            documentRepository.saveAndFlush(activeDoc);
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

    @Transactional
    public InvoiceDto submitInvoice(UUID smeOrgId, UUID userId, UUID invoiceId, String endpoint, String idempotencyKey, UUID fencingToken) {
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

    public InvoiceDto toInvoiceDto(Invoice invoice) {
        return new InvoiceDto(
                invoice.getId(),
                invoice.getSmeOrgId(),
                invoice.getBuyerName(),
                invoice.getBuyerGstin(),
                invoice.getBuyerPan(),
                invoice.getInvoiceNumber(),
                invoice.getInvoiceDate(),
                invoice.getDueDate(),
                invoice.getCurrency(),
                invoice.getTaxableAmount().toPlainString(),
                invoice.getTaxAmount().toPlainString(),
                invoice.getTotalAmount().toPlainString(),
                invoice.getStatus(),
                invoice.getRejectionReason(),
                invoice.getSubmittedAt(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt(),
                invoice.getVerifiedConfirmationId(),
                invoice.getLastBuyerResponseId()
        );
    }

    public InvoiceDocumentDto toDocumentDto(InvoiceDocument doc) {
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
            throw new RuntimeException("Serialization failure", e);
        }
    }
}
