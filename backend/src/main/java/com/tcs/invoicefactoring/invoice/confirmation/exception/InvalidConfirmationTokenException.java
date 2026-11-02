package com.tcs.invoicefactoring.invoice.confirmation.exception;

public class InvalidConfirmationTokenException extends BuyerConfirmationException {
    public InvalidConfirmationTokenException(String message) {
        super(message);
    }
}
