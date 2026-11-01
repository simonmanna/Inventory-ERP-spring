package com.inventoryerp.costing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.costing.application.DistributionCostCalculator.Charged;
import com.inventoryerp.costing.application.DistributionCostCalculator.Trip;
import com.inventoryerp.fleet.application.FreightCalculator;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CM-7 (ADR-S037): empty-leg policies, exact sums, cost per tonne-km; transporter rate bases and minimum charge. */
class DistributionCostCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static final List<Trip> TRIPS = List.of(
            new Trip("L1", "V1", d("100"), d("100"), d("20")), // 2000 t-km, returns empty
            new Trip("L2", "V1", d("50"), d("0"), d("20")),    // 1000 t-km, back-loaded
            new Trip("L3", "V2", d("30"), d("10"), d("10")));

    static Charged of(List<Charged> list, String load) {
        return list.stream().filter(c -> c.loadId().equals(load)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("loaded_leg: each trip carries its own empty return")
    void loadedLeg() {
        List<Charged> c = DistributionCostCalculator.charge(TRIPS, Map.of("V1", d("2"), "V2", d("3")), "loaded_leg");
        assertThat(of(c, "L1").amount()).isEqualByComparingTo("400"); // (100 + 100) × 2
        assertThat(of(c, "L2").amount()).isEqualByComparingTo("100");
        assertThat(of(c, "L3").amount()).isEqualByComparingTo("120");
        assertThat(of(c, "L1").perTonneKm()).isEqualByComparingTo("0.2"); // 400 / 2000 t-km
    }

    @Test
    @DisplayName("pooled: the vehicle's empty km are shared by loaded tonne-km; vehicle total unchanged")
    void pooled() {
        List<Charged> c = DistributionCostCalculator.charge(TRIPS, Map.of("V1", d("2"), "V2", d("3")), "pooled");
        assertThat(of(c, "L1").emptyKm()).isEqualByComparingTo("66.7");
        assertThat(of(c, "L2").emptyKm()).isEqualByComparingTo("33.3");
        assertThat(of(c, "L1").amount().add(of(c, "L2").amount())).isEqualByComparingTo("500");
        assertThat(of(c, "L3").amount()).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("randomised: trip amounts always sum to rate × vehicle km; unknown policy refused")
    void exactSums() {
        Random r = new Random(11);
        for (int i = 0; i < 300; i++) {
            List<Trip> trips = new java.util.ArrayList<>();
            BigDecimal km = BigDecimal.ZERO;
            for (int t = 0; t < 1 + r.nextInt(6); t++) {
                BigDecimal loaded = BigDecimal.valueOf(r.nextInt(5000), 1);
                BigDecimal empty = BigDecimal.valueOf(r.nextInt(3000), 1);
                km = km.add(loaded).add(empty);
                trips.add(new Trip("L" + t, "V", loaded, empty, BigDecimal.valueOf(r.nextInt(400), 1)));
            }
            BigDecimal rate = BigDecimal.valueOf(1 + r.nextInt(900), 2);
            for (String policy : List.of("loaded_leg", "pooled")) {
                if (km.signum() == 0) {
                    continue;
                }
                BigDecimal sum = DistributionCostCalculator.charge(trips, Map.of("V", rate), policy).stream()
                        .map(Charged::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(sum).isEqualByComparingTo(rate.multiply(km).setScale(2, java.math.RoundingMode.HALF_UP));
            }
        }
        assertThatThrownBy(() -> DistributionCostCalculator.charge(TRIPS, Map.of("V1", d("1"), "V2", d("1")), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("transporter freight: per m³, per tonne-km and the minimum charge")
    void freight() {
        assertThat(FreightCalculator.amount("per_tonne_km", d("0.5"), d("20"), d("30"), null, null))
                .isEqualByComparingTo("300");
        assertThat(FreightCalculator.amount("per_m3", d("12"), null, null, d("8"), null)).isEqualByComparingTo("96");
        assertThat(FreightCalculator.amount("per_tonne", d("10"), d("3"), null, null, d("50")))
                .isEqualByComparingTo("50");
        assertThat(FreightCalculator.amount("per_tonne", d("10"), d("30"), null, null, d("50")))
                .isEqualByComparingTo("300");
        assertThatThrownBy(() -> FreightCalculator.amount("per_tonne_km", d("1"), d("2"), null, null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FreightCalculator.amount("per_m3", d("1"), null, null, null, null))
                .isInstanceOf(ApiException.class);
    }
}
