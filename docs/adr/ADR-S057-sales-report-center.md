# ADR-S057 — Sales report center

- Status: accepted (owner request, 2026-11-05).
- Spec: [`docs/spec/sales-reports.md`](../spec/sales-reports.md).
- Builds on: [ADR-S011](ADR-S011-sales-o2c.md) (sales O2C), [ADR-S056](ADR-S056-branch-cash-sales-central-invoicing.md)
  (branch cash sales as a channel).
- Extension: no NestJS counterpart. New read-only routes under `/sales/report-center/*`; no existing contract changes,
  no migration.

## Context

The owner asked for a complete sales reporting catalogue (12 categories, ~140 reports: performance, customers,
products, orders, quotations & pipeline, deliveries, invoices, collections, discounts & returns, team,
profitability, forecasting) under Sales → Reporting, each with graphical analytics, filters, drill-down, Excel / PDF
export and print. The existing `/sales/analytics/*` endpoints cover four views only, and each computes its own figures.

## Decision

1. **One reporting layer, one fact.** `SalesReportFacts` is the single sales fact: product lines of posted sales
   invoices and credit notes (credit notes negative), with customer, branch, salesperson (invoice → order owner →
   cash-sale seller → customer's salesperson), team, channel (sales order, branch cash sale, POS, direct invoice),
   product, category and unit. Every revenue report, KPI and the dashboard aggregate it in SQL, so totals agree.
2. **Actual cost.** Cost of sales comes from the inventory ledger: the invoice's own issue, else the branch cash
   sale's issue, else the sales order's delivery trips (`fleet_trip`); the product's cost price is only the fallback.
   Reports show the actual-cost coverage when it is below 100 %.
3. **Uniform result shape.** Every report returns `{columns, rows, totals, kpis, chart, chartData, notes}`. Rows carry
   `_route` (drill-through to a document) or `_drill` (drill-down into another report with filters). One web viewer
   renders all of them (table / chart / both), and the server exports Excel and CSV from the same result.
4. **Shared filter.** Period, as-of, branch, salesperson, customer, product, category, customer type, channel,
   status, group-by, dimension, comparison, measure, threshold, days and top-N. Unknown parameters are rejected.
5. **Accounting stays authoritative.** Sales reports show operational views of posted documents; ledger balances,
   tax returns and receivables ledgers remain Accounting reports. Reports say so in their notes.
6. **Permissions.** `sales:report:read` for the center; commission reports also need `sales:commission:read`.
   Read-only, tenant-scoped through `SalesDb`.

## Consequences

- New reports are one method plus a `def(...)` line in a `SalesReportSet`; the web picks them up from the catalog.
- Targets double as the sales budget (company-level targets, else the sum of salesperson targets) until a sales budget
  module exists.
- Branch data scopes (`BranchScopeService`) are not applied yet — same as the existing sales analytics; follow-up.
