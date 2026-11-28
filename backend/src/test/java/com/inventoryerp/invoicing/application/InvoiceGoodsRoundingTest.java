package com.inventoryerp.invoicing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** IPR review (unit rounding over-issue): an undelivered line never states more base units than are undelivered. */
class InvoiceGoodsRoundingTest {

    @Test
    @DisplayName("2 base units left in packs of 3 are not 0.667 pack (2.001 base): the line goes to base units")
    void nonTerminatingPackFallsBackToBaseUnits() {
        assertThat(InvoiceGoodsService.lineQuantity(new BigDecimal("2"), new BigDecimal("3"))).isNull();
    }

    @Test
    @DisplayName("an exact pack quantity keeps its unit")
    void exactPackKeepsUnit() {
        assertThat(InvoiceGoodsService.lineQuantity(new BigDecimal("6"), new BigDecimal("3")))
                .isEqualByComparingTo("2");
        assertThat(InvoiceGoodsService.lineQuantity(new BigDecimal("1.5"), new BigDecimal("0.5")))
                .isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("the stated quantity never exceeds the base quantity")
    void neverAbove() {
        for (int base = 1; base <= 50; base++) {
            for (String f : new String[] {"3", "7", "12", "0.333", "2.5"}) {
                BigDecimal factor = new BigDecimal(f);
                BigDecimal q = InvoiceGoodsService.lineQuantity(BigDecimal.valueOf(base), factor);
                if (q != null) {
                    assertThat(q.multiply(factor)).isLessThanOrEqualTo(BigDecimal.valueOf(base));
                }
            }
        }
    }
}
