package com.inventoryerp.manufacturing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Minimum-curing-days rule of a QC-gated stage (ADR-S014, Phase 2.4): the gate can pass only once
 * {@code curing start + minCuringDays × 24 h} has been reached. Pure, unit-tested.
 */
public final class CuringHold {

    private CuringHold() {}

    /** Earliest pass time, or null when the stage has no hold or nothing has entered curing yet. */
    public static Instant passDueAt(Map<String, Object> stage, Instant curingStart) {
        Object days = stage.get("minCuringDays");
        int d = days instanceof Number n ? n.intValue() : 0;
        return dueAt(curingStart, d);
    }

    /** {@code start + days}, or null when {@code days <= 0} or {@code start} is null. */
    public static Instant dueAt(Instant start, int days) {
        return start == null || days <= 0 ? null : start.plus(Duration.ofDays(days));
    }

    /** Whole days elapsed since {@code start} (0 before it). */
    public static long daysCured(Instant start, Instant now) {
        if (start == null || now.isBefore(start)) {
            return 0;
        }
        return Duration.between(start, now).toDays();
    }
}
