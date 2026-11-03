package com.inventoryerp.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.AvcoLineageCalculator.Distribution;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Event;
import com.inventoryerp.inventory.application.AvcoLineageCalculator.Share;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Landed cost LC-1 golden AVCO maths (ADR-S053, plan "AVCO maths"): every figure below is computed by hand. The
 * identity {@code c = on hand + Σ consumption + negative stock} must hold exactly at the money scale.
 */
class AvcoLandedMathTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Event out(String id, String type, String poolBefore, String qty) {
        return new Event(id, type, d(poolBefore), d(qty).negate(), "test", id);
    }

    private static Event in(String id, String poolBefore, String qty) {
        return new Event(id, "receipt", d(poolBefore), d(qty), "goods_receipt", id);
    }

    private static void identity(Distribution dist, String amount) {
        assertThat(dist.total()).as("c = on hand + consumption + negative stock").isEqualByComparingTo(amount);
    }

    private static BigDecimal share(Distribution dist, String ledgerId) {
        return dist.consumed().stream().filter(s -> s.ledgerId().equals(ledgerId)).map(Share::amount).findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("negative-stock cap: 100 available, issue of 120 → 100 distributed, never 120")
    void issueBeyondPoolTakesOnlyWhatIsThere() {
        Distribution dist = AvcoLineageCalculator.distribute(d("100"), d("100"), d("0"),
                List.of(out("i1", "issue", "100", "120")), 2);
        assertThat(share(dist, "i1")).isEqualByComparingTo("100");
        assertThat(dist.onHand()).isEqualByComparingTo("0");
        identity(dist, "100");
    }

    @Test
    @DisplayName("two receipts, issue, partial transfer, later receipt, issue: 1000 → 200 / 400 transfer / 400, none on hand")
    void fullSequence() {
        // Receipt A: 100 units into an empty pool; receipt B +50 → pool 150.
        // Issue 30 of 150: 1000·1·30/150 = 200, S = 0.8. Transfer 60 of 120: 1000·0.8·60/120 = 400, S = 0.4.
        // Later receipt +40 (S unchanged, pool 100). Issue 100 of 100: 1000·0.4 = 400, S = 0.
        Distribution dist = AvcoLineageCalculator.distribute(d("1000"), d("100"), d("0"), List.of(
                in("b", "100", "50"), out("i1", "issue", "150", "30"), out("t1", "transfer_out", "120", "60"),
                in("c", "60", "40"), out("i2", "issue", "100", "100")), 2);
        assertThat(share(dist, "i1")).isEqualByComparingTo("200");
        assertThat(share(dist, "t1")).isEqualByComparingTo("400");
        assertThat(share(dist, "i2")).isEqualByComparingTo("400");
        assertThat(dist.onHand()).isEqualByComparingTo("0");
        assertThat(AvcoLineageCalculator.target("transfer_out", false)).isEqualTo("transfer");
        identity(dist, "1000");
    }

    @Test
    @DisplayName("late charge on a layer that settled negative stock: 20 of 100 filled it → 200 negative, 400 sold, 400 on hand")
    void layerSettlingNegativeStock() {
        // Pool at −20 when the layer arrived: 20 % of it covered goods already sold → 200 to negative stock, S = 0.8.
        // Issue 40 of 80: 1000·0.8·40/80 = 400, S = 0.4 → 400 still on hand. The settlement never redistributes.
        Distribution dist = AvcoLineageCalculator.distribute(d("1000"), d("100"), d("-20"),
                List.of(out("i1", "issue", "80", "40")), 2);
        assertThat(dist.negativeStock()).isEqualByComparingTo("200");
        assertThat(share(dist, "i1")).isEqualByComparingTo("400");
        assertThat(dist.onHand()).isEqualByComparingTo("400");
        identity(dist, "1000");
    }

    @Test
    @DisplayName("pool empty before an issue: the layer is exhausted, later movements get nothing (epoch reset)")
    void epochReset() {
        Distribution dist = AvcoLineageCalculator.distribute(d("300"), d("30"), d("0"), List.of(
                out("i1", "issue", "30", "30"), in("r", "0", "10"), out("i2", "issue", "10", "10")), 2);
        assertThat(share(dist, "i1")).isEqualByComparingTo("300");
        assertThat(dist.consumed()).hasSize(1);
        identity(dist, "300");
    }

    @Test
    @DisplayName("rounding (D4): the residual goes to on hand, or to the largest consumption when nothing is left")
    void roundingResidual() {
        Distribution partly = AvcoLineageCalculator.distribute(d("100"), d("3"), d("0"),
                List.of(out("i1", "issue", "3", "1")), 2);
        assertThat(share(partly, "i1")).isEqualByComparingTo("33.33");
        assertThat(partly.onHand()).isEqualByComparingTo("66.67");
        identity(partly, "100");

        // 3.33 + 3.33 + 3.33 = 9.99; nothing on hand → the 0.01 goes to the (first) largest share.
        Distribution gone = AvcoLineageCalculator.distribute(d("10"), d("3"), d("0"), List.of(
                out("i1", "issue", "3", "1"), out("i2", "issue", "2", "1"), out("i3", "issue", "1", "1")), 2);
        assertThat(gone.onHand()).isEqualByComparingTo("0");
        assertThat(share(gone, "i1")).isEqualByComparingTo("3.34");
        identity(gone, "10");
    }

    @Test
    @DisplayName("targets: waste → scrap, open production → WIP, return → cogs in the shared mapper (LC-1 maps return)")
    void targets() {
        assertThat(AvcoLineageCalculator.target("waste", false)).isEqualTo("scrap");
        assertThat(AvcoLineageCalculator.target("production_consume", true)).isEqualTo("wip");
        assertThat(AvcoLineageCalculator.target("issue", false)).isEqualTo("cogs");
    }
}
