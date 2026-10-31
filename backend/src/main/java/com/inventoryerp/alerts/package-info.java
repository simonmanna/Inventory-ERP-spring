/**
 * Scheduled alerts and exception reports (Phase 5, ADR-S021): an hourly and a daily scan per organization, thresholds,
 * channels and recipients per alert type, one alert per subject and period. Spec: docs/spec/alerts.md.
 *
 * <p>This module depends on {@code kernel} only. Each owning module implements {@code alerts.spi.AlertSource} and keeps
 * its own query, so nothing here reads another module's tables and no module needs to expose its application layer.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Alerts")
package com.inventoryerp.alerts;
