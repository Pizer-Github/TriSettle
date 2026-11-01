package com.tcs.invoicefactoring.invoice.validation;

import java.util.regex.Pattern;

public final class PanValidator {

    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]{1}$");

    private PanValidator() {}

    public static void validatePanMatchesGstin(String pan, String gstin) {
        if (pan == null || pan.isBlank()) {
            return;
        }

        String normalizedPan = pan.trim().toUpperCase();
        if (!PAN_PATTERN.matcher(normalizedPan).matches()) {
            throw new InvalidGstinException("Buyer PAN format is invalid (must be 5 letters, 4 digits, 1 letter)");
        }

        if (gstin != null && gstin.length() >= 12) {
            String embeddedPan = gstin.substring(2, 12).toUpperCase();
            if (!normalizedPan.equals(embeddedPan)) {
                throw new InvalidGstinException("Provided PAN [" + normalizedPan + "] does not match the PAN embedded in Buyer GSTIN [" + embeddedPan + "]");
            }
        }
    }
}
