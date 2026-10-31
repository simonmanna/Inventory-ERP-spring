package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.manufacturing.application.DemandForecastCalculator;
import com.inventoryerp.manufacturing.application.DemandForecastCalculator.Result;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Holt smoothing of weekly sales. */
class DemandForecastCalculatorTest {

    static List<BigDecimal> weeks(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    @DisplayName("flat sales forecast themselves, with no trend and no error")
    void flat() {
        Result r = DemandForecastCalculator.smooth(weeks("100", "100", "100", "100"));
        assertThat(r.level()).isEqualByComparingTo("100");
        assertThat(r.trend()).isEqualByComparingTo("0");
        assertThat(r.meanAbsoluteError()).isEqualByComparingTo("0");
        assertThat(r.confidence()).isEqualTo("high");
        assertThat(DemandForecastCalculator.horizon(r, 14)).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("a rising series ends with a positive trend and forecasts above an average")
    void rising() {
        Result r = DemandForecastCalculator.smooth(weeks("10", "20", "30", "40", "50", "60"));
        assertThat(r.trend().signum()).isPositive();
        assertThat(DemandForecastCalculator.week(r, 1)).isGreaterThan(new BigDecimal("35"));
    }

    @Test
    @DisplayName("a collapsing series never forecasts a negative quantity")
    void neverNegative() {
        Result r = DemandForecastCalculator.smooth(weeks("500", "300", "100", "0", "0", "0"));
        assertThat(DemandForecastCalculator.week(r, 52)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a part week counts pro rata")
    void partWeek() {
        Result r = new Result(new BigDecimal("70"), BigDecimal.ZERO, BigDecimal.ZERO, 8);
        assertThat(DemandForecastCalculator.horizon(r, 10)).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("little history is low confidence, noisy history medium")
    void confidence() {
        assertThat(DemandForecastCalculator.smooth(weeks("5", "0", "0", "6")).confidence()).isEqualTo("low");
        assertThat(DemandForecastCalculator.smooth(weeks("100", "0", "200", "0", "300", "10")).confidence())
                .isEqualTo("medium");
    }

    @Test
    @DisplayName("no history is zero")
    void empty() {
        assertThat(DemandForecastCalculator.horizon(DemandForecastCalculator.smooth(List.of()), 30))
                .isEqualByComparingTo("0");
    }
}
