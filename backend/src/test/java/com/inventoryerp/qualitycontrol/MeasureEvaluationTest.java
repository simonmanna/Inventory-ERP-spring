package com.inventoryerp.qualitycontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.qualitycontrol.application.MeasureEvaluator;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-S087 §1: inclusive specification limits, one-sided limits, decimals refused not failed. */
class MeasureEvaluationTest {

    private static MeasureEvaluator.Outcome eval(String v, String min, String max, Integer decimals) {
        return MeasureEvaluator.evaluate(new BigDecimal(v), min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max), decimals, "measureValue");
    }

    @Test
    @DisplayName("limits are inclusive")
    void inclusive() {
        assertThat(eval("4.0", "4", "6", 2).passed()).isTrue();
        assertThat(eval("6.00", "4", "6", 2).passed()).isTrue();
        assertThat(eval("3.99", "4", "6", 2).passed()).isFalse();
        assertThat(eval("3.99", "4", "6", 2).reason()).isEqualTo("below minimum 4");
        assertThat(eval("6.01", "4", "6", 2).reason()).isEqualTo("above maximum 6");
    }

    @Test
    @DisplayName("one-sided limits")
    void oneSided() {
        assertThat(eval("1000", "25", null, null).passed()).isTrue();
        assertThat(eval("24.9", "25", null, null).passed()).isFalse();
        assertThat(eval("-5", null, "5", null).passed()).isTrue();
    }

    @Test
    @DisplayName("too many decimals or a missing value is a 400, not a failure")
    void refused() {
        assertThatThrownBy(() -> eval("4.123", "4", "6", 2)).isInstanceOf(ApiException.class)
                .hasMessage("measureValue has more than 2 decimals.");
        assertThat(eval("4.100", "4", "6", 1).passed()).isTrue();
        assertThatThrownBy(() -> MeasureEvaluator.evaluate(null, BigDecimal.ONE, null, null, "measureValue"))
                .isInstanceOf(ApiException.class).hasMessage("measureValue is required.");
    }
}
