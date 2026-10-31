package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.VarianceCalculator.Material;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Production variances (ADR-S018); positive is unfavourable. */
class VarianceCalculatorTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("material: price on the actual quantity, usage at the standard price")
    void material() {
        // Planned 100 at 2; used 110 for 242 (2.20 each).
        Material m = VarianceCalculator.material(d("100"), d("110"), d("242"), d("2"));
        assertThat(m.price()).isEqualByComparingTo("22");
        assertThat(m.usage()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("price and usage add up to actual less standard material cost")
    void reconciles() {
        Material m = VarianceCalculator.material(d("100"), d("110"), d("242"), d("2"));
        assertThat(m.price().add(m.usage())).isEqualByComparingTo(d("242").subtract(d("200")));
    }

    @Test
    @DisplayName("a saving is negative; yield and efficiency follow the same sign")
    void signs() {
        assertThat(VarianceCalculator.material(d("100"), d("90"), d("180"), d("2")).usage()).isEqualByComparingTo("-20");
        assertThat(VarianceCalculator.yield(d("100"), d("95"), d("4"))).isEqualByComparingTo("20");
        assertThat(VarianceCalculator.yield(d("100"), d("105"), d("4"))).isEqualByComparingTo("-20");
        assertThat(VarianceCalculator.efficiency(d("3"), d("2"), d("10"))).isEqualByComparingTo("10");
    }
}
