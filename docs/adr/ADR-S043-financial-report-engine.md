# ADR-S043: Financial report engine and library (FR-0)

- Status: Accepted (owner approved the plan, 2026-11-02)
- Plan: [`docs/roadmap/financial-reporting-plan.md`](../roadmap/financial-reporting-plan.md)
- Spec: [`docs/spec/reporting.md`](../spec/reporting.md)
- Migrations: `V236__fr0_report_engine.sql`, `V237__fr1_cash_flow_layouts.sql`

## Context

The system had about 200 report endpoints, each with its own filters, period logic, export and screen. The owner's
catalogue asks for 219 accounting reports at SAP / Oracle / Odoo level, with shared filters, comparatives,
drill-down, saved views, scheduling, export, permissions and reproducibility (catalogue §2), and a configurable
statement mapping (§6). Building 60 missing reports as 60 more isolated screens would multiply the inconsistency.

## Decision

1. **A new module `reporting`** (`com.inventoryerp.reporting`) with an SPI (`reporting.spi`): `ReportDefinition`,
   `ReportRequest`, `ReportContext`, `ReportBody`, `ReportProvider`. Later phases add providers in the module that owns
   the data; the engine knows none of them. It depends on `kernel`, `core` (branch data scope) and `accounting`
   (organization time zone) only.
2. **One request shape for every report** (period preset or from / to / as-of, branches, cost centers, partners,
   accounts, journals, source types, analytic dimensions, posting status, comparatives, layout, page). A report
   declares the filters it accepts; the engine refuses the others with a 400, so a saved view or schedule cannot
   silently ignore a filter.
3. **Period presets resolve at run time** in the organization's time zone. Saved views and schedules keep the preset,
   so "last month" stays last month. Comparative periods: prior period (whole months stay whole months), prior year
   (month ends stay month ends), plus variance and variance %.
4. **Financial reports read posted journal lines only** (`posted` + `reversed`; a reversal is its own posted entry, so a
   reversed pair nets to zero). `postingStatus = ALL` adds drafts and is shown in the header.
5. **Configurable statement layouts** (`FinancialStatementLayout` / `FinancialStatementLine`): a line takes account
   categories, report sections or account codes (minus excluded codes) with a debit or credit sign, or is a formula of
   other lines (`+ - * /`, parentheses; division by zero is blank). Two system layouts (IFRS for SMEs profit or loss and
   financial position) are created per organization on first use, stay editable and can be reset. EBITDA and margins
   are `kpi` lines, kept apart from the IFRS lines (catalogue §6).
6. **Integrity checks travel with the result**: every account with a balance is on exactly one line; the financial
   position balances per column; net profit equals the income and expense accounts; a journal-line selection balances.
   A layout mistake shows as a failed check, never as a silently wrong statement.
7. **Drill-down is data**: every amount cell carries a drill target (another engine report with parameters, normally
   `gl.journal-lines`), every journal line a route to its journal entry and, when the source type is known, to its
   source document.
8. **Run log for reproducibility**: each run stores its parameters and a SHA-256 of columns, rows and totals
   (`ReportRun`). `POST /reports/runs/{id}/verify` re-runs it and compares hashes; a closed period must give the same hash.
9. **Branch data scope (ADR-S017) in the engine**: a restricted user's runs are limited to their branches; asking for
   another branch is a 403.
10. **Exports from the result, not per report**: CSV (UTF-8 BOM), XLSX (Apache POI) and PDF (OpenPDF) with the standard
    header. **Apache POI `poi-ooxml` 5.4.1 is added to the pinned stack by this ADR** (owner decision 1).
11. **Schedules run as their owner** with the owner's permissions and branch scope at delivery time, every 15 minutes
    under ShedLock, by e-mail (attachment) and in-app (owner decision 5).
12. **Existing reports are catalogued, not rewritten**: `reporting/catalogue.json` lists 92 existing screens as links
    with their permission, so the library is complete from day one and no client contract changes.

## Consequences

- New permissions `report_library:read|manage|schedule`; every report also needs its own permission
  (`report:accounting` for the engine statements). Admin roles receive the new permissions through `CorePermissionSync`.
- Presets are calendar periods; fiscal-year presets and a budget column arrive with FR-1.
- The running balance of `gl.journal-lines` is debit − credit; FR-1 shows it in the account's normal sign.
- Report rows are limited to what one request returns (journal lines are paged, 500 by default, 5,000 maximum).

## Addendum — FR-1 to FR-9 (built together at the owner's request, 2026-11-02)

13. **One module reads all subledgers, read-only.** FR-2 to FR-9 query documents, payments, stock, assets, payroll,
    budgets, approvals and quarry / crushing tables with SQL from `reporting`, never writing them. Owning-module
    providers through the SPI stay possible; centralising the read queries kept 126 new reports consistent (same
    filters, period logic and checks) and avoided adding named interfaces to nine modules.
14. **Shared primitives instead of per-report code**: account roll-forwards (12 reports), account books (4), statement
    matrices by branch / cost center / project / month (5), a table builder with group subtotals and totals, and
    `OptionSpec` report options (aging basis and buckets, budget, loss rates, density, windows) validated by the engine.
15. **Budget as a comparative column** (`compare: BUDGET`, `options.budgetId` or the budget covering the period),
    evaluated through the same layout as actuals; budget amounts are in the account's normal balance.
16. **Cash flow (indirect) is a layout** (kind `CF`, V237) over period movements of non-cash accounts; it reconciles
    by construction and checks opening + net = closing cash. The direct method classifies the counterpart of every
    cash, bank and mobile-money posting.
17. **AR / AP open items** combine posted documents, POS invoices and receipt-voucher payables, net of unapplied
    payments, so aging ties to the control accounts; before today residuals are rebuilt from allocations by date.
18. **Fiscal presets** (`FISCAL_YTD`, `LAST_FISCAL_YEAR`) use the fiscal year containing today.
19. **Not built**: IFRS 16 leases (owner decision 3, deferred), contingent liabilities, subsequent events,
    financial-instrument and estimates registers, goods delivered not invoiced — no source data exists yet.

A smoke test runs every engine report (defaults, year to date, comparatives) and an integrity test proves the
catalogue §7 acceptance criteria on posted data.
