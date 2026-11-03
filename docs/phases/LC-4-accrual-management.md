# LC-4 — Landed cost accrual management (done-report)

- Plan: [`docs/roadmap/landed-cost-plan.md`](../roadmap/landed-cost-plan.md) § LC-4 · ADR: [ADR-S053](../adr/ADR-S053-landed-cost-erp-grade.md) § LC-4
- Migration: `V294__lc4_accrual_management.sql`

| Plan item | Test (`LandedCostAccrualManagementIT`) |
|---|---|
| Rule columns, fixed precedence, overlap rejected, period by receipt date | `rulePeriodsAndPrecedence` |
| Per-kg (and per-unit) with UoM / weight, missing weight listed, foreign currency at the receipt date | `perKgInForeignCurrency` |
| FX vs estimate variance kept apart, nothing from FX in stock | `perKgInForeignCurrency` |
| Partial clearing 1000 → 600 → 250 → 150, remainder 0, exact books | `partialClears` |
| Over-clearing refused; `close` unwinds the remainder; reverse refused once partly cleared | `overClearingRefused` |
| Monitor (bucket, overdue, settlement date) | `monitor` |

Changed existing test: `LandedCostAuditIT` case 5 closes the accrual when the actual is below the estimate (the
remainder is no longer cleared implicitly); its asserted books are unchanged.

UI (rules page, monitoring tab) is delivered with LC-5.
