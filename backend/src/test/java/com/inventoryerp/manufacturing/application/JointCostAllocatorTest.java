package com.inventoryerp.manufacturing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.manufacturing.application.JointCostAllocator.Result;
import com.inventoryerp.manufacturing.application.JointCostAllocator.Share;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Joint-cost allocation over co-products / by-products / waste (ADR-S011 §4): methods, carve-outs, exact sums. */
class JointCostAllocatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static Share main(String qty) {
        return new Share("main", "main", d(qty), "allocated", null, null, null, null);
    }

    static Share co(String key, String qty, String weight, String market, String pct) {
        return new Share(key, "co_product", d(qty), "allocated", weight == null ? null : d(weight),
                market == null ? null : d(market), pct == null ? null : d(pct), null);
    }

    static BigDecimal sum(Result r) {
        return r.values().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("by quantity: 1,000 over 30 / 40 / 30 t → 300 / 400 / 300")
    void byQuantity() {
        Result r = JointCostAllocator.allocate(d("1000"), List.of(main("30"), co("a", "40", null, null, null),
                co("b", "30", null, null, null)), "quantity", 6);
        assertThat(r.values().get("main")).isEqualByComparingTo("300");
        assertThat(r.values().get("a")).isEqualByComparingTo("400");
        assertThat(r.values().get("b")).isEqualByComparingTo("300");
        assertThat(r.method()).isEqualTo("quantity");
    }

    @Test
    @DisplayName("rounding: 100 over three equal outputs at 2 dp → 33.34 / 33.33 / 33.33, sum exactly 100")
    void roundingSumsExactly() {
        Result r = JointCostAllocator.allocate(d("100"), List.of(main("1"), co("a", "1", null, null, null),
                co("b", "1", null, null, null)), "quantity", 2);
        assertThat(sum(r)).isEqualByComparingTo("100");
        assertThat(r.values().get("main")).isEqualByComparingTo("33.34");
        assertThat(r.values().get("a")).isEqualByComparingTo("33.33");
        // Awkward weights at scale 6 still sum exactly.
        Result r2 = JointCostAllocator.allocate(d("1234.567891"), List.of(main("7"), co("a", "13", null, null, null),
                co("b", "0.333333", null, null, null), co("c", "19.1", null, null, null)), "quantity", 6);
        assertThat(sum(r2)).isEqualByComparingTo("1234.567891");
    }

    @Test
    @DisplayName("by weight: quantity × weight per unit (main defaults to 1)")
    void byWeight() {
        Result r = JointCostAllocator.allocate(d("900"), List.of(main("100"), co("a", "100", "2", null, null)),
                "weight", 6);
        assertThat(r.values().get("main")).isEqualByComparingTo("300");
        assertThat(r.values().get("a")).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("by market value: quantity × unit value; a zero basis falls back to quantity")
    void byMarketValue() {
        Result r = JointCostAllocator.allocate(d("1000"), List.of(
                new Share("main", "main", d("10"), "allocated", null, d("30"), null, null),
                co("a", "20", null, "10", null)), "market_value", 6);
        assertThat(r.values().get("main")).isEqualByComparingTo("600");
        assertThat(r.values().get("a")).isEqualByComparingTo("400");
        Result fallback = JointCostAllocator.allocate(d("100"), List.of(main("1"), co("a", "3", null, null, null)),
                "market_value", 6);
        assertThat(fallback.values().get("a")).isEqualByComparingTo("75");
    }

    @Test
    @DisplayName("by fixed %: co-products take their %, the main output the rest of 100")
    void byFixedPct() {
        Result r = JointCostAllocator.allocate(d("500"), List.of(main("1"), co("a", "1", null, null, "30"),
                co("b", "1", null, null, "10")), "fixed_pct", 6);
        assertThat(r.values().get("main")).isEqualByComparingTo("300");
        assertThat(r.values().get("a")).isEqualByComparingTo("150");
        assertThat(r.values().get("b")).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("by-product at NRV carved out first; waste at zero; the rest by quantity")
    void nrvAndZero() {
        Result r = JointCostAllocator.allocate(d("1000"), List.of(main("60"), co("a", "40", null, null, null),
                new Share("dust", "by_product", d("20"), "nrv", null, null, null, d("5")),
                new Share("fines", "waste", d("10"), "zero", null, null, null, null)), "quantity", 6);
        assertThat(r.values().get("dust")).isEqualByComparingTo("100");
        assertThat(r.values().get("fines")).isEqualByComparingTo("0");
        assertThat(r.values().get("main")).isEqualByComparingTo("540");
        assertThat(r.values().get("a")).isEqualByComparingTo("360");
        assertThat(r.carvedOut()).isEqualByComparingTo("100");
        assertThat(sum(r)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("NRV larger than the joint cost is capped; nothing goes negative")
    void nrvCapped() {
        Result r = JointCostAllocator.allocate(d("50"), List.of(main("10"),
                new Share("bp", "by_product", d("100"), "nrv", null, null, null, d("1"))), "quantity", 6);
        assertThat(r.values().get("bp")).isEqualByComparingTo("50");
        assertThat(r.values().get("main")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("legacy carve-out %: a by-product takes its % of the pool before allocation")
    void carvePct() {
        Result r = JointCostAllocator.allocate(d("200"), List.of(main("5"),
                new Share("bp", "by_product", d("1"), "carve_pct", null, null, d("10"), null)), "quantity", 6);
        assertThat(r.values().get("bp")).isEqualByComparingTo("20");
        assertThat(r.values().get("main")).isEqualByComparingTo("180");
    }

    @Test
    @DisplayName("no allocated output: the residue stays on the first output; zero quantities go to the main output")
    void edgeCases() {
        Result r = JointCostAllocator.allocate(d("10"), List.of(
                new Share("w", "waste", d("1"), "zero", null, null, null, null)), "quantity", 6);
        assertThat(r.values().get("w")).isEqualByComparingTo("10");
        Result z = JointCostAllocator.allocate(d("10"), List.of(main("0"), co("a", "0", null, null, null)),
                "weight", 6);
        assertThat(z.values().get("main")).isEqualByComparingTo("10");
        Result unknown = JointCostAllocator.allocate(d("10"), List.of(main("1")), "bogus", 6);
        assertThat(unknown.method()).isEqualTo("quantity");
        assertThatThrownBy(() -> JointCostAllocator.allocate(d("-1"), List.of(main("1")), "quantity", 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JointCostAllocator.allocate(d("1"), List.of(), "quantity", 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("apportion: largest remainder, exact sum; zero weights put everything on the first")
    void apportion() {
        List<BigDecimal> parts = JointCostAllocator.apportion(d("0.05"), List.of(d("1"), d("1"), d("1")), 2);
        assertThat(parts).containsExactly(d("0.02"), d("0.02"), d("0.01"));
        assertThat(JointCostAllocator.apportion(d("7"), List.of(d("0"), d("0")), 2))
                .containsExactly(d("7.00"), d("0.00"));
        Map<String, BigDecimal> v = JointCostAllocator.allocate(d("0"), List.of(main("1")), "quantity", 6).values();
        assertThat(v.get("main")).isEqualByComparingTo("0");
    }
}
