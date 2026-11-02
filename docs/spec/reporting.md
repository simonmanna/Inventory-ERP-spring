# Reporting — financial report engine and library

Extension module (not in the Nest reference). Design: [ADR-S043](../adr/ADR-S043-financial-report-engine.md).
Roadmap: [financial-reporting-plan.md](../roadmap/financial-reporting-plan.md).

The library holds **130 engine reports** (run, filtered, compared, drilled, exported and scheduled by the engine) and
**92 catalogued screens** (existing reports that keep their own page; titled "(classic screen)" where an engine report
now covers the same ground).

## Routes

| Verb | Path | Permission | Purpose |
|---|---|---|---|
| GET | `/reports/library` | `report_library:read` | 15 categories with the reports the caller may run (engine reports and links) |
| GET | `/reports/library/{id}` | `report_library:read` + report's | Definition (filters, options), period presets, compare options, layouts, export formats, `canSchedule` |
| POST | `/reports/run/{id}` | `report_library:read` + report's | Run with a `ReportRequest` body; returns header, columns, rows, totals, checks, meta, periods |
| POST | `/reports/run/{id}/export?format=csv\|xlsx\|pdf` | same | Same run as a file |
| GET | `/reports/runs?reportId=` | `report_library:read` | Recent runs (own; everyone's with `manage`) |
| POST | `/reports/runs/{runId}/verify` | `report_library:read` | Re-run and compare the result hash |
| GET/POST/DELETE | `/reports/views[?reportId=][/{id}]` | `report_library:read` (`manage` to share) | Saved views |
| GET/POST/PATCH/DELETE | `/reports/schedules[/{id}]`, POST `/{id}/run` | `report_library:schedule` | Scheduled delivery |
| GET | `/reports/fs-layouts[/{code}]` | `report_library:read` | Statement layouts (PL, BS, CF) |
| POST/PUT/DELETE | `/reports/fs-layouts[/{code}]`, POST `/{code}/reset` | `report_library:manage` | Edit layouts |

## Request (`ReportRequest`)

`period` (TODAY, YESTERDAY, THIS_MONTH, LAST_MONTH, THIS_QUARTER, LAST_QUARTER, THIS_YEAR, LAST_YEAR, FISCAL_YTD,
LAST_FISCAL_YEAR), `from`, `to`, `asOf`, `branchIds`, `costCenterIds`, `partnerIds`, `accountIds`, `journalIds`,
`sourceTypes`, `dimensions` (`{"projectId": ["…"]}`), `postingStatus` (POSTED | ALL), `compare` (PRIOR_PERIOD,
PRIOR_YEAR, BUDGET, VARIANCE, VARIANCE_PCT), `layoutCode`, `page`, `limit`, `options` (report-specific, see each
definition's `options`). A filter or option the report does not accept → 400.

## Engine reports

| Category | Reports |
|---|---|
| 01 Financial statements | `fs.profit-and-loss`, `fs.balance-sheet`, `fs.cash-flow` (indirect, layout), `fs.cash-flow-direct`, `fs.trial-balance`, `fs.changes-in-equity` |
| 02 General ledger | `gl.general-ledger`, `gl.journal-lines`, `gl.journal-register`, `gl.account-activity` |
| 03 Receivables | `ar.aging-summary`, `ar.aging-detail`, `ar.open-invoices`, `ar.overdue`, `ar.customer-ledger`, `ar.customer-statement`, `ar.summary`, `ar.collections`, `ar.payment-history`, `ar.credit-utilization`, `ar.unallocated-receipts`, `ar.customer-advances`, `ar.credit-notes`, `ar.bad-debt`, `ar.reconciliation` |
| 04 Payables | `ap.aging-summary`, `ap.aging-detail`, `ap.open-bills`, `ap.supplier-ledger`, `ap.supplier-statement`, `ap.payment-history`, `ap.upcoming-payments`, `ap.payment-forecast`, `ap.unallocated-payments`, `ap.debit-notes`, `ap.liability-summary`, `ap.reconciliation` |
| 05 Cash & bank | `cash.cash-book`, `cash.bank-book`, `cash.mobile-money-book`, `cash.petty-cash`, `cash.position`, `cash.forecast`, `cash.receipts`, `cash.payments`, `cash.payment-methods`, `cash.transfers`, `cash.undeposited`, `cash.bank-charges` |
| 06 Tax | `tax.vat-summary`, `tax.vat-detail`, `tax.output-vat`, `tax.input-vat`, `tax.vat-return` (URA boxes 1–9), `tax.audit-trail`, `tax.reconciliation`, `tax.withholding`, `tax.wht-certificates`, `tax.paye`, `tax.liability`, `tax.payments`, `tax.efris` |
| 07 Fixed assets | `fa.register`, `fa.depreciation-schedule`, `fa.acquisitions`, `fa.disposals`, `fa.transfers`, `fa.maintenance-cost`, `fa.revaluations`, `fa.impairment`, `fa.cwip`, `fa.gl-balances`, `fa.reconciliation` |
| 08 Inventory | `inv.valuation`, `inv.gl-reconciliation`, `inv.movement-valuation`, `inv.cogs`, `inv.adjustments`, `inv.write-offs`, `inv.in-transit`, `inv.aging`, `inv.slow-moving`, `inv.obsolete` |
| 09 Cost accounting | `mgmt.cost-center-pl` (+ CM-0…11 screens catalogued) |
| 10 Budgeting | `mgmt.budget-vs-actual` (scope P&L / capex / all), `mgmt.budget-monthly`, `mgmt.commitments` |
| 11 Analysis | `mgmt.ratios` (liquidity, leverage, returns, margins, DSO, DIO, DPO, CCC), `mgmt.break-even`, `mgmt.revenue-analysis`, `mgmt.product-profitability`, `mgmt.customer-profitability`, `mgmt.expense-analysis`, `mgmt.monthly-pl`, `mgmt.project-pl` |
| 12 Consolidation | `branch.trial-balance`, `branch.profit-and-loss`, `branch.balance-sheet`, `fx.currency-balances`, `fx.gain-loss`, `fx.rate-history` |
| 13 Audit | `audit.trail`, `audit.pending-approvals`, `audit.approval-history`, `audit.unposted`, `audit.backdated`, `audit.reversals`, `audit.manual-journals`, `audit.closed-period-postings`, `audit.segregation`, `audit.duplicate-bills`, `audit.duplicate-payments`, `audit.anomalies`, `audit.suspense`, `audit.period-status` |
| 14 Advanced | `adv.accruals`, `adv.prepayments`, `adv.deferred-revenue`, `adv.provisions`, `adv.ecl`, `adv.retained-earnings`, `adv.post-closing` |
| 15 Quarry | `quarry.site-pl`, `quarry.extraction-cost`, `quarry.crusher-cost`, `quarry.cost-per-unit` (t and m³), `quarry.production-vs-sales`, `quarry.yield-loss`, `quarry.capacity`, `quarry.material-margin` |

## Integrity checks shipped with results

`ALL_ACCOUNTS_MAPPED`, `NO_DOUBLE_MAPPING`, `BALANCED_*`, `TIES_TO_GL_*`, `CASH_RECONCILES*`, `OPENING/PERIOD/CLOSING_BALANCES`
(trial balance), `TIES_TO_BALANCE_SHEET` (equity), `BRANCHES_ADD_UP`, `SEGMENTS_ADD_UP`, `ALL_BALANCED` (journal
register), `TIES_TO_GL` (AR / AP aging, stock valuation, COGS, VAT return, PAYE), `TOTAL_RECONCILES` / `PARTNERS_RECONCILE`
(subledger reconciliations), `COST_RECONCILES` / `DEPRECIATION_RECONCILES` (fixed assets), `ALL_FISCALISED` (EFRIS),
`NONE` (audit exceptions), `NO_SELF_APPROVAL`, `NONE_OVER_LIMIT`, `COMPLETE` (truncated listings).

## Default layouts

P&L (credit − debit, costs negative): Revenue, Sales returns and discounts, **Net revenue**, Cost of sales, **Gross
profit**, Other operating income, Operating expenses (excl. 5600, 7200), Depreciation (5600), **Operating profit**, Finance
costs and FX losses (7200), Other expenses, **Profit before tax**, Income tax, **Net profit**; KPIs (EBITDA, margins).

Financial position: cash and equivalents, receivables, inventories, prepayments, other current assets; PPE (incl. CIP),
accumulated depreciation; payables, tax, deferred revenue, other current liabilities; non-current liabilities; equity,
OCI reserve, profit not yet closed.

Cash flow (indirect): profit, depreciation, changes in receivables / inventories / prepayments / payables / tax / other
current liabilities, capital expenditure, borrowings, equity; reconciles opening to closing cash.

## Deviations and limits

- Ledger reports read posted lines; subledger reports read documents, payments, stock and asset tables read-only.
- AR / AP aging before today rebuilds residuals from payment allocations and deposit applications by date;
  receipt-voucher payables are always current.
- EFRIS reconciliation reads the fiscal fields of POS invoices; sales documents outside the POS are matched against the
  EFRIS portal export (no import yet).
- Not built (no data in the system): IFRS 16 leases (owner to decide), contingent liabilities, subsequent events,
  financial-instrument classification, accounting estimates register, goods delivered not invoiced.
- Customer profitability uses today's average product cost (labelled "estimated").
