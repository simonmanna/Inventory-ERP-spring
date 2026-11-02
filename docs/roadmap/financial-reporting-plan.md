# Financial Reporting Framework — Design and Phased Plan (rev 1)

> **Status:** approved 2026-11-02 with the recommended defaults (POI for XLSX, IFRS for SMEs layouts, EFRIS by import
> file, e-mail + in-app delivery; leases decided at FR-8). One independently tested phase per PR (FR-0 … FR-9), owner
> review before the next phase starts — same process as `cost-management-plan.md`. Phase log in §7.

## 1. Goal

Turn the existing ~200 report endpoints into one **accounting report engine** with a 15-category library
(SAP / Oracle Fusion / Odoo level): every report shares filters, comparative columns, drill-down to journal and
source document, saved views, scheduled delivery, export (CSV / XLSX / PDF / print), permissions and a
reproducible run log. Fill the ~60 missing reports on top of that engine, never as isolated screens.

Principles (non-negotiable, from the owner's catalogue §2.D):

1. Financial reports read **posted GL lines only** (`JournalEntry.status = posted`); operational reports are labelled
   "operational" and never feed a financial statement.
2. Every subledger report has a **tie-out** to its GL control account (AR, AP, inventory, FA, VAT, WHT, cash).
3. Closed periods are reproducible: runs over a closed period read the period-close snapshot or are hashed.
4. Tenancy + branch data scope (ADR-S017) applied in the engine, not per report.
5. EBITDA / KPIs are management measures, shown separately from IFRS statement lines.

## 2. What exists today (gap analysis)

Legend: ✅ exists · 🟡 exists but lacks filters/comparatives/drill · ❌ missing.

| # | Category | Status | Existing endpoints (examples) | Main gaps |
|---|---|---|---|---|
| 01 | Financial statements | 🟡 | `/reports/accounting/{trial-balance,profit-and-loss,balance-sheet[/detailed],cash-flow,general-ledger,account-ledger,detailed,tieout}` | Configurable FS layout, comparative columns, budget column, SoCE, journal report, direct CF, notes |
| 02 | General ledger | 🟡 | `general-ledger`, `account-ledger`, `detailed` (has branch/cost-center/partner filters), `/audit-log` page | Running balance across pages, project/dimension columns, created/posted-by, journal register |
| 03 | Accounts receivable | 🟡 | `/reports/ar/{aging,outstanding,customer-balances,revenue-*}`, customer statement (+pdf), `/credit/reconciliation` | Aging basis (due vs doc date) + aging date + custom buckets, aging detail, collections, credit utilisation, unallocated receipts, advances, payment history, bad debt, AR↔GL recon |
| 04 | Accounts payable | 🟡 | `/reports/ap/aging`, supplier statement (+pdf), supplier ledger, GRNI | Same set as AR + upcoming obligations, payment forecast, supplier credit notes, duplicate invoices/payments |
| 05 | Cash & bank | 🟡 | `/accounts/cash-flow/*`, bank reconciliation, cash-session reconciliation, POS X/Z, cashier shift | Cash book, bank book, cash position by location, undeposited funds, payment-method/MoMo analysis, bank charges, FX cash, cash forecast |
| 06 | Tax & statutory | 🟡 | `/reports/tax/{summary,pack}`, HR payroll register / tax tables | VAT detail, configurable VAT return boxes (URA), input/output split, WHT report + certificate register, PAYE summary, tax liability/payment, tax↔GL recon, EFRIS recon |
| 07 | Fixed assets | 🟡 | `/fixed-assets-dashboard/reports/{type}`, depreciation by period, revaluations | Register with NBV as-of, acquisitions, disposals gain/loss, transfers, CWIP, impairment, FA↔GL recon, maintenance cost per asset |
| 08 | Inventory accounting | ✅/🟡 | valuation, `/reports/inventory/gl-tieout`, cost layers, GRNI, landed-cost reports, revaluations, standard-cost variances | Stock-in-transit valuation, goods-delivered-not-invoiced, aging, slow/obsolete valuation, by-branch value, COGS report |
| 09 | Cost accounting / manufacturing | ✅ | CM-0…CM-11, cost-center P&L/matrix, manufacturing costing, process costing, variance analysis | Catalogue adapters only; product cost sheet in common layout |
| 10 | Budgeting | 🟡 | `/budgets/{id}/vs-actual`, procurement budgets, commitments, project budgets | Monthly phasing view, variance %, departmental/branch budget, rolling forecast, capex budget, utilisation |
| 11 | Financial analysis | 🟡 | `/reports/dashboard-kpi`, `/sales/analytics/profitability`, CM-9 profitability runs, branch comparison | Ratio pack (current, quick, D/E, ROA, ROE), DSO/DPO/DIO/CCC, EBITDA bridge, break-even, contribution margin |
| 12 | Multi-branch / consolidation | 🟡 | `/reports/accounting/consolidated`, `/intercompany/{reconciliation,consolidation}`, branch transfers clearing | Branch TB/BS/P&L via common branch filter, inter-branch due-to/due-from recon, contribution |
| 13 | Audit & compliance | 🟡 | audit log, close checklists, approvals, `/alerts/exception-report`, expense audit | Backdated, manual-vs-auto journals, reversals, suspense, locked-period attempts, SoD conflicts, duplicate invoice/payment, approval history |
| 14 | Advanced IFRS | 🟡 | sales-agreement accruals, quarry IAS 37 provisions (ADR-S035), revaluation | Accruals/prepayments/deferred revenue registers, provisions roll-forward, ECL matrix, RE reconciliation, post-closing adjustments. **Leases (IFRS 16) has no subledger** |
| 15 | Quarry & construction materials | ✅ | quarry cost-per-tonne / losses / royalties, crushing yield / cost-per-tonne / plant hours, precast cost-per-block, fleet fuel / delivery profitability / tonnes / utilisation, weighbridge discrepancies | Quarry P&L, cost per m³, production-vs-sales recon, capacity vs actual, rehab provision roll-forward — mostly composition of existing data |

Roughly: **~110 of 219 covered in some form, ~50 partial, ~60 missing.**

## 3. Engine architecture (FR-0)

Package `com.inventoryerp.accounting.reporting` (engine) — other modules contribute reports through a
`ReportProvider` SPI (no repository access across modules; ArchUnit-checked).

```
ReportDefinition   id ("fs.profit-and-loss"), category (01..15), title, level (OPERATIONAL|FINANCIAL|MANAGEMENT),
                   permission, filters[] (from the shared FilterKey set), columns[], defaultGroupBy,
                   supportsComparatives, drillTarget, basis (POSTED_GL | SUBLEDGER | OPERATIONAL)
ReportRequest      period (from/to | asOf | fiscalPeriodId), companyIds, branchIds, departmentIds, costCenterIds,
                   projectIds, partnerIds, accountIds/accountGroup, productIds/categoryIds, warehouseIds,
                   currency (BASE | TXN | code), sourceModule, postingStatus, journalTypes, approvalStatus,
                   groupBy[], sort, compare[] (PRIOR_PERIOD | PRIOR_YEAR | YTD | BUDGET:{id} | VARIANCE | VARIANCE_PCT),
                   page/limit
ReportResult       header {company, branch, title, period, asOf, currency, basis, status, generatedAt, generatedBy,
                   runId}, columns[], rows (tree: group → subtotal → line), totals, checks[] (e.g. "TB balances",
                   "ties to GL 1100"), drill refs per cell
```

Routes (new, ours — not Nest parity):

| Route | Purpose |
|---|---|
| `GET /reports/library` | Catalogue filtered by the caller's permissions + `reports.visibility` (extends `ReportAccessService`) |
| `GET /reports/library/{id}` | Definition: filters, columns, comparatives |
| `POST /reports/run/{id}` | Run with `ReportRequest`, returns `ReportResult`, writes `report_run` row |
| `GET /reports/run/{id}/export?format=csv|xlsx|pdf` | Export of the same request |
| `GET /reports/drill` | Cell → GL lines → source document link (`sourceType`/`sourceId` on journal) |
| `GET/POST/DELETE /reports/views` | Saved views (per user, or shared to a role) |
| `POST /reports/schedules` | Scheduled delivery via the ADR-S021 scheduler (email / in-app) |

Shared building blocks:

- **Financial statement layouts** (new tables `fs_layout`, `fs_layout_line`): lines = account ranges / categories /
  `ReportSection` / formula (`GP = REV - COGS`). Seeded IFRS-for-SMEs P&L, BS, CF (indirect via account CF class,
  direct via cash-line counterpart analysis), SoCE. Accountant edits without code (owner catalogue §6).
- **Comparative column engine**: one ledger query per column period, merged by layout line.
- **Ledger query service**: single place for posted-line aggregation with dimension filters (reuses `LedgerQueries`,
  `AnalyticReportService` dimension joins), branch data scope, closed-period snapshot reuse.
- **Run log** (`report_run`: id, org, user, reportId, params JSON, result hash, generatedAt) — auditability +
  reproducibility check for closed periods.
- **Adapters**: every existing report endpoint registered in the catalogue (operational ones flagged), so the library is
  complete on day one; existing routes and web pages stay untouched (client contract).
- **Web**: `ReportLibraryPage` (15 categories, search, favourites, recent), generic `ReportViewer` (filter bar from
  definition, comparative toggles, group/sort, subtotals, expand/collapse, drill side-panel, export, print CSS),
  saved-view picker. Existing pages link into it.

## 4. Phases

Each phase = one PR, exit gate = `./gradlew build` green, integrity tests listed, PORT_STATUS / done-report updated.

| Phase | Scope | New reports | Exit gate (examples) |
|---|---|---|---|
| **FR-0** Engine | Definitions, request/result, library + run + export + drill + saved views + run log, FS-layout tables + seed, adapters for all existing reports, web Library + Viewer | 0 new (≈110 catalogued) | Library lists only permitted reports; drill from any FS line reaches source doc; run log written; ArchUnit clean |
| **FR-1** Core statements | P&L, BS, CF (indirect + direct), TB (opening / movement / closing, dr/cr), SoCE, comprehensive income, journal register, GL with running balance + dimensions + users, account activity; comparatives + budget column | 6 | TB dr = cr; BS balances; P&L = Σ GL; net profit → RE; CF opening + net = closing; reversed journals net to zero; closed period reproducible (hash match) |
| **FR-2** AR / AP | Aging summary/detail (due/doc basis, aging date, custom buckets), ledgers, statements, outstanding, collections, credit utilisation, unallocated receipts/payments, advances, payment history, upcoming obligations, payment forecast, supplier credit notes, bad debt, subledger↔GL recon | 18 | AR aging total = AR control; AP aging total = AP control; statement closing = ledger balance |
| **FR-3** Cash, bank & tax | Cash book, bank book, cash position, receipts / payments, petty cash, undeposited funds, payment-method (MoMo / Airtel) analysis, bank charges, transfers, FX cash, recon exceptions; VAT summary/detail/return (configurable URA boxes), input/output, WHT + certificate register, PAYE summary, tax liability / payments / recon, EFRIS recon | 22 | Bank book = GL bank; VAT return = VAT control movement; WHT payable = GL |
| **FR-4** Inventory & fixed assets | Stock-in-transit value, GDNI, aging, slow / obsolete, by branch, COGS; FA register (NBV as-of), acquisitions, disposals, transfers, CWIP, impairment, maintenance cost, FA↔GL recon | 13 | Valuation = inventory control; FA register = FA cost + acc. dep. GL |
| **FR-5** Management & budgeting | Departmental / branch / cost-center P&L via layout + dimension, monthly budget phasing, variance %, rolling forecast, capex budget, utilisation; ratio pack, DSO/DPO/DIO/CCC, EBITDA bridge, break-even, contribution margin, KPI dashboard | 14 | Σ branch P&L = company P&L; ratios recompute from BS/P&L |
| **FR-6** Multi-currency & consolidation | FX balances by currency, realised / unrealised gain-loss, revaluation report, rate history, FX recon; branch TB / BS, inter-branch recon, consolidated statements with eliminations (reuse ADR-S028), contribution | 9 | Branch TBs sum to company TB; consolidated eliminations net to zero |
| **FR-7** Audit & compliance | Audit trail, pending approvals, unposted, backdated, reversed, manual-vs-auto, suspense, locked-period exceptions (new event log), SoD conflicts, duplicate invoice / payment, approval history, rule-based anomaly flags | 12 | Seeded fixtures trigger each exception exactly once |
| **FR-8** Advanced IFRS | Accruals / prepayments / deferred revenue registers, provisions roll-forward (incl. quarry rehab), ECL matrix, RE reconciliation, post-closing adjustments, estimates register | 8 (+ leases if approved) | Roll-forwards: opening + movements = closing = GL |
| **FR-9** Quarry & construction pack | Quarry P&L, cost per m³ (density from product spec), production-vs-sales recon, capacity vs actual, block cost by size, contract profitability; quarry dashboard composing existing reports | 7 | Cost per tonne × tonnes = cost pool = GL (unallocated shown) |

Indicative size: FR-0 and FR-1 largest (≈1 week each); FR-2…FR-9 ≈ 3–5 days each.

## 5. Acceptance tests carried through all phases

Regression class `FinancialReportIntegrityIT` (fresh test DB, grows each phase): TB balances, BS balances, P&L = GL,
NP → RE, CF reconciles cash, AR / AP / inventory / FA / VAT tie to control accounts, branch → company totals,
reversals, closed-period reproducibility, every report has a working drill path, 403 per report without permission,
org A cannot see org B.

## 6. Owner decisions needed before FR-0

1. **XLSX export** — Apache POI is not in the pinned stack (AGENTS §3). Options: (a) add POI via ADR, (b) CSV +
   PDF only. Recommendation: (a).
2. **Default statement layout** — IFRS for SMEs (recommended) vs full IFRS presentation.
3. **Leases (IFRS 16)** — needs a new lease subledger (schedule, ROU asset, liability unwind). In FR-8 or deferred?
4. **EFRIS** — is there an EFRIS integration feeding invoice fiscal numbers? If not, FR-3 ships the recon report
   against an import file only.
5. **Scheduled report delivery channel** — email + in-app (recommended) or in-app only.

## 7. Phase log

| Phase | Commit | ADR | Notes |
|---|---|---|---|
| FR-0 | see git log | S043 | Module `reporting`: library (15 categories, 3 engine reports + 92 catalogued screens), shared request, presets, prior period / prior year / variance, layouts (IFRS for SMEs P&L and BS, editable, checks), drill to journal lines and source documents, branch scope, CSV / XLSX / PDF, saved views, schedules, run log + verify. Web: Report Library and generic viewer. V236 |
| FR-1…FR-9 | see git log | S043 addendum | Built together at the owner's request: 126 more engine reports (130 in all) over shared primitives — trial balance, direct and indirect cash flow, changes in equity, GL, journal register, roll-forwards; AR / AP aging, ledgers, statements, reconciliations, forecasts; cash / bank / MoMo books, URA VAT return, WHT, PAYE, EFRIS; inventory and fixed-asset accounting; budget column, budget vs actual, ratios (DSO / DIO / DPO / CCC), break-even, profitability; branch / FX; audit exceptions; ECL, accruals, provisions; quarry pack. Leases deferred. V237 |
