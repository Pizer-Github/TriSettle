package com.tcs.invoicefactoring.invoice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateInvoiceRequest(
        @NotBlank(message = "Buyer name is required")
        @Size(max = 255, message = "Buyer name cannot exceed 255 characters")
        String buyerName,

        @NotBlank(message = "Buyer GSTIN is required")
        @Size(min = 15, max = 15, message = "Buyer GSTIN must be exactly 15 characters")
        String buyerGstin,

        @Size(min = 10, max = 10, message = "Buyer PAN must be 10 characters")
        String buyerPan,

        @NotBlank(message = "Invoice number is required")
        @Size(max = 100, message = "Invoice number cannot exceed 100 characters")
        String invoiceNumber,

        @NotNull(message = "Invoice date is required")
        LocalDate invoiceDate,

        @NotNull(message = "Due date is required")
        LocalDate dueDate,

        String currency,

        @NotBlank(message = "Taxable amount is required")
        String taxableAmount,

        @NotBlank(message = "Tax amount is required")
        String taxAmount,

        @NotBlank(message = "Total amount is required")
        String totalAmount
) {}
