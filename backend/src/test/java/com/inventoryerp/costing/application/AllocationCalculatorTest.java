package com.inventoryerp.costing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.costing.application.AllocationCalculator.Receiver;
import com.inventoryerp.costing.application.AllocationCalculator.Rounding;
import com.inventoryerp.costing.application.AllocationCalculator.Share;
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
}
