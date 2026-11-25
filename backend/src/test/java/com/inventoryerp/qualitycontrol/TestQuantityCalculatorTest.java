package com.inventoryerp.qualitycontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.qualitycontrol.application.TestQuantityCalculator;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-S087 §4: per-line test quantity, ceiling at the UoM scale, clamped to [minSampleQty, coveredQty]. */
class TestQuantityCalculatorTest {

    private static BigDecimal qty(String covered, String pct, String min, int scale) {
        return TestQuantityCalculator.testQty(new BigDecimal(covered), pct == null ? null : new BigDecimal(pct),
                min == null ? null : new BigDecimal(min), scale);
    }

    @Test
    @DisplayName("the review example: lines 100 / 200 / 300 at 10 % test 10 / 20 / 30, each independently")
    void perLine() {
        assertThat(qty("100", "10", null, 0)).isEqualByComparingTo("10");
        assertThat(qty("200", "10", null, 0)).isEqualByComparingTo("20");
        assertThat(qty("300", "10", null, 0)).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("fractions round up to the unit's scale")
    void rounding() {
        assertThat(qty("105", "10", null, 0)).isEqualByComparingTo("11");
        assertThat(qty("1.234", "10", null, 2)).isEqualByComparingTo("1");
        assertThat(qty("12.34", "10", null, 2)).isEqualByComparingTo("1.24");
        assertThat(qty("12.34", "10", "0.01", 2)).isEqualByComparingTo("1.24");
        assertThat(qty("12.34", "10", "0.01", 2).scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("minimum sample (default 1) and never more than the covered quantity")
    void clamps() {
        assertThat(qty("5", "1", null, 0)).isEqualByComparingTo("1");
        assertThat(qty("50", "1", "3", 0)).isEqualByComparingTo("3");
        assertThat(qty("2", "10", "5", 0)).isEqualByComparingTo("2");
        assertThat(qty("0.5", "10", null, 0)).isEqualByComparingTo("0.5");
        assertThat(qty("0", "10", null, 0)).isEqualByComparingTo("0");
        assertThat(qty("40", null, null, 0)).isEqualByComparingTo("40");
    }
}
