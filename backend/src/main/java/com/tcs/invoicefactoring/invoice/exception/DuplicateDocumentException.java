package com.tcs.invoicefactoring.invoice.exception;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;

public class DuplicateDocumentException extends AppException {
    public DuplicateDocumentException() {
        super(ErrorCode.DUPLICATE_DOCUMENT, "An invoice document with identical cryptographic content already exists on the network");
    }
}
