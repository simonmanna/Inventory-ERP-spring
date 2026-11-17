package com.inventoryerp.intercompany.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.intercompany.application.IntercompanyConsolidationService.RatePoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Review R4: the P&amp;L translation rate is time-weighted over the period, carries in the rate in force at the start,
 * prefers the consolidating organization's own rates over shared ones (as {@code CurrencyService.findRate} does) and
 * normalises rows stored for the inverse pair.
 */
class ConsolidationAverageRateTest {

    static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    static final Instant TO = Instant.parse("2026-07-01T00:00:00Z"); // 30 days

    static Instant day(String d) {
        return Instant.parse(d + "T00:00:00Z");
    }

    static BigDecimal avg(List<RatePoint> points) {
        BigDecimal r = IntercompanyConsolidationService.timeWeightedRate(points, FROM, TO);
        return r == null ? null : r.setScale(4, RoundingMode.HALF_UP);
    }

    @Test
    void weightsEachRateByTheDaysItWasInForceAndCarriesTheOpeningRateIn() {
        // 3,600 from before the period for 20 days, then 3,800 for 10 days: (20 × 3600 + 10 × 3800) / 30.
        // The unweighted average of the rows dated in the period would be 3,800.
        BigDecimal r = avg(List.of(new RatePoint(day("2026-05-15"), new BigDecimal("3600"), true, false),
                new RatePoint(day("2026-06-21"), new BigDecimal("3800"), true, false)));
        assertThat(r).isEqualByComparingTo("3666.6667");
    }

    @Test
    void ownRatesWinOverSharedOnesEvenWhenOlder() {
        BigDecimal r = avg(List.of(new RatePoint(day("2026-05-01"), new BigDecimal("3500"), true, true),
                new RatePoint(day("2026-06-11"), new BigDecimal("4000"), true, false)));
        assertThat(r).isEqualByComparingTo("3500");
    }

    @Test
    void inverseRowsAreNormalisedAndTheDirectPairTakesPrecedence() {
        // 1 UGX = 0.00025 USD (i.e. 4,000) until a direct 3,000 row on day 16: 15 days each.
        BigDecimal r = avg(List.of(new RatePoint(day("2026-05-01"), new BigDecimal("0.00025"), false, false),
                new RatePoint(day("2026-06-16"), new BigDecimal("3000"), true, false)));
        assertThat(r).isEqualByComparingTo("3500");
    }

    @Test
    void stretchesWithoutARateCarryNoWeight() {
        // nothing in force until day 11, then 3,700 for the remaining 20 days
        assertThat(avg(List.of(new RatePoint(day("2026-06-11"), new BigDecimal("3700"), true, false))))
                .isEqualByComparingTo("3700");
    }

    @Test
    void noRateInForceOrAnEmptyPeriodGivesNullSoTheClosingRateApplies() {
        assertThat(avg(List.of())).isNull();
        assertThat(IntercompanyConsolidationService.timeWeightedRate(List.of(new RatePoint(day("2026-05-01"),
                BigDecimal.TEN, true, true)), TO, TO)).isNull();
    }
}
