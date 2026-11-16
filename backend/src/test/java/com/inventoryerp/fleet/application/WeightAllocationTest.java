package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.fleet.application.WeightAllocation.Line;
import com.inventoryerp.fleet.application.WeightAllocation.Result;
import com.inventoryerp.fleet.application.WeightAllocation.Share;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Phase A A2 (plan §7): proportional split, ceilings with redistribution, largest remainder, unallocated rest. */
class WeightAllocationTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static Line line(String id, int n, String eligible, String cap, String kgPerUnit) {
        return new Line(id, n, d(eligible), d(cap), kgPerUnit == null ? null : d(kgPerUnit));
    }

    static Share share(Result r, String id) {
        return r.shares().stream().filter(s -> s.lineId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void singleProductTakesTheWholeVarianceInItsUnit() {
        Result r = WeightAllocation.propose(d("1000"), List.of(line("a", 1, "20", "20", "1000")));
        assertThat(share(r, "a").qty()).isEqualByComparingTo("1");
        assertThat(r.unallocatedKg()).isEqualByComparingTo("0");
    }

    @Test
    void singleProductIsCappedAndTheRestIsUnallocated() {
        Result r = WeightAllocation.propose(d("19000"), List.of(line("a", 1, "17", "17", "1000")));
        assertThat(share(r, "a").qty()).isEqualByComparingTo("17");
        assertThat(share(r, "a").capped()).isTrue();
        assertThat(r.unallocatedKg()).isEqualByComparingTo("2000");
    }

    @Test
    void threeProductsInDifferentUnitsSplitByWeight() {
        // 10 t of aggregate (1000 kg/t), 5 m³ of sand (1600 kg/m³), 100 blocks without a weight factor.
        Result r = WeightAllocation.propose(d("1800"), List.of(line("agg", 1, "10", "10", "1000"),
                line("sand", 2, "5", "5", "1600"), line("blocks", 3, "100", "100", null)));
        // Weights 10 000 : 8 000 → 1 000 kg and 800 kg.
        assertThat(share(r, "agg").qty()).isEqualByComparingTo("1");
        assertThat(share(r, "sand").qty()).isEqualByComparingTo("0.5");
        assertThat(share(r, "blocks").qty()).isEqualByComparingTo("0");
        assertThat(share(r, "blocks").needsManual()).isTrue();
        assertThat(r.allocatedKg().add(r.unallocatedKg())).isEqualByComparingTo("1800");
    }

    @Test
    void aBindingCapRedistributesOverTheOthers() {
        // Equal weights, but line a may take only 0.2 t: the rest goes to b.
        Result r = WeightAllocation.propose(d("1000"), List.of(line("a", 1, "10", "0.2", "1000"),
                line("b", 2, "10", "10", "1000")));
        assertThat(share(r, "a").qty()).isEqualByComparingTo("0.2");
        assertThat(share(r, "a").capped()).isTrue();
        assertThat(share(r, "b").qty()).isEqualByComparingTo("0.8");
        assertThat(r.unallocatedKg()).isEqualByComparingTo("0");
    }

    @RepeatedTest(3)
    void largestRemainderIsDeterministicAndTiesBreakByLineNumber() {
        // 1 kg over three equal lines of 1000 kg/unit: 0.000333… each → floors to 0, nothing fits a 0.001 step (1 kg)
        // twice; exactly one line gets the step, and it is line 1.
        Result r = WeightAllocation.propose(d("1"), List.of(line("c", 3, "5", "5", "1000"), line("a", 1, "5", "5", "1000"),
                line("b", 2, "5", "5", "1000")));
        assertThat(share(r, "a").qty()).isEqualByComparingTo("0.001");
        assertThat(share(r, "b").qty()).isEqualByComparingTo("0");
        assertThat(share(r, "c").qty()).isEqualByComparingTo("0");
        assertThat(r.unallocatedKg()).isEqualByComparingTo("0");
    }

    @Test
    void neverAllocatesMoreThanTheVarianceOrACap() {
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 500; i++) {
            int n = 1 + rnd.nextInt(4);
            java.util.List<Line> lines = new java.util.ArrayList<>();
            for (int k = 0; k < n; k++) {
                BigDecimal e = BigDecimal.valueOf(rnd.nextInt(30000), 3);
                BigDecimal cap = e.multiply(BigDecimal.valueOf(rnd.nextInt(101))).movePointLeft(2)
                        .setScale(3, java.math.RoundingMode.DOWN);
                BigDecimal kg = rnd.nextInt(5) == 0 ? null : BigDecimal.valueOf(1 + rnd.nextInt(2000));
                lines.add(new Line("l" + k, k + 1, e, cap, kg));
            }
            BigDecimal v = BigDecimal.valueOf(rnd.nextInt(5_000_000), 3);
            Result r = WeightAllocation.propose(v, lines);
            assertThat(r.allocatedKg()).isLessThanOrEqualTo(v.setScale(3, java.math.RoundingMode.HALF_UP));
            assertThat(r.unallocatedKg().signum()).isGreaterThanOrEqualTo(0);
            for (int k = 0; k < n; k++) {
                assertThat(r.shares().get(k).qty()).isBetween(BigDecimal.ZERO, lines.get(k).capQty().max(BigDecimal.ZERO));
            }
        }
    }
}
