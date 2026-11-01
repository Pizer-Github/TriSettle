package com.tcs.invoicefactoring.invoice.exception;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;

public class DocumentRequiredException extends AppException {
    public DocumentRequiredException(String message) {
        super(ErrorCode.DOCUMENT_REQUIRED, message);
    }
}
