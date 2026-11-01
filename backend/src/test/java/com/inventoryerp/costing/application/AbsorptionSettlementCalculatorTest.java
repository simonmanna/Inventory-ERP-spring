package com.inventoryerp.costing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.costing.application.AbsorptionSettlementCalculator.Decomposition;
import com.inventoryerp.costing.application.AbsorptionSettlementCalculator.Inputs;
import com.inventoryerp.costing.application.AbsorptionSettlementCalculator.Plan;
import com.inventoryerp.costing.application.AbsorptionSettlementCalculator.Treatment;
import com.inventoryerp.manufacturing.application.VarianceCalculator;
import com.inventoryerp.manufacturing.application.VarianceCalculator.MixLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CM-4 (ADR-S034, AD-11): decomposition by cause and the treatment matrix; the variance split reconciles. */
class AbsorptionSettlementCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static Decomposition dec(String actual, String absorbed, String budget, String normal, String activity,
            String rate) {
        return AbsorptionSettlementCalculator.decompose(new Inputs(d(actual), d(absorbed), d(budget), d(normal),
                d(activity), rate == null ? null : d(rate)));
    }

    @Test
    @DisplayName("spending + volume + rate error = actual − absorbed, exactly (randomised)")
    void decompositionSums() {
        Decomposition x = dec("150", "70", "100", "4", "2", null);
        assertThat(x.volume()).isEqualByComparingTo("50"); // (4 − 2) × 25
        assertThat(x.rateError()).isEqualByComparingTo("0");
        assertThat(x.spending()).isEqualByComparingTo("30");
        Decomposition withRate = dec("150", "70", "100", "4", "2", "20");
        assertThat(withRate.rateError()).isEqualByComparingTo("10"); // (25 − 20) × 2
        assertThat(withRate.spending()).isEqualByComparingTo("20");
        Random r = new Random(7);
        for (int i = 0; i < 400; i++) {
            Decomposition z = dec(String.valueOf(r.nextInt(100000) / 100.0), String.valueOf(r.nextInt(100000) / 100.0),
                    String.valueOf(r.nextInt(50000) / 100.0), String.valueOf(1 + r.nextInt(5000) / 10.0),
                    String.valueOf(r.nextInt(5000) / 10.0), r.nextBoolean() ? null : String.valueOf(r.nextInt(900) / 10.0));
            assertThat(z.spending().add(z.volume()).add(z.rateError())).isEqualByComparingTo(z.total());
        }
        assertThatThrownBy(() -> dec("1", "1", "1", "0", "1", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("low volume is expensed; above tolerance needs a reviewer; abnormal is expensed")
    void underAbsorbed() {
        Decomposition x = dec("150", "70", "100", "4", "2", null);
        Plan blocked = AbsorptionSettlementCalculator.plan(x, d("70"), d("5"), BigDecimal.ZERO, null);
        assertThat(blocked.blocker()).contains("exceeds the tolerance");
        Plan abnormal = AbsorptionSettlementCalculator.plan(x, d("70"), d("5"), BigDecimal.ZERO, "abnormal");
        assertThat(abnormal.lines()).containsExactly(new Treatment("volume", "low_volume", "expense", d("50.00")),
                new Treatment("spending", "abnormal", "expense", d("30.00")));
        Plan normal = AbsorptionSettlementCalculator.plan(x, d("70"), d("5"), BigDecimal.ZERO, "normal");
        assertThat(normal.lines().get(1).treatment()).isEqualTo("capitalise");
        // Within tolerance: capitalised without review.
        Plan within = AbsorptionSettlementCalculator.plan(dec("123", "70", "100", "4", "2", null), d("70"), d("5"),
                BigDecimal.ZERO, null);
        assertThat(within.blocker()).isNull();
        assertThat(within.lines()).extracting(Treatment::treatment).containsExactly("expense", "capitalise");
    }

    @Test
    @DisplayName("high volume and favourable spending reduce the period's output; below materiality → COGS")
    void overAbsorbed() {
        Decomposition x = dec("50", "70", "10", "1", "2", "10");
        assertThat(x.volume()).isEqualByComparingTo("-10");
        assertThat(x.spending()).isEqualByComparingTo("-10");
        Plan p = AbsorptionSettlementCalculator.plan(x, d("70"), d("5"), BigDecimal.ZERO, null);
        assertThat(p.lines()).containsExactly(new Treatment("volume", "high_volume", "reduce_inventory", d("-10.00")),
                new Treatment("spending", "favourable_spending", "reduce_inventory", d("-10.00")));
        Plan small = AbsorptionSettlementCalculator.plan(x, d("70"), d("5"), d("25"), null);
        assertThat(small.lines()).containsExactly(new Treatment("total", "below_materiality", "cogs", d("-20.00")));
        assertThat(AbsorptionSettlementCalculator.plan(dec("70", "70", "10", "1", "1", null), d("70"), d("5"),
                BigDecimal.ZERO, null).lines()).isEmpty();
    }

    @Test
    @DisplayName("variance split: labour rate + efficiency, overhead spending + volume, mix + yield reconcile")
    void varianceSplit() {
        VarianceCalculator.Labour l = VarianceCalculator.labour(d("12"), d("10"), d("150"), d("11"));
        assertThat(l.rate().add(l.efficiency())).isEqualByComparingTo(d("150").subtract(d("110")));
        assertThat(l.efficiency()).isEqualByComparingTo("22");
        VarianceCalculator.Overhead o = VarianceCalculator.fixedOverhead(d("130"), d("120"), d("10"), d("11"));
        assertThat(o.spending().add(o.volume())).isEqualByComparingTo("20");
        List<MixLine> lines = List.of(new MixLine(d("70"), d("60"), d("2")), new MixLine(d("30"), d("40"), d("5")));
        VarianceCalculator.Mix m = VarianceCalculator.mix(lines);
        // usage = 10 × 2 − 10 × 5 = −30; actual total equals standard total, so it is all mix.
        assertThat(m.mix().add(m.yield())).isEqualByComparingTo("-30");
        assertThat(m.yield()).isEqualByComparingTo("0");
    }
}
