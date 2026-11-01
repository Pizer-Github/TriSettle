package com.tcs.invoicefactoring.invoice.dto;

import java.util.List;

public record InvoiceDetailDto(
        InvoiceDto invoice,
        InvoiceDocumentDto activeDocument,
        List<InvoiceDocumentDto> documentHistory
) {}
