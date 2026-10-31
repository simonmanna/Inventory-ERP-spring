# ADR-S020: Analytics dashboards

- Status: proposed (2026-10-31). Built, **not compiled or run**.
- Related: Track C Phase 5, requirements §10 and §2.7, `V100__analytics.sql`, `docs/spec/analytics-kpis.md`,
  ADR-S017 (user data scope)

## Context

Requirements §10 lists KPIs for ten business areas, filterable, with drill-down and role-based visibility. Most figures
already exist in report services across `quarry`, `manufacturing`, `fleet`, `invoicing`, `procurement`, `inventory` and
`accounting`. Those reports take different filters, and most ignore `BranchScopeService`, because they were written for
users who see the whole organization. A dashboard built directly on them would show a restricted user every branch.

## Decision

1. **A new read-only module, `com.inventoryerp.analytics`.** It has no table, publishes no event, and nothing depends on
   it, so it cannot form a cycle. It may call the `application` API of the modules it summarises.
2. **Reuse the reports; widen them additively.** Where a report lacks a filter, the owning service gets an overload taking
   a scope (`QuarryReportService.ReportScope`, `CrushingReportService.RunScope`, `ManufacturingCostingService.MarginScope`,
   a branch list on `PrecastReportService` and `SupplierPerformanceService`, `FleetReportService.deliveryKpis`). The old
   signature delegates with no limit, so routes behave as before. Status rules stay in one place.
3. **Plain SQL only where no report fits**: stock value and availability, count and survey variances, procurement spend
   and price trend, open balances by branch, maintenance spend. The spec names the source of every KPI.
4. **One gate for scope.** `AnalyticsViews.view` resolves every request into an `AnalyticsView` holding the period and the
   branch list; services read only that. A branch filter is checked against the user's grants. Sites, plants and
   locations are derived from it. Where the data has no branch at all (cash, OEE), a limited view omits the figure and
   says so.
5. **One response shape.** `Dashboard` carries tiles, charts, tables, notes and which filters applied. The server sends
   keys and formats, the web holds labels, so one generic screen renders all ten dashboards and i18n stays in the web.
6. **Filters are never dropped silently.** Each dashboard declares what it honours; anything else comes back in
   `ignoredFilters`.
7. **Role-based visibility by permission.** `analytics:view` plus one per dashboard, enforced per route. Module-gated
   dashboards use `@RequiresModule`.
8. **`manufacturing.application` becomes a named interface**, as in `quarry` and `fleet`, because analytics needs its
   report services.

## Consequences

- A KPI follows its report: a fix there reaches the dashboard, and so does a regression.
- Some dashboards run several reports per request, and inventory and fleet run reports per branch or location in a
  limited view. Phase 6 should time them on real volumes. There is no cache or read model.
- Labels live in `web/src/pages/analytics/labels.ts`, so a new tile needs a web change.
- Dashboards are fixed: only filters are configurable. Requirement row 164 stays partial.
- Any module may now call manufacturing's application services; that needs review discipline.
