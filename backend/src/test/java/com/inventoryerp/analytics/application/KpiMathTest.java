package com.inventoryerp.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KpiMathTest {

    @Test
    void percentAndRateRoundHalfUp() {
        assertThat(KpiMath.pct(new BigDecimal("1"), new BigDecimal("3"))).isEqualByComparingTo("33.33");
        assertThat(KpiMath.pct(new BigDecimal("2"), new BigDecimal("3"))).isEqualByComparingTo("66.67");
        assertThat(KpiMath.rate(new BigDecimal("10"), new BigDecimal("3"))).isEqualByComparingTo("3.3333");
    }

    @Test
    void noDenominatorMeansNoFigure() {
        assertThat(KpiMath.pct(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
        assertThat(KpiMath.pct(BigDecimal.ONE, null)).isNull();
        assertThat(KpiMath.rate(BigDecimal.ONE, BigDecimal.valueOf(-1))).isNull();
    }

    @Test
    void sumReadsStringsAndNumbersAndSkipsMissing() {
        List<Map<String, Object>> rows = List.of(Map.of("v", "1.50"), Map.of("v", 2), Map.of("other", 9));
        assertThat(KpiMath.sum(rows, "v")).isEqualByComparingTo("3.50");
    }
}
