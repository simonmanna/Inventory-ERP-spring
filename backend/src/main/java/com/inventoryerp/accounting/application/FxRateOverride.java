package com.inventoryerp.accounting.application;

/**
 * FX-2 B: the reason a caller gave for stating a transaction rate outside its company's rate-policy tolerance, for the
 * current request (HTTP header {@code X-Fx-Rate-Override-Reason}, bound by {@code FxRateOverrideFilter}). Read by
 * {@link CurrencyService} and stored on the entry's rate snapshot.
 */
public final class FxRateOverride {

    private static final ThreadLocal<String> REASON = new ThreadLocal<>();

    private FxRateOverride() {}

    /** The current request's override reason, or {@code null}. */
    public static String reason() {
        String r = REASON.get();
        return r == null || r.isBlank() ? null : r.trim();
    }

    /** Bind {@code reason} for the duration of {@code work}. */
    public static void with(String reason, Runnable work) {
        String previous = REASON.get();
        REASON.set(reason);
        try {
            work.run();
        } finally {
            if (previous == null) {
                REASON.remove();
            } else {
                REASON.set(previous);
            }
        }
    }
}
