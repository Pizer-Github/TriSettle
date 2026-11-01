package com.tcs.invoicefactoring.common.idempotency;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;

public class IdempotencyConflictException extends AppException {
    public IdempotencyConflictException(String message) {
        super(ErrorCode.IDEMPOTENCY_CONFLICT, message);
    }
}
