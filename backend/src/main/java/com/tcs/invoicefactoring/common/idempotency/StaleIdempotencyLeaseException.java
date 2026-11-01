package com.tcs.invoicefactoring.common.idempotency;

import com.tcs.invoicefactoring.common.exception.AppException;
import com.tcs.invoicefactoring.common.exception.ErrorCode;

public class StaleIdempotencyLeaseException extends AppException {
    public StaleIdempotencyLeaseException(String message) {
        super(ErrorCode.STALE_IDEMPOTENCY_LEASE, message);
    }
}
