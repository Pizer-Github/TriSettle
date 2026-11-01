package com.tcs.invoicefactoring.invoice.storage;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;

public class PayloadTooLargeException extends AppException {
    public PayloadTooLargeException(String message) {
        super(ErrorCode.PAYLOAD_TOO_LARGE, message);
    }
}
