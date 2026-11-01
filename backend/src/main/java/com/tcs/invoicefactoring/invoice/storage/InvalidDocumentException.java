package com.tcs.invoicefactoring.invoice.storage;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;

public class InvalidDocumentException extends AppException {
    public InvalidDocumentException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
    }
}
