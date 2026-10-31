# ADR-S023 — Fiscal years and the year-end close

Status: **accepted** (owner instruction "build it production ready", 2026-10-31; roadmap 1.7, PR-2). Implemented in
`V216`, `FiscalYearService`, `FiscalYearController` and the web pages `/fiscal-years`, `/fiscal-years/:id`.

## Context

The port has fiscal **periods** only (Nest parity): `POST /fiscal-periods/:id/close` posts a CLOSING journal that zeroes
every P&L account of the period into Retained Earnings, with blockers (unreconciled shifts, pending stock postings and
documents, inventory exceptions) and a per-period checklist with sign-offs. There is no fiscal year, no year-end
checklist, no carried-forward snapshot and nothing that opens the next year. Bootstrap creates one year-long period.

## Decision

1. **`FiscalYear`** (extension table) groups periods (`FiscalPeriod.fiscalYearId`). Creating a year links the periods
   already inside its dates and, by default, fills the gaps with monthly, quarterly or one yearly period (generated
   periods end at 23:59:59.999 of their last day). Overlapping years and periods that cross the year boundary are
   refused. A year is at most 18 months.
2. **Retained-earnings rollover stays in the period close.** Each period close already posts P&L to Retained Earnings,
   so the year close posts no journal. This keeps one posting path and keeps reopen symmetrical (a period reopen
   reverses its own closing entry).
3. **Year-end checklist**, blocking: earlier years closed; every period with postings closed; P&L nets to zero over
   the year; trial balance balances; no draft journals in the year; `retained_earnings` mapping set. Warning: unmatched
   bank statement lines. Manual sign-offs (all required): year-end stock count, depreciation and asset register,
   accountant review, annual tax computation.
4. **Close** (`POST /fiscal-years/:id/close`, idempotent): closes the open periods that have no postings (they cannot
   go through the period close, which refuses "Nothing to close"), re-runs the checklist, records net income, snapshots
   every balance-sheet account's cumulative debit / credit / balance at the year end into `FiscalYearBalance` (the next
   year's opening position and audit evidence), locks the periods unless `lockPeriods: false`, and creates the next
   year with the same length and periodicity when none exists.
5. **Reopen** only when the close did not lock the periods and no later period is closed. The snapshot is dropped;
   periods stay closed (reopen them one by one with the existing period reopen).
6. Permissions reuse `fiscal_period:read|create|update|reopen`. Every new table has the tenant RLS policy (V214).

## Consequences

- No change to existing routes or JSON. `FiscalPeriod` JSON from the parity routes does not show `fiscalYearId`.
- Organizations that never close periods must close each period with postings before the year; the checklist says
  which. Years before the first year record are untouched.
- Out of scope (owner later, roadmap 1.1/1.2): statutory year-end reports per country, tax returns.
