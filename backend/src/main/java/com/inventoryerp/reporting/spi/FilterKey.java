package com.inventoryerp.reporting.spi;

/** Filters a report accepts; the viewer shows only these, and the engine refuses the others when set. */
public enum FilterKey {
    /** {@code period} preset or {@code from} / {@code to}. */
    PERIOD,
    /** {@code asOf} (point-in-time reports). */
    AS_OF,
    BRANCH,
    COST_CENTER,
    PARTNER,
    ACCOUNT,
    JOURNAL,
    SOURCE,
    /** Analytic dimensions on the journal line ({@code dimensions.projectId}, ...). */
    DIMENSION,
    POSTING_STATUS,
    /** Comparative columns (prior period, prior year, variance). */
    COMPARE,
    /** Financial statement layout code. */
    LAYOUT
}
