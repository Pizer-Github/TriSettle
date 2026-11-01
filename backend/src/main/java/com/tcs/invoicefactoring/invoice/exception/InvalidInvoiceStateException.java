package com.tcs.invoicefactoring.invoice.exception;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;

public class InvalidInvoiceStateException extends AppException {
    public InvalidInvoiceStateException(String message) {
        super(ErrorCode.INVALID_INVOICE_STATE, message);
    }
}
