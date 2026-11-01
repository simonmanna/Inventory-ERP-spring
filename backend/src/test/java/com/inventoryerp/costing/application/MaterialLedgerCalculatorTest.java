package com.inventoryerp.costing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.costing.application.MaterialLedgerCalculator.Input;
import com.inventoryerp.costing.application.MaterialLedgerCalculator.Result;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MaterialLedgerCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void poolIsSpreadOverAvailableQuantity() {
        // 10 on hand (no carry), 30 received with +120 PPV, 25 consumed: 15 of 40 remain → 45 stays in stock.
        Result r = MaterialLedgerCalculator.compute(new Input(d("10"), d("0"), d("30"), d("120"), d("25"), d("10")));
        assertThat(r.endingQty()).isEqualByComparingTo("15");
        assertThat(r.pool()).isEqualByComparingTo("120.00");
        assertThat(r.toInventory()).isEqualByComparingTo("45.00");
        assertThat(r.toConsumption()).isEqualByComparingTo("75.00");
        assertThat(r.periodicUnitCost()).isEqualByComparingTo("13.000000");
        assertThat(r.delta()).isEqualByComparingTo("45.00");
    }

    @Test
    void carriedVarianceFlowsOutWhenStockIsConsumed() {
        // Last run left 45 in stock; nothing received, everything consumed: carry released, delta −45.
        Result r = MaterialLedgerCalculator.compute(new Input(d("15"), d("45"), d("0"), d("0"), d("15"), d("10")));
        assertThat(r.toInventory()).isEqualByComparingTo("0.00");
        assertThat(r.toConsumption()).isEqualByComparingTo("45.00");
        assertThat(r.delta()).isEqualByComparingTo("-45.00");
    }

    @Test
    void favourableVarianceAndRoundingAddUp() {
        Result r = MaterialLedgerCalculator.compute(new Input(d("0"), d("0"), d("3"), d("-10"), d("1"), d("5")));
        assertThat(r.toInventory()).isEqualByComparingTo("-6.67");
        assertThat(r.toConsumption()).isEqualByComparingTo("-3.33");
        assertThat(r.toInventory().add(r.toConsumption())).isEqualByComparingTo(r.pool());
    }

    @Test
    void noAvailableQuantityPutsEverythingInConsumption() {
        Result r = MaterialLedgerCalculator.compute(new Input(d("0"), d("12"), d("0"), d("8"), d("0"), d("4")));
        assertThat(r.toInventory()).isEqualByComparingTo("0.00");
        assertThat(r.toConsumption()).isEqualByComparingTo("20.00");
        assertThat(r.periodicUnitCost()).isEqualByComparingTo("4");
        assertThat(r.delta()).isEqualByComparingTo("-12.00");
    }
}
