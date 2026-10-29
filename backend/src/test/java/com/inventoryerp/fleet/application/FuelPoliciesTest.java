package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.fleet.application.FuelEfficiencyPolicy.Band;
import com.inventoryerp.fleet.application.FuelEfficiencyPolicy.Efficiency;
import com.inventoryerp.fleet.application.FuelEfficiencyPolicy.Fill;
import com.inventoryerp.fleet.application.FuelEfficiencyPolicy.Flag;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fleet 1.5 rules: full-to-full efficiency, anomaly flags, fuel-card number masking and card limit periods. */
class FuelPoliciesTest {

    static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    static Fill km(String id, String litres, boolean full, String odo) {
        return new Fill(id, d(litres), full, d(odo), null);
    }

    static Fill hours(String id, String litres, boolean full, String h) {
        return new Fill(id, d(litres), full, null, d(h));
    }

    static final Band TIPPER = new Band(d("25"), d("60"), null, null);
    static final Band EXCAVATOR = new Band(null, null, d("8"), d("35"));

    @Test
    @DisplayName("efficiency: first full fill has none; partial fills add to the next full-to-full segment")
    void fullToFull() {
        List<Fill> chain = List.of(km("a", "100", true, "10000"), km("b", "50", false, "10200"),
                km("c", "100", true, "10500"));
        assertThat(FuelEfficiencyPolicy.efficiency(chain, 0)).isEqualTo(Efficiency.NONE);
        assertThat(FuelEfficiencyPolicy.efficiency(chain, 1)).isEqualTo(Efficiency.NONE);
        Efficiency e = FuelEfficiencyPolicy.efficiency(chain, 2);
        assertThat(e.previousFullId()).isEqualTo("a");
        assertThat(e.segmentLitres()).isEqualByComparingTo("150");
        assertThat(e.distanceKm()).isEqualByComparingTo("500");
        assertThat(e.lPer100Km()).isEqualByComparingTo("30.000");
        assertThat(e.lPerHour()).isNull();
    }

    @Test
    @DisplayName("efficiency: litres per engine hour for plant; zero distance gives no figure")
    void hoursAndZeroDistance() {
        List<Fill> plant = List.of(hours("a", "200", true, "1000"), hours("b", "180", true, "1012"));
        Efficiency e = FuelEfficiencyPolicy.efficiency(plant, 1);
        assertThat(e.hoursRun()).isEqualByComparingTo("12");
        assertThat(e.lPerHour()).isEqualByComparingTo("15.000");
        assertThat(e.lPer100Km()).isNull();
        List<Fill> still = List.of(km("a", "100", true, "500"), km("b", "40", true, "500"));
        Efficiency z = FuelEfficiencyPolicy.efficiency(still, 1);
        assertThat(z.distanceKm()).isEqualByComparingTo("0");
        assertThat(z.lPer100Km()).isNull();
        // Rounding is HALF_UP to 3 places: 100 L over 300 km = 33.333 L/100 km.
        assertThat(FuelEfficiencyPolicy.efficiency(List.of(km("a", "1", true, "0"), km("b", "100", true, "300")), 1)
                .lPer100Km()).isEqualByComparingTo("33.333");
    }

    @Test
    @DisplayName("flags: a fill within the band and tank size raises nothing")
    void noFlags() {
        List<Fill> chain = List.of(km("a", "100", true, "10000"), km("b", "120", true, "10300"));
        Efficiency e = FuelEfficiencyPolicy.efficiency(chain, 1);
        assertThat(FuelEfficiencyPolicy.flags(chain, 1, e, TIPPER, d("400"), d("2"), null)).isEmpty();
    }

    @Test
    @DisplayName("flags: consumption above / below the class band (L/100 km and L/h)")
    void bands() {
        List<Fill> high = List.of(km("a", "100", true, "10000"), km("b", "100", true, "10100"));
        List<Flag> h = FuelEfficiencyPolicy.flags(high, 1, FuelEfficiencyPolicy.efficiency(high, 1), TIPPER, null,
                null, null);
        assertThat(h).extracting(Flag::type).containsExactly(FuelEfficiencyPolicy.HIGH_CONSUMPTION);
        assertThat(h.getFirst().observed()).isEqualByComparingTo("100");
        assertThat(h.getFirst().expectedMax()).isEqualByComparingTo("60");
        assertThat(h.getFirst().detail()).isEqualTo("Consumption above the class band: 100 L/100 km > 60 L/100 km");

        List<Fill> low = List.of(km("a", "100", true, "10000"), km("b", "20", true, "11000"));
        assertThat(FuelEfficiencyPolicy.flags(low, 1, FuelEfficiencyPolicy.efficiency(low, 1), TIPPER, null, null,
                null)).extracting(Flag::type).containsExactly(FuelEfficiencyPolicy.LOW_CONSUMPTION);

        List<Fill> plant = List.of(hours("a", "50", true, "100"), hours("b", "400", true, "110"));
        List<Flag> p = FuelEfficiencyPolicy.flags(plant, 1, FuelEfficiencyPolicy.efficiency(plant, 1), EXCAVATOR, null,
                null, null);
        assertThat(p).extracting(Flag::type).containsExactly(FuelEfficiencyPolicy.HIGH_CONSUMPTION);
        assertThat(p.getFirst().detail()).contains("40 L/h > 35 L/h");
        // No band configured: nothing to compare with.
        assertThat(FuelEfficiencyPolicy.flags(high, 1, FuelEfficiencyPolicy.efficiency(high, 1), Band.NONE, null,
                null, null)).isEmpty();
    }

