package com.inventoryerp.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.ReplenishmentCalculator;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How much to reorder (INV-3). */
class ReplenishmentCalculatorTest {

    BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("at or above the minimum nothing is ordered")
    void enough() {
        assertThat(ReplenishmentCalculator.suggest(d("10"), d("10"), d("50"), null)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a maximum orders up to it; negative availability adds to the order")
    void upToMax() {
        assertThat(ReplenishmentCalculator.suggest(d("4"), d("10"), d("50"), d("5"))).isEqualByComparingTo("46");
        assertThat(ReplenishmentCalculator.suggest(d("-2"), d("10"), d("50"), null)).isEqualByComparingTo("52");
    }

    @Test
    @DisplayName("a reorder quantity orders that much, or the shortfall if bigger")
    void reorderQuantity() {
        assertThat(ReplenishmentCalculator.suggest(d("8"), d("10"), null, d("25"))).isEqualByComparingTo("25");
        assertThat(ReplenishmentCalculator.suggest(d("0"), d("100"), null, d("25"))).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("with neither, the shortfall")
    void shortfall() {
        assertThat(ReplenishmentCalculator.suggest(d("7.5"), d("10"), null, null)).isEqualByComparingTo("2.5");
    }
}
