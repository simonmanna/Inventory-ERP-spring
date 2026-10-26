package com.inventoryerp.kernel.licensing;

import java.time.Instant;
import java.util.Set;

/**
 * What the licence means for this installation at one moment: the mode enforcement acts on, why,
 * and everything the licence page and the banner need.
 *
 * <p>A value object with no behaviour beyond asking it questions. It is computed by
 * {@link LicenseStateMachine} and cached for a minute, so the guard on every request is a map
 * lookup rather than a signature check.
 *
 * @param mode             what is allowed now
 * @param reason           machine-readable cause, e.g. {@code grace}, {@code revoked},
 *                         {@code trial_expired}, {@code fingerprint_mismatch}, {@code suspended}
 * @param claims           the verified licence, or {@code null} when none was ever activated
 * @param tamperSuspected  the local clock is behind the newest server time ever seen
 */
public record LicenseState(
        LicenseMode mode,
        String reason,
        LicenseClaims claims,
        Instant expiresAt,
        Instant graceUntil,
        Long daysRemaining,
        boolean tamperSuspected,
        String fingerprint,
        Instant lastCheckAt,
        String lastCheckError,
        Instant evaluatedAt) {

    public boolean allowsWrites() {
        return mode.allowsWrites();
    }

    public boolean licensed() {
        return claims != null;
    }

    /** Features the licence grants. A trial grants everything; an unlicensed install grants nothing. */
    public Set<String> features() {
        if (claims != null) {
            return claims.features();
        }
        return mode == LicenseMode.TRIAL ? LicenseFeatures.ALL : Set.of();
    }

    /**
     * Is this feature licensed? The licence is the ceiling — Developer Settings can switch a
     * licensed feature off, but can never switch an unlicensed one on.
     */
    public boolean grants(String feature) {
        return features().contains(feature);
    }

    public Integer maxDevices() {
        return claims == null ? null : claims.maxDevices();
    }

    public Integer maxUsers() {
        return claims == null ? null : claims.maxUsers();
    }

    public Integer maxOrganizations() {
        return claims == null ? null : claims.maxOrganizations();
    }

    public String plan() {
        return claims == null ? (mode == LicenseMode.TRIAL ? "trial" : null) : claims.plan();
    }

    public String customer() {
        return claims == null ? null : claims.customer();
    }

    /** When the install should next check in, or {@code null} if it never can (offline licence). */
    public Instant refreshAfter() {
        return claims == null ? null : claims.refreshAfter();
    }
}
