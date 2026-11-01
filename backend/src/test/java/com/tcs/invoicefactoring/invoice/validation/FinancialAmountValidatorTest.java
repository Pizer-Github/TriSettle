package com.tcs.invoicefactoring.invoice.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancialAmountValidatorTest {

    @Test
    void parseAndValidate_validDecimalString_returnsExactBigDecimal() {
        BigDecimal val = FinancialAmountValidator.parseAndValidate("100000.00", "taxableAmount", false);
        assertThat(val).isEqualByComparingTo("100000.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "100.5", "100.555", "-10.00", "abc", ""})
    void parseAndValidate_invalidPrecisionOrFormat_throwsException(String invalidAmount) {
        assertThatThrownBy(() -> FinancialAmountValidator.parseAndValidate(invalidAmount, "testAmount", false))
                .isInstanceOf(InvalidFinancialAmountException.class);
    }

    @Test
    void validateAmountMath_matchingTotal_succeeds() {
        BigDecimal taxable = new BigDecimal("100000.00");
        BigDecimal tax = new BigDecimal("18000.00");
        BigDecimal total = new BigDecimal("118000.00");

        FinancialAmountValidator.validateAmountMath(taxable, tax, total);
    }

    @Test
    void validateAmountMath_mismatchedTotal_throwsException() {
        BigDecimal taxable = new BigDecimal("100000.00");
        BigDecimal tax = new BigDecimal("18000.00");
        BigDecimal wrongTotal = new BigDecimal("119000.00");

        assertThatThrownBy(() -> FinancialAmountValidator.validateAmountMath(taxable, tax, wrongTotal))
                .isInstanceOf(InvalidFinancialAmountException.class)
                .hasMessageContaining("must exactly equal");
    }
}
