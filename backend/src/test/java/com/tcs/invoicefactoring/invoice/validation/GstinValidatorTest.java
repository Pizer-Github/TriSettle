package com.tcs.invoicefactoring.invoice.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GstinValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "27AAACT2727Q1ZW", // Tata Motors (Maharashtra) - Check digit W
            "29AAACI4397Q1ZP", // Infosys (Karnataka) - Check digit P
            "09AAAUP8175A1ZG", // Sample Enterprise (Uttar Pradesh) - Check digit G
            "06AAACL6442L1ZE", // Larsen & Toubro (Haryana) - Check digit E
            "07AABCB1518J1ZY"  // Bharat Petroleum (Delhi) - Check digit Y
    })
    void validate_withIndependentlyVerifiedValidGstin_succeeds(String validGstin) {
        GstinValidator.validate(validGstin);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "27AAACT2727Q1ZX", // Tata Motors with wrong check digit X
            "29AAACI4397Q1Z9", // Infosys with wrong check digit 9
            "00AAACT2727Q1ZW", // State code 00
            "45AAACT2727Q1ZW", // State code 45
            "27AAACT2727Q1AW", // 14th char not Z
            "27AAACT2727Q1Z",  // 14 chars
            "27AAACT2727Q1ZWW" // 16 chars
    })
    void validate_withInvalidGstin_throwsInvalidGstinException(String invalidGstin) {
        assertThatThrownBy(() -> GstinValidator.validate(invalidGstin))
                .isInstanceOf(InvalidGstinException.class);
    }

    @Test
    void validatePanMatchesGstin_matchingPan_succeeds() {
        PanValidator.validatePanMatchesGstin("AAACT2727Q", "27AAACT2727Q1ZW");
    }

    @Test
    void validatePanMatchesGstin_mismatchedPan_throwsException() {
        assertThatThrownBy(() -> PanValidator.validatePanMatchesGstin("BBBBT2727Q", "27AAACT2727Q1ZW"))
                .isInstanceOf(InvalidGstinException.class)
                .hasMessageContaining("does not match");
    }
}