    @Test
    @DisplayName("flags: overfill beyond tank size + tolerance")
    void overfill() {
        assertThat(FuelEfficiencyPolicy.overfill(d("306"), d("300"), d("2"))).isNull();
        Flag f = FuelEfficiencyPolicy.overfill(d("306.5"), d("300"), d("2"));
        assertThat(f.type()).isEqualTo(FuelEfficiencyPolicy.OVERFILL);
        assertThat(f.detail()).isEqualTo("Fuelled 306.5 L into a 300 L tank");
        assertThat(f.expectedMax()).isEqualByComparingTo("306");
        assertThat(FuelEfficiencyPolicy.overfill(d("301"), d("300"), null)).isNotNull();
        assertThat(FuelEfficiencyPolicy.overfill(d("900"), null, d("2"))).isNull();
    }

    @Test
    @DisplayName("flags: meter not above the previous log, or refused by the meter rule")
    void meterNotIncreasing() {
        List<Fill> same = List.of(km("a", "100", true, "10500"), km("b", "100", true, "10500"));
        List<Flag> f = FuelEfficiencyPolicy.flags(same, 1, FuelEfficiencyPolicy.efficiency(same, 1), TIPPER, null,
                null, null);
        assertThat(f).extracting(Flag::type).containsExactly(FuelEfficiencyPolicy.METER_NOT_INCREASING);
        assertThat(f.getFirst().detail()).isEqualTo(
                "Odometer 10500 km is not above the previous fuel log's 10500 km");

        // The previous log with a meter is used even when the one in between has none.
        List<Fill> gap = List.of(hours("a", "50", true, "300"), new Fill("b", d("10"), false, null, null),
                hours("c", "50", true, "290"));
        assertThat(FuelEfficiencyPolicy.flags(gap, 2, FuelEfficiencyPolicy.efficiency(gap, 2), Band.NONE, null, null,
                null).getFirst().detail()).isEqualTo("Engine hours 290 h are not above the previous fuel log's 300 h");

        List<Fill> first = List.of(km("a", "100", true, "9000"));
        List<Flag> refused = FuelEfficiencyPolicy.flags(first, 0, Efficiency.NONE, TIPPER, null, null,
                "Odometer reading 9000 km is lower than the previous reading of 9500 km on 2026-10-01.");
        assertThat(refused).extracting(Flag::detail).containsExactly(
                "Odometer reading 9000 km is lower than the previous reading of 9500 km on 2026-10-01.");
        assertThat(FuelEfficiencyPolicy.CHAIN_TYPES).doesNotContain(FuelEfficiencyPolicy.CARD_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("fuel card numbers: normalised, masked, keyed hash; invalid numbers refused")
    void cardNumbers() {
        String n = FuelCardNumbers.normalize(" 7000-1234 5678 9012 ");
        assertThat(n).isEqualTo("7000123456789012");
        assertThat(FuelCardNumbers.mask(n)).isEqualTo("**** 9012");
        assertThat(FuelCardNumbers.last4(n)).isEqualTo("9012");
        assertThat(FuelCardNumbers.hash("org-a", n)).hasSize(64).isEqualTo(FuelCardNumbers.hash("org-a", n))
                .isNotEqualTo(FuelCardNumbers.hash("org-b", n)).doesNotContain("9012");
        assertThatThrownBy(() -> FuelCardNumbers.normalize("12")).isInstanceOf(ApiException.class)
                .hasMessage("A fuel card number has 4 to 32 letters or digits");
        assertThatThrownBy(() -> FuelCardNumbers.normalize("1234#5678")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("card limit periods: day, ISO week from Monday, calendar month")
    void limitPeriods() {
        LocalDate wed = LocalDate.of(2026, 10, 28);
        assertThat(FleetFuelCardService.periodStart("daily", wed)).isEqualTo(wed);
        assertThat(FleetFuelCardService.periodStart("weekly", wed)).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(FleetFuelCardService.periodStart("monthly", wed)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(FleetFuelCardService.periodEnd("weekly", LocalDate.of(2026, 10, 26)))
                .isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(FleetFuelCardService.periodEnd("monthly", LocalDate.of(2026, 10, 1)))
                .isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(FleetFuelCardService.periodEnd("daily", wed)).isEqualTo(wed.plusDays(1));
    }
}
