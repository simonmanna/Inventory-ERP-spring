package com.inventoryerp.kernel.common;

import java.util.function.Supplier;

/**
 * Phase I2 (owner decision 1, ADR-S072): marks work replaying a sale an offline device already completed (the
 * {@code sale.checkout} sync op). Such a sale is kept even when a unit of one of its lines cannot be converted to the
 * product's stock unit: the line keeps its unit without a factor and its stock posting is held (retried, then
 * quarantined for an operator) instead of the sale being refused. Online checkouts never run inside this scope.
 */
public final class OfflineReplay {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private OfflineReplay() {}

    /** True while replaying an offline sale on this thread. */
    public static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** Run {@code body} as the replay of an offline sale. */
    public static <T> T run(Supplier<T> body) {
        Boolean previous = ACTIVE.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }
}
