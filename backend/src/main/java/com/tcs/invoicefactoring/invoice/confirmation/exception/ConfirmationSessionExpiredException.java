package com.tcs.invoicefactoring.invoice.confirmation.exception;

public class ConfirmationSessionExpiredException extends BuyerConfirmationException {
    public ConfirmationSessionExpiredException(String message) {
        super(message);
    }
}
