package com.tcs.invoicefactoring.invoice.exception;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;

public class DuplicateInvoiceException extends AppException {
    public DuplicateInvoiceException(String message) {
        super(ErrorCode.DUPLICATE_INVOICE, message);
    }
}
