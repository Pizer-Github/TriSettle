package com.tcs.invoicefactoring.invoice.validation;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Validates financial amounts represented as decimal strings with exactly two decimal places.
 * Prohibits unsupported scale and silent rounding.
 */
public final class FinancialAmountValidator {

    private static final Pattern DECIMAL_STRING_PATTERN = Pattern.compile("^[0-9]+(\\.[0-9]{2})$");

    private FinancialAmountValidator() {}

    public static BigDecimal parseAndValidate(String amountStr, String fieldName, boolean allowZero) {
        if (amountStr == null || amountStr.isBlank()) {
            throw new InvalidFinancialAmountException(fieldName + " is required");
        }

        String trimmed = amountStr.trim();
        if (!DECIMAL_STRING_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidFinancialAmountException(fieldName + " [" + amountStr + "] has invalid decimal precision. Exact two decimal places required (e.g. '1000.00')");
        }

        BigDecimal value = new BigDecimal(trimmed);
        if (allowZero) {
            if (value.compareTo(BigDecimal.ZERO) < 0) {
                throw new InvalidFinancialAmountException(fieldName + " cannot be negative");
            }
        } else {
            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidFinancialAmountException(fieldName + " must be strictly greater than zero");
            }
        }

        return value;
    }

    public static void validateAmountMath(BigDecimal taxableAmount, BigDecimal taxAmount, BigDecimal totalAmount) {
        BigDecimal expectedTotal = taxableAmount.add(taxAmount);
        if (totalAmount.compareTo(expectedTotal) != 0) {
            throw new InvalidFinancialAmountException("Total amount [" + totalAmount + "] must exactly equal taxable amount [" + taxableAmount + "] + tax amount [" + taxAmount + "] (expected [" + expectedTotal + "])");
        }
    }
}
