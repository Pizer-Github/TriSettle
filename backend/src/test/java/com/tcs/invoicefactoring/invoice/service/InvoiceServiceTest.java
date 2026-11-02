package com.tcs.invoicefactoring.invoice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tcs.invoicefactoring.common.idempotency.IdempotencyLease;
import com.tcs.invoicefactoring.common.idempotency.IdempotencyService;
import com.tcs.invoicefactoring.invoice.domain.Invoice;
import com.tcs.invoicefactoring.invoice.domain.InvoiceDocument;
import com.tcs.invoicefactoring.invoice.dto.CreateInvoiceRequest;
import com.tcs.invoicefactoring.invoice.dto.InvoiceDocumentDto;
import com.tcs.invoicefactoring.invoice.dto.InvoiceDto;
import com.tcs.invoicefactoring.invoice.exception.*;
import com.tcs.invoicefactoring.invoice.repository.InvoiceDocumentRepository;
import com.tcs.invoicefactoring.invoice.repository.InvoiceRepository;
import com.tcs.invoicefactoring.invoice.storage.DocumentStorageService;
import com.tcs.invoicefactoring.invoice.storage.StoredDocumentInfo;
import com.tcs.invoicefactoring.invoice.validation.InvalidFinancialAmountException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceDocumentRepository documentRepository;

    @Mock
    private DocumentStorageService storageService;

    @Mock
    private IdempotencyService idempotencyService;

    private ObjectMapper objectMapper;
    private InvoiceService service;

    private final UUID smeOrgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        InvoiceMutationService mutationService = new InvoiceMutationService(invoiceRepository, documentRepository, idempotencyService, objectMapper);
        service = new InvoiceService(invoiceRepository, documentRepository, storageService, idempotencyService, mutationService, objectMapper);
    }

    private CreateInvoiceRequest createValidRequest() {
        return new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZW",
                "AAACT2727Q",
                "INV-2026-001",
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                "INR",
                "100000.00",
                "18000.00",
                "118000.00"
        );
    }

    @Test
    void createDraft_validRequest_createsInvoiceAndCompletesIdempotency() {
        CreateInvoiceRequest request = createValidRequest();
        UUID token = UUID.randomUUID();
        when(idempotencyService.acquireLease(any(), any(), any(), any(), any()))
                .thenReturn(IdempotencyLease.acquired(UUID.randomUUID(), token));
        when(invoiceRepository.existsBySmeOrgIdAndBuyerGstinAndInvoiceNumber(any(), any(), any())).thenReturn(false);
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceDto dto = service.createDraft(smeOrgId, userId, request, "key-1");

        assertThat(dto).isNotNull();
        assertThat(dto.invoiceNumber()).isEqualTo("INV-2026-001");
        assertThat(dto.status()).isEqualTo("DRAFT");
        assertThat(dto.totalAmount()).isEqualTo("118000.00");
        verify(idempotencyService).completeSuccess(eq(smeOrgId), eq(userId), any(), eq("key-1"), eq(token), eq(201), any());
    }

    @Test
    void createDraft_dueDateBeforeInvoiceDate_throwsException() {
        CreateInvoiceRequest request = new CreateInvoiceRequest(
                "Tata Motors Limited",
                "27AAACT2727Q1ZW",
                "AAACT2727Q",
                "INV-2026-001",
                LocalDate.now(),
                LocalDate.now().minusDays(1), // due date before invoice date
                "INR",
                "100000.00",
                "18000.00",
                "118000.00"
        );
        when(idempotencyService.acquireLease(any(), any(), any(), any(), any()))
                .thenReturn(IdempotencyLease.acquired(UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> service.createDraft(smeOrgId, userId, request, "key-1"))
                .isInstanceOf(InvalidFinancialAmountException.class)
                .hasMessageContaining("cannot be before invoice date");
    }

    @Test
    void createDraft_duplicateInvoiceNumber_throwsDuplicateInvoiceException() {
        CreateInvoiceRequest request = createValidRequest();
        when(idempotencyService.acquireLease(any(), any(), any(), any(), any()))
                .thenReturn(IdempotencyLease.acquired(UUID.randomUUID(), UUID.randomUUID()));
        when(invoiceRepository.existsBySmeOrgIdAndBuyerGstinAndInvoiceNumber(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.createDraft(smeOrgId, userId, request, "key-1"))
                .isInstanceOf(DuplicateInvoiceException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void uploadDocument_validPdf_attachesDocument() {
        UUID invoiceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        StoredDocumentInfo docInfo = new StoredDocumentInfo(
                Paths.get("storage/doc.pdf"),
                "storage/path/doc.pdf",
                500L,
                "dummy-sha-256-hash",
                "application/pdf"
        );
        when(storageService.storeDocument(any(), any(), any(), any(), any())).thenReturn(docInfo);
        when(documentRepository.existsBySha256Checksum("dummy-sha-256-hash")).thenReturn(false);
        when(idempotencyService.acquireLease(any(), any(), any(), any(), any()))
                .thenReturn(IdempotencyLease.acquired(UUID.randomUUID(), token));

        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setSmeOrgId(smeOrgId);
        invoice.setStatus("DRAFT");
        when(invoiceRepository.findForUpdate(invoiceId, smeOrgId)).thenReturn(Optional.of(invoice));
        when(documentRepository.save(any(InvoiceDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceDocumentDto docDto = service.uploadDocument(smeOrgId, userId, invoiceId, new ByteArrayInputStream(new byte[10]), "inv.pdf", "key-2");

        assertThat(docDto).isNotNull();
        assertThat(docDto.fileName()).isEqualTo("inv.pdf");
        assertThat(docDto.version()).isEqualTo(1);
        assertThat(docDto.active()).isTrue();
    }

    @Test
    void uploadDocument_duplicateGlobalHash_throwsDuplicateDocumentException() {
        UUID invoiceId = UUID.randomUUID();
        StoredDocumentInfo docInfo = new StoredDocumentInfo(
                Paths.get("storage/doc.pdf"),
                "storage/path/doc.pdf",
                500L,
                "existing-hash-123",
                "application/pdf"
        );
        when(storageService.storeDocument(any(), any(), any(), any(), any())).thenReturn(docInfo);
        when(documentRepository.existsBySha256Checksum("existing-hash-123")).thenReturn(true);

        assertThatThrownBy(() -> service.uploadDocument(smeOrgId, userId, invoiceId, new ByteArrayInputStream(new byte[10]), "inv.pdf", "key-2"))
                .isInstanceOf(DuplicateDocumentException.class);
    }

    @Test
    void submitInvoice_withActiveDocument_transitionsToPendingVerification() {
        UUID invoiceId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(idempotencyService.acquireLease(any(), any(), any(), any(), any()))
                .thenReturn(IdempotencyLease.acquired(UUID.randomUUID(), token));

        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setSmeOrgId(smeOrgId);
        invoice.setStatus("DRAFT");
        invoice.setTaxableAmount(new BigDecimal("100000.00"));
        invoice.setTaxAmount(new BigDecimal("18000.00"));
        invoice.setTotalAmount(new BigDecimal("118000.00"));
        when(invoiceRepository.findForUpdate(invoiceId, smeOrgId)).thenReturn(Optional.of(invoice));
        when(documentRepository.findByInvoiceIdAndActiveTrue(invoiceId)).thenReturn(Optional.of(new InvoiceDocument()));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));

        InvoiceDto result = service.submitInvoice(smeOrgId, userId, invoiceId, "key-3");

        assertThat(result.status()).isEqualTo("PENDING_VERIFICATION");
        assertThat(result.submittedAt()).isNotNull();
    }

    @Test
    void submitInvoice_withoutActiveDocument_throwsDocumentRequiredException() {
        UUID invoiceId = UUID.randomUUID();
        when(idempotencyService.acquireLease(any(), any(), any(), any(), any()))
                .thenReturn(IdempotencyLease.acquired(UUID.randomUUID(), UUID.randomUUID()));

        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setSmeOrgId(smeOrgId);
        invoice.setStatus("DRAFT");
        when(invoiceRepository.findForUpdate(invoiceId, smeOrgId)).thenReturn(Optional.of(invoice));
        when(documentRepository.findByInvoiceIdAndActiveTrue(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitInvoice(smeOrgId, userId, invoiceId, "key-3"))
                .isInstanceOf(DocumentRequiredException.class)
                .hasMessageContaining("active invoice document must be uploaded");
    }
}
