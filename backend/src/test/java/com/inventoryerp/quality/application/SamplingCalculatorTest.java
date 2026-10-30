package com.inventoryerp.quality.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.quality.application.SamplingCalculator.Sampling;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Sample size from the plan's sampling method (ADR-S013). */
class SamplingCalculatorTest {

    static int n(String method, Integer size, String pct, String per, Integer min, Integer max, String qty) {
        return SamplingCalculator.sampleSize(new Sampling(method, size, pct == null ? null : new BigDecimal(pct),
                per == null ? null : new BigDecimal(per), min, max), new BigDecimal(qty));
    }

    @Test
    @DisplayName("fixed, percent, sqrt (+1), per quantity, clamped to min / max and at least 1")
    void methods() {
        assertThat(n("fixed", 3, null, null, 1, null, "1000")).isEqualTo(3);
        assertThat(n("fixed", null, null, null, null, null, "1000")).isEqualTo(1);
        assertThat(n("percent", null, "0.5", null, 1, null, "1000")).isEqualTo(5);
        assertThat(n("percent", null, "0.5", null, 1, null, "1001")).isEqualTo(6);
        assertThat(n("sqrt", null, null, null, 1, null, "100")).isEqualTo(11);
        assertThat(n("sqrt", null, null, null, 1, null, "30")).isEqualTo(7);
        assertThat(n("sqrt", null, null, null, 1, 5, "900")).isEqualTo(5);
        assertThat(n("per_quantity", null, null, "500", 1, null, "1200")).isEqualTo(3);
        assertThat(n("per_quantity", null, null, "500", 2, null, "100")).isEqualTo(2);
        assertThat(n("per_quantity", null, null, "1000", 3, 10, "50000")).isEqualTo(10);
        assertThat(n("percent", null, "1", null, null, null, "0.5")).isEqualTo(1);
        assertThat(n(null, 4, null, null, null, null, "1")).isEqualTo(4);
    }
}
