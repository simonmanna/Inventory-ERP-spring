package com.inventoryerp.kernel.common;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * ADR-S088: the business time of the stock movements made while a block runs. The offline sync path binds the op's
 * {@code occurredAt} here, so every stock move of the op (direct stock, transfers, waste, goods receipts, approvals of
 * offline adjustments) records the device time as its {@code effectiveAt} without each service in between carrying it.
 * A move that names its own effective time ({@code StockMove.effectiveAt}) wins over the bound one.
 *
 * <p>{@code offline} marks work recorded on a device: it is exempt from the back-office backdating limit and from a
 * count freeze (it already happened; the count reconciliation handles it).
 */
public final class EffectiveTime {

    /** The bound business time and whether it comes from offline device work. */
    public record Context(Instant at, boolean offline) {}

    private static final ScopedValue<Context> CURRENT = ScopedValue.newInstance();

    private EffectiveTime() {}

    /** The bound context, or null outside any binding. */
    public static Context current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /** Run {@code body} with {@code at} (null = none) bound as the business time of offline device work. */
    public static <T> T offline(Instant at, Supplier<T> body) {
        return ScopedValue.where(CURRENT, new Context(at, true)).call(body::get);
    }

    /**
     * Run {@code body} as back-office work even inside an offline binding (no bound time, not offline): for example
     * the POS stock postings a count submit drains while it runs inside a device's count op.
     */
    public static <T> T backOffice(Supplier<T> body) {
        return ScopedValue.where(CURRENT, new Context(null, false)).call(body::get);
    }

    /** {@link #offline(Instant, Supplier)} without a result. */
    public static void offlineRun(Instant at, Runnable body) {
        ScopedValue.where(CURRENT, new Context(at, true)).run(body);
    }
}
