package com.inventoryerp.costing.application;

import com.inventoryerp.shared.costing.AllocationCalculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.shared.costing.AllocationCalculator.Receiver;
import com.inventoryerp.shared.costing.AllocationCalculator.Rounding;
import com.inventoryerp.shared.costing.AllocationCalculator.Share;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-S033: the shared allocation primitive and the reciprocal solver. */
class AllocationCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static BigDecimal sum(List<Share> shares) {
        return shares.stream().map(Share::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("largest remainder: 100 over three equal receivers → 33.34 / 33.33 / 33.33")
    void largestRemainder() {
        List<Share> s = AllocationCalculator.allocate(d("100"), List.of(new Receiver("a", d("1")),
                new Receiver("b", d("1")), new Receiver("c", d("1"))), Rounding.LARGEST_REMAINDER, 2);
        assertThat(s).extracting(Share::amount).containsExactly(d("33.34"), d("33.33"), d("33.33"));
    }

    @Test
    @DisplayName("last / main: the difference lands on the last or the first receiver")
    void lastAndMain() {
        List<Receiver> r = List.of(new Receiver("a", d("1")), new Receiver("b", d("1")), new Receiver("c", d("1")));
        assertThat(AllocationCalculator.allocate(d("100"), r, Rounding.LAST, 2)).extracting(Share::amount)
                .containsExactly(d("33.33"), d("33.33"), d("33.34"));
        assertThat(AllocationCalculator.allocate(d("100"), r, Rounding.MAIN, 2)).extracting(Share::amount)
                .containsExactly(d("33.34"), d("33.33"), d("33.33"));
    }

    @Test
    @DisplayName("exact sums for every rounding, positive and negative totals (randomised)")
    void exact() {
        Random rnd = new Random(7);
        for (int run = 0; run < 600; run++) {
            BigDecimal total = BigDecimal.valueOf(rnd.nextInt(2_000_000) - 1_000_000, 2);
            List<Receiver> receivers = new ArrayList<>();
            int n = rnd.nextInt(9) + 1;
            for (int i = 0; i < n; i++) {
                receivers.add(new Receiver("r" + i, BigDecimal.valueOf(rnd.nextInt(1000), rnd.nextInt(3))));
            }
            if (receivers.stream().allMatch(x -> x.basis().signum() == 0)) {
                continue;
            }
            Rounding rounding = Rounding.values()[run % 3];
            assertThat(sum(AllocationCalculator.allocate(total, receivers, rounding, 2))).as("run " + run)
                    .isEqualByComparingTo(total);
        }
    }

    @Test
    @DisplayName("refused: negative basis, all-zero basis, no receivers, unknown rounding")
    void refused() {
        assertThatThrownBy(() -> AllocationCalculator.allocate(d("10"), List.of(new Receiver("a", d("-1"))),
                Rounding.LAST, 2)).isInstanceOf(ApiException.class).hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> AllocationCalculator.allocate(d("10"), List.of(new Receiver("a", d("0"))),
                Rounding.LAST, 2)).isInstanceOf(ApiException.class).hasMessageContaining("nothing to allocate by");
        assertThatThrownBy(() -> AllocationCalculator.allocate(d("10"), List.of(), Rounding.LAST, 2))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Rounding.of("banker")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("reciprocal: S1 = 1000 + 10 % of S2, S2 = 500 + 20 % of S1 → 1071.43 / 714.29")
    void reciprocal() {
        Map<String, BigDecimal> direct = new LinkedHashMap<>();
        direct.put("S1", d("1000"));
        direct.put("S2", d("500"));
        Map<String, Map<String, BigDecimal>> f = new LinkedHashMap<>();
        f.put("S1", Map.of("S2", d("0.2"), "P1", d("0.4"), "P2", d("0.4")));
        f.put("S2", Map.of("S1", d("0.1"), "P1", d("0.5"), "P2", d("0.4")));
        Map<String, BigDecimal> t = AllocationCalculator.reciprocal(direct, f);
        assertThat(t.get("S1").setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("1071.43");
        assertThat(t.get("S2").setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("714.29");
        Map<String, Map<String, BigDecimal>> loop = Map.of("S1", Map.of("S2", d("1")), "S2", Map.of("S1", d("1")));
        assertThatThrownBy(() -> AllocationCalculator.reciprocal(direct, loop)).isInstanceOf(ApiException.class)
                .hasMessageContaining("no solution");
    }

    @Test
    @DisplayName("reciprocal, ACR-7: centres passing 99 % to each other are solved exactly (the 100-round iteration"
            + " left a third of the cost behind)")
    void reciprocalExactWhenServiceCentresMostlyServeEachOther() {
        Map<String, BigDecimal> direct = new LinkedHashMap<>();
        direct.put("S1", d("100"));
        direct.put("S2", d("0"));
        Map<String, Map<String, BigDecimal>> f = new LinkedHashMap<>();
        f.put("S1", Map.of("S2", d("0.99"), "P1", d("0.01")));
        f.put("S2", Map.of("S1", d("0.99"), "P2", d("0.01")));
        Map<String, BigDecimal> t = AllocationCalculator.reciprocal(direct, f);
        // T1 = 100 / (1 − 0.99²) = 5025.1256…, T2 = 0.99 · T1 = 4974.8743…
        assertThat(t.get("S1").setScale(4, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("5025.1256");
        assertThat(t.get("S2").setScale(4, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("4974.8744");
        // What leaves the service centres for production is exactly their direct cost.
        BigDecimal out = t.get("S1").multiply(d("0.01")).add(t.get("S2").multiply(d("0.01")));
        assertThat(out.setScale(8, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("100");
        // Each equation holds to well below a cent.
        assertThat(t.get("S1").subtract(d("100")).subtract(t.get("S2").multiply(d("0.99"))).abs())
                .isLessThan(d("1e-20"));
    }

    @Test
    @DisplayName("reciprocal, ACR-7: three centres in a ring, and a sender outside the service set is ignored")
    void reciprocalThreeCentres() {
        Map<String, BigDecimal> direct = new LinkedHashMap<>();
        direct.put("A", d("300"));
        direct.put("B", d("200"));
        direct.put("C", d("100"));
        Map<String, Map<String, BigDecimal>> f = new LinkedHashMap<>();
        f.put("A", Map.of("B", d("0.5"), "P", d("0.5")));
        f.put("B", Map.of("C", d("0.5"), "P", d("0.5")));
        f.put("C", Map.of("A", d("0.5"), "P", d("0.5")));
        f.put("X", Map.of("A", d("0.9")));
        Map<String, BigDecimal> t = AllocationCalculator.reciprocal(direct, f);
        // A = 300 + C/2, B = 200 + A/2, C = 100 + B/2 → A = 3200/7, B = 3000/7, C = 2200/7
        assertThat(t.get("A").setScale(6, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("457.142857");
        assertThat(t.get("B").setScale(6, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("428.571429");
        assertThat(t.get("C").setScale(6, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("314.285714");
        // Half of every total goes to production: exactly the 600 of direct cost.
        assertThat(t.get("A").add(t.get("B")).add(t.get("C")).multiply(d("0.5")).setScale(8,
                java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("600");
    }
}
