/**
 * Financial reporting engine (FR-0, ADR-S043): one library of accounting reports in 15 categories with shared filters,
 * period presets, comparative columns, drill-down to journal lines and source documents, configurable financial
 * statement layouts, saved views, scheduled delivery, CSV / XLSX / PDF export and a reproducible run log.
 * Spec: docs/spec/reporting.md.
 *
 * <p>Reports read posted ledger lines; nothing in this module posts. Other modules contribute engine reports through
 * {@code reporting.spi.ReportProvider}; reports that keep their own screens are listed in the catalogue as links.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Reporting")
package com.inventoryerp.reporting;
