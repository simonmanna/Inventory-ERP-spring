package com.inventoryerp.projects.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.projects.application.ContractCalculator.Claim;
import com.inventoryerp.projects.application.ContractCalculator.OnerousInput;
import com.inventoryerp.projects.application.ContractCalculator.OnerousResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cost Management CM-8b (ADR-S039): IFRS 15 progress, claim deductions, IAS 37 onerous provision. */
class ContractCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("cost-to-cost progress and cumulative revenue; capped; zero when nothing is estimated")
    void progress() {
        BigDecimal p = ContractCalculator.progress(d("30000"), d("45000"));
        assertThat(p).isEqualByComparingTo("0.4");
        assertThat(ContractCalculator.cumulativeRevenue(d("110000"), p)).isEqualByComparingTo("44000");
        assertThat(ContractCalculator.progress(d("0"), d("0"))).isEqualByComparingTo("0");
        assertThat(ContractCalculator.progress(d("10"), d("0"))).isEqualByComparingTo("1");
        assertThat(ContractCalculator.cumulativeRevenue(d("100"), ContractCalculator.progress(d("1"), d("2"))))
                .isEqualByComparingTo("33.33");
    }

    @Test
    @DisplayName("claim: retention and advance recovery capped at the outstanding advance; net = the rest")
    void claim() {
        Claim c = ContractCalculator.claim(d("40000"), d("0"), d("10"), d("20"), d("5000"));
        assertThat(c.thisClaim()).isEqualByComparingTo("40000");
        assertThat(c.retention()).isEqualByComparingTo("4000");
        assertThat(c.advanceRecovery()).isEqualByComparingTo("5000");
        assertThat(c.net()).isEqualByComparingTo("31000");
        Claim none = ContractCalculator.claim(d("60000"), d("40000"), d("0"), d("0"), d("0"));
        assertThat(none.net()).isEqualByComparingTo("20000");
        assertThat(none.retention().add(none.advanceRecovery()).add(none.net())).isEqualByComparingTo(
                none.thisClaim());
    }

    @Test
    @DisplayName("onerous: fulfilment shortfall; cheaper exit wins; impairment first; excluded cost never counts")
    void onerous() {
        OnerousResult base = ContractCalculator.onerous(new OnerousInput(d("1000"), d("0"), d("1500"), d("300"), null,
                d("0")));
        assertThat(base.costOfFulfilling()).isEqualByComparingTo("1800");
        assertThat(base.provision()).isEqualByComparingTo("800");
        assertThat(base.exitCheaper()).isFalse();

        OnerousResult exit = ContractCalculator.onerous(new OnerousInput(d("1000"), d("0"), d("1500"), d("300"),
                d("300"), d("0")));
        assertThat(exit.exitCheaper()).isTrue();
        assertThat(exit.provision()).as("the exit-based amount").isEqualByComparingTo("300");

        OnerousResult dearExit = ContractCalculator.onerous(new OnerousInput(d("1000"), d("0"), d("1500"), d("300"),
                d("2000"), d("0")));
        assertThat(dearExit.exitCheaper()).isFalse();
        assertThat(dearExit.provision()).isEqualByComparingTo("800");

        OnerousResult impaired = ContractCalculator.onerous(new OnerousInput(d("1000"), d("0"), d("1500"), d("300"),
                null, d("200")));
        assertThat(impaired.provision()).as("impairment recognised first").isEqualByComparingTo("600");

        OnerousResult claims = ContractCalculator.onerous(new OnerousInput(d("1000"), d("900"), d("1500"), d("300"),
                null, d("0")));
        assertThat(claims.provision()).as("EAC above revenue alone does not provide").isEqualByComparingTo("0");
    }
}
