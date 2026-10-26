package com.inventoryerp.kernel.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * This is the code that can stop a shop trading, so every branch and every boundary second is
 * pinned here.
 */
class LicenseStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final Instant INSTALLED = Instant.parse("2026-06-01T00:00:00Z");
    private static final String FINGERPRINT = "a".repeat(64);

    /** A licence that is comfortably inside its term unless a test moves something. */
    private LicenseClaims claims(String status, Instant expiresAt, Instant graceUntil) {
        return claims(status, expiresAt, graceUntil, FINGERPRINT, false);
    }

    private LicenseClaims claims(String status, Instant expiresAt, Instant graceUntil, String fingerprint,
            boolean trial) {
        return new LicenseClaims("inventoryerp-license", "ERP-2345-6789-ABCD-0000", fingerprint, "act-1",
                graceUntil, "Acme Cafe Ltd", "cust-1", "pro-monthly", "Pro (monthly)", "MONTH", 1,
                status, trial, Instant.parse("2026-05-15T12:00:00Z"), expiresAt, graceUntil, 7, 21_600L,
                NOW.plus(Duration.ofHours(6)), Set.of("crm", "accounting"), 5, 25, 1, false, NOW);
    }

    private LicenseState evaluate(LicenseClaims claims, Instant now) {
        return evaluate(claims, now, null);
    }

    private LicenseState evaluate(LicenseClaims claims, Instant now, Instant highWater) {
        return LicenseStateMachine.evaluate(claims, now, INSTALLED, highWater, 14, FINGERPRINT, null, null);
    }

    @Nested
    @DisplayName("no licence")
    class Unlicensed {

        @Test
        void isATrialInsideTheTrialWindow() {
            LicenseState state = evaluate(null, INSTALLED.plus(Duration.ofDays(3)));

            assertThat(state.mode()).isEqualTo(LicenseMode.TRIAL);
            assertThat(state.reason()).isEqualTo("trial");
            assertThat(state.allowsWrites()).isTrue();
            assertThat(state.licensed()).isFalse();
            assertThat(state.daysRemaining()).isEqualTo(11L);
        }

        @Test
        void aTrialGrantsEverythingSoNothingIsHiddenWhileEvaluating() {
            LicenseState state = evaluate(null, INSTALLED.plus(Duration.ofDays(1)));

            assertThat(state.features()).isEqualTo(LicenseFeatures.ALL);
            assertThat(state.grants(LicenseFeatures.HR)).isTrue();
        }

        @Test
        void becomesReadOnlyTheMomentTheTrialEnds() {
            Instant trialEnds = INSTALLED.plus(Duration.ofDays(14));

            assertThat(evaluate(null, trialEnds.minusSeconds(1)).mode()).isEqualTo(LicenseMode.TRIAL);
            assertThat(evaluate(null, trialEnds).mode()).isEqualTo(LicenseMode.UNLICENSED);
        }

        @Test
        void anExpiredTrialGrantsNothingAndRefusesWrites() {
            LicenseState state = evaluate(null, INSTALLED.plus(Duration.ofDays(30)));

            assertThat(state.mode()).isEqualTo(LicenseMode.UNLICENSED);
            assertThat(state.reason()).isEqualTo("trial_expired");
            assertThat(state.allowsWrites()).isFalse();
            assertThat(state.features()).isEmpty();
            assertThat(state.grants(LicenseFeatures.CRM)).isFalse();
        }

        @Test
        void aZeroDayTrialIsReadOnlyImmediately() {
            LicenseState state = LicenseStateMachine.evaluate(null, NOW, INSTALLED, null, 0, FINGERPRINT,
                    null, null);
            assertThat(state.mode()).isEqualTo(LicenseMode.UNLICENSED);
        }
    }

    @Nested
    @DisplayName("the dates")
    class Dates {

        @Test
        void insideTheTermIsActive() {
            LicenseState state = evaluate(claims("active", NOW.plus(Duration.ofDays(10)),
                    NOW.plus(Duration.ofDays(17))), NOW);

            assertThat(state.mode()).isEqualTo(LicenseMode.ACTIVE);
            assertThat(state.reason()).isEqualTo("active");
            assertThat(state.allowsWrites()).isTrue();
            assertThat(state.daysRemaining()).isEqualTo(10L);
        }

        @Test
        void theLastSecondOfTheTermIsStillActive() {
            Instant expiry = NOW.plus(Duration.ofDays(1));
            assertThat(evaluate(claims("active", expiry, expiry.plus(Duration.ofDays(7))),
                    expiry.minusSeconds(1)).mode()).isEqualTo(LicenseMode.ACTIVE);
        }

        @Test
        void theExpiryInstantItselfIsAlreadyGrace() {
            Instant expiry = NOW.plus(Duration.ofDays(1));
            LicenseState state = evaluate(claims("active", expiry, expiry.plus(Duration.ofDays(7))), expiry);

            assertThat(state.mode()).isEqualTo(LicenseMode.GRACE);
            assertThat(state.reason()).isEqualTo("grace");
            // The whole point of grace: a lapsed shop keeps trading while somebody sorts the invoice.
            assertThat(state.allowsWrites()).isTrue();
        }

        @Test
        void theLastSecondOfGraceStillWrites() {
            Instant expiry = NOW.minus(Duration.ofDays(1));
            Instant graceUntil = expiry.plus(Duration.ofDays(7));

            assertThat(evaluate(claims("active", expiry, graceUntil), graceUntil.minusSeconds(1))
                    .allowsWrites()).isTrue();
        }

        @Test
        void theEndOfGraceIsReadOnly() {
            Instant expiry = NOW.minus(Duration.ofDays(1));
            Instant graceUntil = expiry.plus(Duration.ofDays(7));
            LicenseState state = evaluate(claims("active", expiry, graceUntil), graceUntil);

            assertThat(state.mode()).isEqualTo(LicenseMode.READ_ONLY);
            assertThat(state.reason()).isEqualTo("expired");
            assertThat(state.allowsWrites()).isFalse();
        }

        @Test
        void zeroGraceGoesStraightFromActiveToReadOnly() {
            Instant expiry = NOW.plus(Duration.ofDays(1));
            LicenseClaims noGrace = claims("active", expiry, expiry);

            assertThat(evaluate(noGrace, expiry.minusSeconds(1)).mode()).isEqualTo(LicenseMode.ACTIVE);
            assertThat(evaluate(noGrace, expiry).mode()).isEqualTo(LicenseMode.READ_ONLY);
        }

        @Test
        void graceIsDerivedFromGraceDaysWhenTheTokenOmitsTheWindow() {
            Instant expiry = NOW.minus(Duration.ofDays(2));
            LicenseClaims withoutWindow = claims("active", expiry, null);

            LicenseState state = evaluate(withoutWindow, NOW);

            assertThat(state.graceUntil()).isEqualTo(expiry.plus(Duration.ofDays(7)));
            assertThat(state.mode()).isEqualTo(LicenseMode.GRACE);
        }

        @Test
        void aPerpetualLicenceNeverExpires() {
            LicenseState state = evaluate(claims("active", null, null),
                    NOW.plus(Duration.ofDays(365 * 40)));

            assertThat(state.mode()).isEqualTo(LicenseMode.ACTIVE);
            assertThat(state.reason()).isEqualTo("perpetual");
            assertThat(state.daysRemaining()).isNull();
        }

        @Test
        void aTrialLicenceInsideItsTermReportsItself() {
            LicenseState state = evaluate(claims("active", NOW.plus(Duration.ofDays(5)),
                    NOW.plus(Duration.ofDays(5)), FINGERPRINT, true), NOW);

            assertThat(state.mode()).isEqualTo(LicenseMode.ACTIVE);
            assertThat(state.reason()).isEqualTo("trial");
        }
    }

    @Nested
    @DisplayName("the authority's decisions")
    class Decisions {

        @Test
        void revokedBeatsAPerfectlyValidTerm() {
            LicenseState state = evaluate(claims("revoked", NOW.plus(Duration.ofDays(300)),
                    NOW.plus(Duration.ofDays(307))), NOW);

            assertThat(state.mode()).isEqualTo(LicenseMode.REVOKED);
            assertThat(state.reason()).isEqualTo("revoked");
            assertThat(state.allowsWrites()).isFalse();
        }

        @Test
        void suspendedIsReadOnlyButNotTerminal() {
            LicenseState state = evaluate(claims("suspended", NOW.plus(Duration.ofDays(300)), null), NOW);

            assertThat(state.mode()).isEqualTo(LicenseMode.READ_ONLY);
            assertThat(state.reason()).isEqualTo("suspended");
            assertThat(state.allowsWrites()).isFalse();
        }

        @Test
        void statusIsMatchedWithoutRegardToCase() {
            assertThat(evaluate(claims("REVOKED", NOW.plus(Duration.ofDays(9)), null), NOW).mode())
                    .isEqualTo(LicenseMode.REVOKED);
        }

        @Test
        void anExpiredStatusStillGoesByTheDates() {
            // The server marks a lapsed licence 'expired' as bookkeeping; the install had already
            // worked that out, and must not read the label as a harder state than the dates say.
            LicenseState state = evaluate(claims("expired", NOW.minus(Duration.ofDays(1)),
                    NOW.plus(Duration.ofDays(6))), NOW);

            assertThat(state.mode()).isEqualTo(LicenseMode.GRACE);
        }
    }

    @Nested
    @DisplayName("moved to other hardware")
    class MovedInstall {

        @Test
        void aFingerprintMismatchDegradesToGraceRatherThanBlocking() {
            // The usual cause is a database restored onto a new cluster: a recovery, not piracy.
            LicenseState state = evaluate(claims("active", NOW.plus(Duration.ofDays(10)),
                    NOW.plus(Duration.ofDays(17)), "b".repeat(64), false), NOW);

            assertThat(state.mode()).isEqualTo(LicenseMode.GRACE);
            assertThat(state.reason()).isEqualTo("fingerprint_mismatch");
            assertThat(state.allowsWrites()).isTrue();
        }

        @Test
        void aMismatchedAndSpentLicenceIsReadOnly() {
            Instant expiry = NOW.minus(Duration.ofDays(30));
            LicenseState state = evaluate(claims("active", expiry, expiry.plus(Duration.ofDays(7)),
                    "b".repeat(64), false), NOW);

            assertThat(state.mode()).isEqualTo(LicenseMode.READ_ONLY);
            assertThat(state.reason()).isEqualTo("fingerprint_mismatch");
        }

        @Test
        void aMismatchedPerpetualLicenceStaysInGraceIndefinitely() {
            LicenseState state = evaluate(claims("active", null, null, "b".repeat(64), false), NOW);

            assertThat(state.mode()).isEqualTo(LicenseMode.GRACE);
            assertThat(state.reason()).isEqualTo("fingerprint_mismatch");
        }
    }

    @Nested
    @DisplayName("the clock")
    class Clock {

        @Test
        void windingTheClockBackDoesNotBuyMoreTerm() {
            Instant expiry = NOW.minus(Duration.ofDays(30));
            Instant graceUntil = expiry.plus(Duration.ofDays(7));
            LicenseClaims spent = claims("active", expiry, graceUntil);

            // Local clock set to well before the licence expired, but the server was seen at NOW.
            LicenseState state = evaluate(spent, NOW.minus(Duration.ofDays(60)), NOW);

            assertThat(state.tamperSuspected()).isTrue();
            assertThat(state.mode()).isEqualTo(LicenseMode.READ_ONLY);
        }

        @Test
        void ordinaryDriftIsNotCalledTampering() {
            LicenseClaims live = claims("active", NOW.plus(Duration.ofDays(10)), null);

            LicenseState state = evaluate(live, NOW.minus(Duration.ofMinutes(20)), NOW);

            assertThat(state.tamperSuspected()).isFalse();
            assertThat(state.mode()).isEqualTo(LicenseMode.ACTIVE);
        }

        @Test
        void theToleranceBoundaryIsExact() {
            LicenseClaims live = claims("active", NOW.plus(Duration.ofDays(10)), null);
            Instant highWater = NOW;

            assertThat(evaluate(live, highWater.minus(LicenseStateMachine.CLOCK_TOLERANCE), highWater)
                    .tamperSuspected()).isFalse();
            assertThat(evaluate(live, highWater.minus(LicenseStateMachine.CLOCK_TOLERANCE).minusSeconds(1),
                    highWater).tamperSuspected()).isTrue();
        }

        @Test
        void windingTheClockForwardIsNotDefendedAgainstBecauseItOnlyCostsTheAttacker() {
            LicenseClaims live = claims("active", NOW.plus(Duration.ofDays(10)),
                    NOW.plus(Duration.ofDays(17)));

            LicenseState state = evaluate(live, NOW.plus(Duration.ofDays(100)), NOW);

            assertThat(state.tamperSuspected()).isFalse();
            assertThat(state.mode()).isEqualTo(LicenseMode.READ_ONLY);
        }

        @Test
        void aNeverOnlineInstallHasNoHighWaterMarkToJudgeAgainst() {
            LicenseClaims live = claims("active", NOW.plus(Duration.ofDays(10)), null);

            assertThat(evaluate(live, NOW.minus(Duration.ofDays(400)), null).tamperSuspected()).isFalse();
        }
    }

    @Nested
    @DisplayName("what the state carries")
    class Carried {

        @Test
        void exposesTheLicencesCapsAndFeatures() {
            LicenseState state = evaluate(claims("active", NOW.plus(Duration.ofDays(5)), null), NOW);

            assertThat(state.maxDevices()).isEqualTo(5);
            assertThat(state.maxUsers()).isEqualTo(25);
            assertThat(state.maxOrganizations()).isEqualTo(1);
            assertThat(state.plan()).isEqualTo("pro-monthly");
            assertThat(state.customer()).isEqualTo("Acme Cafe Ltd");
            assertThat(state.grants("crm")).isTrue();
            assertThat(state.grants("hr")).isFalse();
        }

        @Test
        void hasNoCapsWhenThereIsNoLicence() {
            LicenseState state = evaluate(null, NOW);

            assertThat(state.maxDevices()).isNull();
            assertThat(state.maxUsers()).isNull();
            assertThat(state.maxOrganizations()).isNull();
            assertThat(state.customer()).isNull();
        }

        @Test
        void onlyActiveNeedsNoWarning() {
            assertThat(LicenseMode.ACTIVE.warrantsWarning()).isFalse();
            for (LicenseMode mode : LicenseMode.values()) {
                if (mode != LicenseMode.ACTIVE) {
                    assertThat(mode.warrantsWarning()).as("%s warns", mode).isTrue();
                }
            }
        }

        @Test
        void onlyTrialActiveAndGraceAllowWrites() {
            assertThat(Set.of(LicenseMode.TRIAL, LicenseMode.ACTIVE, LicenseMode.GRACE))
                    .allMatch(LicenseMode::allowsWrites);
            assertThat(Set.of(LicenseMode.UNLICENSED, LicenseMode.READ_ONLY, LicenseMode.REVOKED))
                    .noneMatch(LicenseMode::allowsWrites);
        }
    }
}
