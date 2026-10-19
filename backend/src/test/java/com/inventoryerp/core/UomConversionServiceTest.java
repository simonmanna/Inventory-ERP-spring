package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.shared.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code uom-conversion.service.spec.ts} (pure math). */
class UomConversionServiceTest {

    @Test
    @DisplayName("convertByFactor: smaller unit up to the reference (18 g → 0.018 kg)")
    void smallerToReference() {
        assertThat(UomConversionService.convertByFactor(18, 0.001, 1).stripTrailingZeros().toPlainString())
                .isEqualTo("0.018");
    }

    @Test
    @DisplayName("convertByFactor: reference down to a smaller unit (2 kg → 2000 g)")
    void referenceToSmaller() {
        assertThat(UomConversionService.convertByFactor(2, 1, 0.001).doubleValue()).isEqualTo(2000);
    }

    @Test
    @DisplayName("convertByFactor: bigger unit to base (1 bag(50 kg) → 50 kg)")
    void biggerToBase() {
        assertThat(UomConversionService.convertByFactor(1, 50, 1).doubleValue()).isEqualTo(50);
    }

    @Test
    @DisplayName("convertByFactor: throws when the target factor is not positive")
    void nonPositiveTarget() {
        assertThatThrownBy(() -> UomConversionService.convertByFactor(1, 1, 0))
                .isInstanceOf(ApiException.class)
                .hasMessage("Target unit factor must be positive");
    }

    @Test
    @DisplayName("roundToStep: rounds to a milli step")
    void milliStep() {
        assertThat(UomConversionService.roundToStep("0.0184", 0.001).stripTrailingZeros().toPlainString())
                .isEqualTo("0.018");
    }

    @Test
    @DisplayName("roundToStep: rounds pieces to whole units (half up)")
    void wholeUnits() {
        assertThat(UomConversionService.roundToStep(2.6, 1).doubleValue()).isEqualTo(3);
        assertThat(UomConversionService.roundToStep(2.4, 1).doubleValue()).isEqualTo(2);
        assertThat(UomConversionService.roundToStep(2.5, 1).doubleValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("roundToStep: no rounding when the step is zero/negative")
    void noRounding() {
        assertThat(UomConversionService.roundToStep("1.23456", 0).toPlainString()).isEqualTo("1.23456");
    }
}
