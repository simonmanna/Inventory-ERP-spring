package com.inventoryerp.reporting.spi;

import java.math.BigDecimal;

/**
 * Subledger-to-GL reconciliation as a check other modules can run (ADR-S083 C2 post-restore reconciliation): the
 * {@code ar.reconciliation} / {@code ap.reconciliation} engine reports as of today, reduced to their outcome.
 */
public interface SubledgerReconciliation {

    /** {@code side} AR or AP; {@code reconciles} = the subledger total equals the control accounts and every partner ties. */
    record Result(String side, boolean reconciles, BigDecimal subledger, BigDecimal gl, String detail) {}

    /** Runs the reconciliation for {@code organizationId} without a user (all branches). */
    Result reconcile(String organizationId, String side);
}
