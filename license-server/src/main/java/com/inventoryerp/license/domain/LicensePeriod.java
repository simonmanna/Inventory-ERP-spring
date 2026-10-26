package com.inventoryerp.license.domain;

import java.time.Duration;

/**
 * What the customer bought a unit of. The value is what the {@code plan.period} CHECK constraint and
 * the licence token's {@code lic.period} claim carry.
 *
 * @see com.inventoryerp.license.application.LicensePeriods
 */
public enum LicensePeriod {

    /** Pay per day — pre-paid shops and short events. Checked in hourly, one day of grace. */
    DAY(Duration.ofHours(1), 1),

    /** The ordinary subscription. */
    MONTH(Duration.ofHours(6), 7),

    /** Annual, usually discounted. */
    YEAR(Duration.ofHours(24), 14),

    /** Time-boxed evaluation; {@code periodCount} is a number of days. No grace. */
    TRIAL(Duration.ofHours(6), 0),

    /** Bought outright: never expires, and heartbeats carry telemetry only. */
    PERPETUAL(Duration.ofHours(24), 0);

    private final Duration heartbeatInterval;
    private final int defaultGraceDays;

    LicensePeriod(Duration heartbeatInterval, int defaultGraceDays) {
        this.heartbeatInterval = heartbeatInterval;
        this.defaultGraceDays = defaultGraceDays;
    }

    /** How often an install of this plan should check in. */
    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    public int defaultGraceDays() {
        return defaultGraceDays;
    }

    public boolean isPerpetual() {
        return this == PERPETUAL;
    }

    public static LicensePeriod of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("A period is required");
        }
        for (LicensePeriod p : values()) {
            if (p.name().equalsIgnoreCase(value)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown period '" + value + "'");
    }
}
