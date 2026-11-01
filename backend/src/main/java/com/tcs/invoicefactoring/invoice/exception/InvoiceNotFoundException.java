package com.tcs.invoicefactoring.invoice.exception;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;

public class InvoiceNotFoundException extends AppException {
    public InvoiceNotFoundException(String message) {
        super(ErrorCode.INVOICE_NOT_FOUND, message);
    }
}
