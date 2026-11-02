package com.inventoryerp.reporting.spi;

import java.time.LocalDate;

/**
 * A resolved column period. {@code from} is {@code null} for a point-in-time (balance) column: everything up to and
 * including {@code to}.
 */
public record ReportPeriod(String key, String label, LocalDate from, LocalDate to) {}
