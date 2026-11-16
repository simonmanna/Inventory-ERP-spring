package com.inventoryerp.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** R-8 (ADR-S076): carrying-value relief and the funds check of a foreign-currency account (plan §3). */
class ForeignCashTest {

    private static ForeignCash.Balance bal(String f, String b) {
        return new ForeignCash.Balance(new BigDecimal(f), new BigDecimal(b));
    }

    @Test
    void partialOutflowAtCarryingRate() {
        assertThat(ForeignCash.relieve(bal("130", "500000"), new BigDecimal("30"), new BigDecimal("3900")))
                .isEqualByComparingTo("115384.615385");
    }

    @Test
    void emptyingOutflowRelievesTheWholeCarryingValue() {
        assertThat(ForeignCash.relieve(bal("3", "10000"), new BigDecimal("3"), new BigDecimal("9999")))
                .isEqualByComparingTo("10000");
    }

    @Test
    void beyondTheBalanceAtTheSpotRate() {
        assertThat(ForeignCash.relieve(bal("10", "37000"), new BigDecimal("12"), new BigDecimal("3800")))
                .isEqualByComparingTo("44600");
        assertThat(ForeignCash.relieve(bal("0", "0"), new BigDecimal("5"), new BigDecimal("3800")))
                .isEqualByComparingTo("19000");
    }

    @Test
    void carryingRateAndFunds() {
        assertThat(bal("100", "370000").carryingRate()).isEqualByComparingTo("3700");
        assertThat(bal("0", "5").carryingRate()).isNull();
        assertThatThrownBy(() -> ForeignCash.assertFunds(bal("60", "1"), new BigDecimal("61"), "Bank", "QAA"))
                .hasMessage("Bank has insufficient funds (available QAA 60.00)");
    }
}
