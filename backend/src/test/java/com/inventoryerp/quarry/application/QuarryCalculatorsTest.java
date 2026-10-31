package com.inventoryerp.quarry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.quarry.application.QuarryCostCalculator.Blast;
import com.inventoryerp.quarry.application.QuarryCostCalculator.Input;
import com.inventoryerp.quarry.application.QuarryCostCalculator.Resource;
import com.inventoryerp.quarry.application.QuarryCostCalculator.Result;
import com.inventoryerp.quarry.application.QuarryCostCalculator.Royalty;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Quarry 2.2 — cost build-up / per-tonne allocation and extraction-vs-target variance (pure). */
class QuarryCalculatorsTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static Input input(String gross, String loss, String basis, List<Resource> resources, Blast blast,
            List<Royalty> royalties, String other) {
        return new Input(d(gross), d(loss), basis, resources, blast, royalties, d(other));
    }

    @Test
    @DisplayName("net-tonne basis: every component on the net tonnes, unit cost = total / net")
    void netBasis() {
        Result r = QuarryCostCalculator.compute(input("225.5", "15.5", "net_tonnes", List.of(
                new Resource("equipment", d("8"), d("120"), d("100"), d("2.5")),
                new Resource("labour", d("8"), d("10"), null, null)), null, List.of(
                new Royalty("r1", "Royalty", "net_tonnes", d("3"), "p1"),
                new Royalty("r2", "Council levy", "gross_tonnes", d("0.5"), null)), "20"));
        assertThat(r.netTonnes()).isEqualByComparingTo("210");
        assertThat(r.equipment()).isEqualByComparingTo("960.00");
        assertThat(r.fuel()).isEqualByComparingTo("250.00");
        assertThat(r.labour()).isEqualByComparingTo("80.00");
        assertThat(r.royalty()).isEqualByComparingTo("742.75");
        assertThat(r.other()).isEqualByComparingTo("20.00");
        assertThat(r.total()).isEqualByComparingTo("2052.75");
        assertThat(r.capitalised()).isEqualByComparingTo(r.total());
        assertThat(r.lossCost()).isZero();
        assertThat(r.unitCost()).isEqualByComparingTo("9.775000");
        assertThat(r.lines()).extracting(QuarryCostCalculator.Line::component)
                .containsExactly("equipment", "fuel", "labour", "royalty", "royalty", "other");
        assertThat(r.lines().get(4).quantity()).isEqualByComparingTo("225.5");
        assertThat(r.lines().get(3).partnerId()).isEqualTo("p1");
        assertThat(r.resourceCosts().get(0).hoursCost()).isEqualByComparingTo("960.00");
        assertThat(r.resourceCosts().get(0).fuelCost()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("gross-tonne basis: net tonnes carry their share, the loss share is written off; debits = credits")
    void grossBasis() {
        Result r = QuarryCostCalculator.compute(input("120", "20", "gross_tonnes", List.of(
                new Resource("labour", d("10"), d("10"), null, null)), null, List.of(), "20"));
        assertThat(r.total()).isEqualByComparingTo("120.00");
        assertThat(r.capitalised()).isEqualByComparingTo("100.00");
        assertThat(r.lossCost()).isEqualByComparingTo("20.00");
        assertThat(r.capitalised().add(r.lossCost())).isEqualByComparingTo(r.total());
        assertThat(r.unitCost()).isEqualByComparingTo("1.000000");
        // no losses → same as net
        Result none = QuarryCostCalculator.compute(input("120", "0", "gross_tonnes", List.of(), null, List.of(), "33"));
        assertThat(none.lossCost()).isZero();
        assertThat(none.unitCost()).isEqualByComparingTo("0.275000");
    }

    @Test
    @DisplayName("gross basis rounding: capitalised + loss always equals the total")
    void grossRounding() {
        Result r = QuarryCostCalculator.compute(input("3", "1", "gross_tonnes", List.of(), null, List.of(), "10"));
        assertThat(r.capitalised()).isEqualByComparingTo("6.67");
        assertThat(r.lossCost()).isEqualByComparingTo("3.33");
        assertThat(r.unitCost()).isEqualByComparingTo("3.335000");
    }

    @Test
    @DisplayName("blasting: pool / estimated tonnes × net, capped at what the pool has left")
    void blastAbsorption() {
        Result r = QuarryCostCalculator.compute(input("1000", "0", "net_tonnes", List.of(),
                new Blast("b", "BL-1", d("960"), d("0"), d("5103")), List.of(), "0"));
        assertThat(r.blastRate()).isEqualByComparingTo("0.188125");
        assertThat(r.blasting()).isEqualByComparingTo("188.13");
        Result capped = QuarryCostCalculator.compute(input("1000", "0", "net_tonnes", List.of(),
                new Blast("b", "BL-1", d("960"), d("900"), d("5103")), List.of(), "0"));
        assertThat(capped.blasting()).isEqualByComparingTo("60.00");
        Result exhausted = QuarryCostCalculator.compute(input("1000", "0", "net_tonnes", List.of(),
                new Blast("b", "BL-1", d("960"), d("960"), d("5103")), List.of(), "0"));
        assertThat(exhausted.blasting()).isZero();
        assertThat(exhausted.lines()).isEmpty();
        Result noEstimate = QuarryCostCalculator.compute(input("10", "0", "net_tonnes", List.of(),
                new Blast("b", "BL-1", d("500"), d("100"), null), List.of(), "0"));
        assertThat(noEstimate.blasting()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("refuses no quantity and losses at or above gross")
    void guards() {
        assertThatThrownBy(() -> QuarryCostCalculator.compute(input("0", "0", "net_tonnes", List.of(), null,
                List.of(), "0"))).isInstanceOf(ApiException.class)
                .hasMessage("An extraction log needs at least one quantity line");
        assertThatThrownBy(() -> QuarryCostCalculator.compute(input("10", "10", "net_tonnes", List.of(), null,
                List.of(), "0"))).isInstanceOf(ApiException.class)
                .hasMessage("Losses (10 t) must be less than the gross tonnes (10 t)");
        Result zero = QuarryCostCalculator.compute(input("10", "0", "net_tonnes", List.of(), null, List.of(), "0"));
        assertThat(zero.total()).isZero();
        assertThat(zero.unitCost()).isZero();
    }

    @Test
    @DisplayName("loads × capacity and kg → t")
    void quantities() {
        assertThat(QuarryCostCalculator.loadTonnes(10, d("20"))).isEqualByComparingTo("200.000");
        assertThat(QuarryCostCalculator.loadTonnes(3, d("18.333"))).isEqualByComparingTo("54.999");
        assertThat(QuarryCostCalculator.kgToTonnes(d("25500"))).isEqualByComparingTo("25.500");
        assertThat(QuarryCostCalculator.kgToTonnes(d("12345.6"))).isEqualByComparingTo("12.346");
    }

    @Test
    @DisplayName("target variance: pace to date, achievement, status during and after the period")
    void targetVariance() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 31);
        TargetVariance.Variance mid = TargetVariance.of(d("31000"), d("10000"), start, end, LocalDate.of(2026, 10, 10));
        assertThat(mid.totalDays()).isEqualTo(31);
        assertThat(mid.elapsedDays()).isEqualTo(10);
        assertThat(mid.expectedToDate()).isEqualByComparingTo("10000.000");
        assertThat(mid.status()).isEqualTo("on_track");
        assertThat(mid.variance()).isEqualByComparingTo("-21000");
        assertThat(mid.achievedPct()).isEqualByComparingTo("32.3");
        assertThat(TargetVariance.of(d("31000"), d("9000"), start, end, LocalDate.of(2026, 10, 10)).status())
                .isEqualTo("behind");
        assertThat(TargetVariance.of(d("31000"), d("12000"), start, end, LocalDate.of(2026, 10, 10)).status())
                .isEqualTo("ahead");
        assertThat(TargetVariance.of(d("31000"), d("0"), start, end, LocalDate.of(2026, 9, 30)).status())
                .isEqualTo("not_started");
        assertThat(TargetVariance.of(d("31000"), d("31000"), start, end, LocalDate.of(2026, 11, 2)).status())
                .isEqualTo("achieved");
        TargetVariance.Variance missed = TargetVariance.of(d("31000"), d("30000"), start, end,
                LocalDate.of(2026, 11, 2));
        assertThat(missed.status()).isEqualTo("missed");
        assertThat(missed.elapsedDays()).isEqualTo(31);
        assertThat(missed.paceVariance()).isEqualByComparingTo("-1000");
    }
}
