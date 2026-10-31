/**
 * Management dashboards (ENABLE_* of the modules they read, Track C Phase 5, requirements section 10): quarry,
 * aggregates, blocks, inventory, procurement, sales, fleet and delivery, maintenance, finance and branch KPIs with
 * common filters, drill-down to documents and role-based visibility. Read only: it writes no table, publishes no event,
 * and nothing depends on it. It calls the report services of the modules it summarises through their {@code application}
 * APIs and reads their tables with plain SQL where a report does not exist.
 *
 * <p>Spec: docs/spec/analytics-kpis.md, design: docs/adr/ADR-S020-analytics-dashboards.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Analytics")
package com.inventoryerp.analytics;
