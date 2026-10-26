package com.inventoryerp.kernel.licensing;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides what this installation may do. Pure: claims plus a clock in, a {@link LicenseState} out,
 * no database and no network — which is what makes it exhaustively testable, and it is tested
 * exhaustively, because this is the code that can stop a shop trading.
 *
 * <p>The order of the rules is the design:
 *
 * <ol>
 *   <li><b>Revoked beats everything.</b> It is the authority's only immediate kill switch.
 *   <li><b>Suspended is read-only but not terminal</b> — resuming restores the term untouched.
 *   <li><b>A fingerprint mismatch degrades to grace, never to a block.</b> The usual cause is a
 *       database restored onto new hardware, which is a recovery, not piracy. The install keeps
 *       working and asks to be re-activated.
 *   <li><b>Then the dates:</b> inside the term is {@code ACTIVE}, inside grace is {@code GRACE},
 *       past grace is {@code READ_ONLY}.
 *   <li><b>No licence at all</b> is a trial until the trial window closes, then read-only.
 * </ol>
 *
 * <p>A clock rolled back behind the newest server time ever seen is treated as tampering: the
 * high-water mark is used as "now" instead, so winding the clock back cannot buy more term. Winding
 * it <em>forward</em> is not defended against, and does not need to be — it only expires the licence
 * sooner, which is the attacker's loss.
 */
public final class LicenseStateMachine {

    /** Clock slack before a backwards clock is called tampering rather than drift. */
    static final Duration CLOCK_TOLERANCE = Duration.ofHours(1);

    private LicenseStateMachine() {}

    /**
     * @param claims        the verified licence, or {@code null} when none has been activated
     * @param now           the local clock
     * @param installedAt   when this installation first ran, for the trial window
     * @param highWater     newest {@code serverTime} ever seen, or {@code null} if never online
     * @param trialDays     how long an unlicensed install may run
     * @param fingerprint   this installation's fingerprint
     */
    public static LicenseState evaluate(LicenseClaims claims, Instant now, Instant installedAt,
            Instant highWater, int trialDays, String fingerprint, Instant lastCheckAt, String lastCheckError) {
        boolean tampered = highWater != null && now.isBefore(highWater.minus(CLOCK_TOLERANCE));
        Instant effectiveNow = tampered ? highWater : now;

        if (claims == null) {
            return unlicensed(effectiveNow, installedAt, trialDays, tampered, fingerprint, lastCheckAt,
                    lastCheckError);
        }

        Instant expiresAt = claims.expiresAt();
        Instant graceUntil = claims.graceUntil() != null
                ? claims.graceUntil()
                : (expiresAt == null ? null : expiresAt.plus(Duration.ofDays(claims.graceDays())));
        Long daysRemaining = expiresAt == null ? null : Duration.between(effectiveNow, expiresAt).toDays();

        LicenseMode mode;
        String reason;
        if ("revoked".equalsIgnoreCase(claims.status())) {
            mode = LicenseMode.REVOKED;
            reason = "revoked";
        } else if ("suspended".equalsIgnoreCase(claims.status())) {
            mode = LicenseMode.READ_ONLY;
            reason = "suspended";
        } else if (fingerprint != null && claims.fingerprint() != null
                && !fingerprint.equals(claims.fingerprint())) {
            // Past grace as well as on the wrong machine: the licence is spent either way.
            boolean spent = graceUntil != null && !effectiveNow.isBefore(graceUntil);
            mode = spent ? LicenseMode.READ_ONLY : LicenseMode.GRACE;
            reason = "fingerprint_mismatch";
        } else if (expiresAt == null) {
            mode = LicenseMode.ACTIVE;
            reason = "perpetual";
        } else if (effectiveNow.isBefore(expiresAt)) {
            mode = LicenseMode.ACTIVE;
            reason = claims.trial() ? "trial" : "active";
        } else if (graceUntil != null && effectiveNow.isBefore(graceUntil)) {
            mode = LicenseMode.GRACE;
            reason = "grace";
        } else {
            mode = LicenseMode.READ_ONLY;
            reason = "expired";
        }

        return new LicenseState(mode, reason, claims, expiresAt, graceUntil, daysRemaining, tampered,
                fingerprint, lastCheckAt, lastCheckError, effectiveNow);
    }

    private static LicenseState unlicensed(Instant now, Instant installedAt, int trialDays, boolean tampered,
            String fingerprint, Instant lastCheckAt, String lastCheckError) {
        Instant trialEnds = installedAt == null
                ? now
                : installedAt.plus(Duration.ofDays(Math.max(0, trialDays)));
        boolean inTrial = now.isBefore(trialEnds);
        long daysLeft = Duration.between(now, trialEnds).toDays();

        return new LicenseState(
                inTrial ? LicenseMode.TRIAL : LicenseMode.UNLICENSED,
                inTrial ? "trial" : "trial_expired",
                null,
                trialEnds,
                trialEnds,
                daysLeft,
                tampered,
                fingerprint,
                lastCheckAt,
                lastCheckError,
                now);
    }
}
