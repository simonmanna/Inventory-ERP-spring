package com.inventoryerp.license.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.license.domain.LicensePeriod;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Term arithmetic is what a customer is billed against, so every boundary is pinned here rather
 * than left to the database or to chance.
 */
class LicensePeriodsTest {

    private static final Instant JAN_31 = Instant.parse("2026-01-31T09:00:00Z");

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        void daysAreExactTwentyFourHourDays() {
            assertThat(LicensePeriods.expiryOf(JAN_31, LicensePeriod.DAY, 3))
                    .isEqualTo(Instant.parse("2026-02-03T09:00:00Z"));
        }

        @Test
        void trialCountsItsPeriodCountInDays() {
            assertThat(LicensePeriods.expiryOf(JAN_31, LicensePeriod.TRIAL, 14))
                    .isEqualTo(Instant.parse("2026-02-14T09:00:00Z"));
        }

        @Test
        void aMonthFromTheThirtyFirstClampsToTheEndOfAShortMonth() {
            assertThat(LicensePeriods.expiryOf(JAN_31, LicensePeriod.MONTH, 1))
                    .isEqualTo(Instant.parse("2026-02-28T09:00:00Z"));
        }

        @Test
        void aMonthFromTheThirtyFirstClampsToTheLeapDay() {
            assertThat(LicensePeriods.expiryOf(Instant.parse("2028-01-31T09:00:00Z"), LicensePeriod.MONTH, 1))
                    .isEqualTo(Instant.parse("2028-02-29T09:00:00Z"));
        }

        @Test
        void clampingDoesNotAccumulateAcrossMonths() {
            // 31 Jan + 2 months is 31 March, not 28 February + 1 month.
            assertThat(LicensePeriods.expiryOf(JAN_31, LicensePeriod.MONTH, 2))
                    .isEqualTo(Instant.parse("2026-03-31T09:00:00Z"));
        }

        @Test
        void aYearFromTheLeapDayClampsToTheTwentyEighth() {
            assertThat(LicensePeriods.expiryOf(Instant.parse("2028-02-29T09:00:00Z"), LicensePeriod.YEAR, 1))
                    .isEqualTo(Instant.parse("2029-02-28T09:00:00Z"));
        }

        @Test
        void aPerpetualLicenceHasNoExpiry() {
            assertThat(LicensePeriods.expiryOf(JAN_31, LicensePeriod.PERPETUAL, 1)).isNull();
        }

        @Test
        void aTermMustBeAtLeastOnePeriodLong() {
            assertThatThrownBy(() -> LicensePeriods.expiryOf(JAN_31, LicensePeriod.MONTH, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one period");
        }

        @Test
        void aStartDateIsRequired() {
            assertThatThrownBy(() -> LicensePeriods.expiryOf(null, LicensePeriod.MONTH, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("renewal")
    class Renewal {

        @Test
        void renewingEarlyKeepsTheDaysAlreadyPaidFor() {
            Instant now = Instant.parse("2026-03-01T00:00:00Z");
            Instant expiry = Instant.parse("2026-03-20T00:00:00Z");
            assertThat(LicensePeriods.renewalStart(now, expiry)).isEqualTo(expiry);
        }

        @Test
        void renewingLateStartsFromToday() {
            Instant now = Instant.parse("2026-04-10T00:00:00Z");
            Instant expiry = Instant.parse("2026-03-20T00:00:00Z");
            assertThat(LicensePeriods.renewalStart(now, expiry)).isEqualTo(now);
        }

        @Test
        void renewingAnUndatedLicenceStartsFromToday() {
            Instant now = Instant.parse("2026-04-10T00:00:00Z");
            assertThat(LicensePeriods.renewalStart(now, null)).isEqualTo(now);
        }

        @Test
        void renewingOnTheExpiryInstantStartsThere() {
            Instant at = Instant.parse("2026-03-20T00:00:00Z");
            assertThat(LicensePeriods.renewalStart(at, at)).isEqualTo(at);
        }
    }

    @Nested
    @DisplayName("grace and token lifetime")
    class Grace {

        @Test
        void graceExtendsTheTermByWholeDays() {
            Instant expiry = Instant.parse("2026-03-20T09:00:00Z");
            assertThat(LicensePeriods.graceUntil(expiry, 7))
                    .isEqualTo(Instant.parse("2026-03-27T09:00:00Z"));
        }

        @Test
        void zeroGraceEndsAtExpiry() {
            Instant expiry = Instant.parse("2026-03-20T09:00:00Z");
            assertThat(LicensePeriods.graceUntil(expiry, 0)).isEqualTo(expiry);
        }

        @Test
        void negativeGraceIsTreatedAsNone() {
            Instant expiry = Instant.parse("2026-03-20T09:00:00Z");
            assertThat(LicensePeriods.graceUntil(expiry, -5)).isEqualTo(expiry);
        }

        @Test
        void aPerpetualTermHasNoGraceWindow() {
            assertThat(LicensePeriods.graceUntil(null, 7)).isNull();
        }

        @Test
        void aTokenStaysVerifiableToTheEndOfGrace() {
            Instant now = Instant.parse("2026-03-01T00:00:00Z");
            Instant expiry = Instant.parse("2026-03-20T09:00:00Z");
            assertThat(LicensePeriods.tokenExpiry(expiry, 7, now))
                    .isEqualTo(Instant.parse("2026-03-27T09:00:00Z"));
        }

        @Test
        void aPerpetualTokenOutlivesTheProduct() {
            Instant now = Instant.parse("2026-03-01T00:00:00Z");
            assertThat(LicensePeriods.tokenExpiry(null, 0, now))
                    .isAfter(now.plus(Duration.ofDays(365 * 99)));
        }
    }

    @Nested
    @DisplayName("days remaining")
    class DaysRemaining {

        @Test
        void countsWholeDaysAhead() {
            assertThat(LicensePeriods.daysRemaining(
                    Instant.parse("2026-03-01T00:00:00Z"), Instant.parse("2026-03-08T12:00:00Z")))
                    .isEqualTo(7L);
        }

        @Test
        void goesNegativeOnceThePastIsPast() {
            assertThat(LicensePeriods.daysRemaining(
                    Instant.parse("2026-03-10T00:00:00Z"), Instant.parse("2026-03-08T00:00:00Z")))
                    .isEqualTo(-2L);
        }

        @Test
        void isNullForAPerpetualTerm() {
            assertThat(LicensePeriods.daysRemaining(Instant.now(), null)).isNull();
        }
    }
}
