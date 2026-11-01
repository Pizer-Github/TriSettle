package com.tcs.invoicefactoring.invoice.validation;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural, state-code, and ISO/IEC 7064 Mod 36, 36 checksum validator for Indian GSTINs.
 * Note: Validates syntactic and mathematical correctness; does not replace authoritative online GSTN portal verification.
 */
public final class GstinValidator {

    private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");
    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int[] WEIGHTS = {1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2};

    private static final Set<String> VALID_STATE_CODES = Set.of(
            "01", "02", "03", "04", "05", "06", "07", "08", "09", "10",
            "11", "12", "13", "14", "15", "16", "17", "18", "19", "20",
            "21", "22", "23", "24", "26", "27", "28", "29", "30", "31",
            "32", "33", "34", "35", "36", "37", "38", "97", "99"
    );

    private GstinValidator() {}

    public static void validate(String gstin) {
        if (gstin == null || gstin.length() != 15) {
            throw new InvalidGstinException("Buyer GSTIN must be exactly 15 characters long");
        }

        String normalized = gstin.trim().toUpperCase();

        if (!GSTIN_PATTERN.matcher(normalized).matches()) {
            throw new InvalidGstinException("Buyer GSTIN format is invalid according to Indian GST standards");
        }

        String stateCode = normalized.substring(0, 2);
        if (!VALID_STATE_CODES.contains(stateCode)) {
            throw new InvalidGstinException("Buyer GSTIN state code [" + stateCode + "] is not a valid Indian state or union territory code");
        }

        char calculatedCheckChar = calculateCheckDigit(normalized.substring(0, 14));
        if (normalized.charAt(14) != calculatedCheckChar) {
            throw new InvalidGstinException("Buyer GSTIN checksum verification failed (expected check digit ["
                    + calculatedCheckChar + "], found [" + normalized.charAt(14) + "])");
        }
    }

    public static char calculateCheckDigit(String first14) {
        if (first14 == null || first14.length() != 14) {
            throw new IllegalArgumentException("First 14 characters required to calculate check digit");
        }
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            char c = first14.charAt(i);
            int charValue = CHARS.indexOf(c);
            if (charValue == -1) {
                throw new IllegalArgumentException("Invalid character in GSTIN: " + c);
            }
            int product = charValue * WEIGHTS[i];
            int quotient = product / 36;
            int remainder = product % 36;
            sum += quotient + remainder;
        }
        int checkDigitValue = (36 - (sum % 36)) % 36;
        return CHARS.charAt(checkDigitValue);
    }
}
