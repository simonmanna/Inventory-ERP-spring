package com.inventoryerp.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.AvcoLineageCalculator.Distribution;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Event;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-S031 AD-2a: survival-factor distribution of a later adjustment over an AVCO pool. */
class AvcoLineageCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static Event out(String id, String before, String qty) {
        return new Event(id, "issue", d(before), d(qty).negate(), "sales_invoice", id);
    }

    static Event in(String id, String before, String qty) {
        return new Event(id, "receipt", d(before), d(qty), "goods_receipt", id);
    }

    @Test
    @DisplayName("pro-rata depletion: 100 received into an empty pool, 30 + 20 issued, 50 received, 40 issued")
    void proRata() {
        // Layer X = 100 into an empty pool, adjustment 1000. Issue 30 of 100 → 300; issue 20 of 70 → 200;
        // receipt 50 (pool 100: 50 of X); issue 40 of 100 → X loses 40 % of its 50 → 200; on hand 30 of X → 300.
        Distribution r = AvcoLineageCalculator.distribute(d("1000"), d("100"), d("0"), List.of(out("a", "100", "30"),
                out("b", "70", "20"), in("c", "50", "50"), out("e", "100", "40")), 2);
        assertThat(r.consumed()).extracting(s -> s.amount()).containsExactly(d("300.00"), d("200.00"), d("200.00"));
        assertThat(r.onHand()).isEqualByComparingTo("300");
        assertThat(r.total()).isEqualByComparingTo("1000");
        assertThat(r.negativeStock()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("epoch reset: once the pool is empty the layer is fully consumed; later moves are not visited")
    void reset() {
        Distribution r = AvcoLineageCalculator.distribute(d("90"), d("10"), d("0"), List.of(out("a", "10", "10"),
                in("b", "0", "5"), out("c", "5", "5")), 2);
        assertThat(r.consumed()).hasSize(1);
        assertThat(r.consumed().getFirst().amount()).isEqualByComparingTo("90");
        assertThat(r.onHand()).isEqualByComparingTo("0");
        assertThat(r.survival()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("negative stock: the units that filled a negative pool were already sold")
    void negativeStock() {
        // Pool at −4 when 10 arrive: 4 units filled negative stock → 40 % of the adjustment.
        Distribution r = AvcoLineageCalculator.distribute(d("100"), d("10"), d("-4"), List.of(out("a", "6", "3")), 2);
        assertThat(r.negativeStock()).isEqualByComparingTo("40");
        assertThat(r.consumed().getFirst().amount()).isEqualByComparingTo("30");
        assertThat(r.onHand()).isEqualByComparingTo("30");
        assertThat(r.total()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("rounding: shares always sum exactly to the adjustment (randomised)")
    void exactSums() {
        Random rnd = new Random(42);
        for (int run = 0; run < 500; run++) {
            BigDecimal amount = BigDecimal.valueOf(rnd.nextInt(1_000_000) - 500_000, 2);
            BigDecimal layer = BigDecimal.valueOf(rnd.nextInt(97) + 1);
            BigDecimal pool = layer.add(BigDecimal.valueOf(rnd.nextInt(50)));
            List<Event> events = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                if (rnd.nextBoolean() && pool.signum() > 0) {
                    BigDecimal q = BigDecimal.valueOf(rnd.nextInt(pool.intValue()) + 1);
                    events.add(out("o" + i, pool.toPlainString(), q.toPlainString()));
                    pool = pool.subtract(q);
                } else {
                    BigDecimal q = BigDecimal.valueOf(rnd.nextInt(20) + 1);
                    events.add(in("i" + i, pool.toPlainString(), q.toPlainString()));
                    pool = pool.add(q);
                }
            }
            Distribution r = AvcoLineageCalculator.distribute(amount, layer, BigDecimal.ZERO, events, 2);
            assertThat(r.total()).as("run " + run).isEqualByComparingTo(amount);
        }
    }

    @Test
    @DisplayName("targets: transfer stays in stock, waste is scrap, open production is WIP")
    void targets() {
        assertThat(AvcoLineageCalculator.target("transfer_out", false)).isEqualTo("transfer");
        assertThat(AvcoLineageCalculator.target("waste", false)).isEqualTo("scrap");
        assertThat(AvcoLineageCalculator.target("production_consume", true)).isEqualTo("wip");
        assertThat(AvcoLineageCalculator.target("production_consume", false)).isEqualTo("cogs");
        assertThat(AvcoLineageCalculator.target("issue", false)).isEqualTo("cogs");
        assertThat(AvcoLineageCalculator.target(null, false)).isEqualTo("cogs");
    }
}
