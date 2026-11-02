package com.tcs.invoicefactoring.invoice.confirmation.exception;

public class RateLimitExceededException extends BuyerConfirmationException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
