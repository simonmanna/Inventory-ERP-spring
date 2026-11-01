package com.inventoryerp.costing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.costing.application.AbcCalculator.Activity;
import com.inventoryerp.costing.application.AbcCalculator.Assignment;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AbcCalculatorTest {

    static Map<String, BigDecimal> vols(Object... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], new BigDecimal(String.valueOf(kv[i + 1])));
        }
        return m;
    }

    @Test
    void poolIsAssignedByDriverVolumeAndAddsUp() {
        Assignment a = AbcCalculator.assign(new Activity("billing", new BigDecimal("100"), vols("A", 1, "B", 1, "C", 1)));
        assertThat(a.rate()).isEqualByComparingTo("33.333333");
        assertThat(a.costs().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100");
        assertThat(a.unassigned()).isEqualByComparingTo("0");
    }

    @Test
    void largestObjectTakesTheRounding() {
        Assignment a = AbcCalculator.assign(new Activity("x", new BigDecimal("10"), vols("A", 1, "B", 2)));
        assertThat(a.costs().get("A")).isEqualByComparingTo("3.33");
        assertThat(a.costs().get("B")).isEqualByComparingTo("6.67");
    }

    @Test
    void noVolumeLeavesThePoolUnassigned() {
        Assignment a = AbcCalculator.assign(new Activity("x", new BigDecimal("50"), vols("A", 0)));
        assertThat(a.costs()).isEmpty();
        assertThat(a.unassigned()).isEqualByComparingTo("50");
    }

    @Test
    void totalsSumAcrossActivities() {
        Map<String, BigDecimal> t = AbcCalculator.totals(List.of(
                AbcCalculator.assign(new Activity("a", new BigDecimal("30"), vols("A", 1, "B", 2))),
                AbcCalculator.assign(new Activity("b", new BigDecimal("12"), vols("A", 3, "B", 1)))));
        assertThat(t.get("A")).isEqualByComparingTo("19");
        assertThat(t.get("B")).isEqualByComparingTo("23");
    }
}
