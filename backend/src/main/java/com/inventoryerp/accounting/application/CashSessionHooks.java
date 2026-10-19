package com.inventoryerp.accounting.application;

/**
 * Hooks the POS module registers into the cash-session service without a module cycle (Nest
 * {@code registerStockPostingDrainer} and the lazily imported {@code PosReportsService.zReport}).
 */
public interface CashSessionHooks {

    /** Post this shift's queued stock deductions now (best-effort). */
    default void drainStockPostings(String cashSessionId) {}

    /** Generate (and persist) the Z-report snapshot of a closed session; {@code false} when unavailable. */
    default boolean generateZReport(String cashSessionId) {
        return false;
    }
}
