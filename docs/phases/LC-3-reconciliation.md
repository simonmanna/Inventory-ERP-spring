# LC-3 — Landed cost reconciliation (done-report)

- Plan: [`docs/roadmap/landed-cost-plan.md`](../roadmap/landed-cost-plan.md) § LC-3 and "Final acceptance" · ADR: [ADR-S053](../adr/ADR-S053-landed-cost-erp-grade.md) § LC-3

## Delivered

| Plan item | Where | Test |
|---|---|---|
| Six reconciliation rows with drill-down | `LandedCostReconciliationService.reconciliation`, `GET /procurement/landed-cost-reports/reconciliation` | `LandedCostEndToEndIT` (all rows unchanged through the chain) |
| Classification check | `classification` | `LandedCostEndToEndIT` |
| Location check | `locationCheck` | `LandedCostEndToEndIT` |
| Exceptions and parked 1396 amounts | `exceptions`, 1396 drill-down (parked flag) | `LandedCostLineageIT` (exception lifecycle) |
| Trace chain (purchase and production), tenancy | `trace`, `GET /procurement/landed-cost-reports/trace` | `businessChain`, `tenantScope` (404 for another org) |
| Period-close checklist item | `PeriodCloseChecklistService` (`landed_cost_lineage`) | `PeriodCloseChecklistIT` unchanged |
| Final acceptance `LandedCostEndToEndIT` | — | green (deviation on the FG leg in the ADR) |

## Not in LC-3

- The reconciliation tab, exception list and trace tree in the web app → LC-5 (voucher UX).
