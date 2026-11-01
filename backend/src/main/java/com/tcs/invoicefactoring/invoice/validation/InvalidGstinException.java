package com.tcs.invoicefactoring.invoice.validation;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;

public class InvalidGstinException extends AppException {
    public InvalidGstinException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
    }
}
