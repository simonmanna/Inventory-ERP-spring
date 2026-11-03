# LC-5 — Landed cost voucher UX (done-report)

- Plan: [`docs/roadmap/landed-cost-plan.md`](../roadmap/landed-cost-plan.md) § LC-5 · ADR: [ADR-S053](../adr/ADR-S053-landed-cost-erp-grade.md) § LC-5

## Delivered

| Plan item | Where |
|---|---|
| Preview: journal, disposition, `previewHash`, `computedAt`, warnings | `LandedCostVoucherService.preview` (test `previewJournalHashAndWarnings`) |
| Stepper, autosave, sticky summary, stale preview | `LandedCostVoucherEditPage` |
| Detail tabs (Overview, Allocation, Accounting, Approval, Trace), credit voucher | `LandedCostVoucherDetailPage` |
| Exceptions workspace, reconciliation tab, accrual rules + monitor, overrides, trace tree | `LandedCostControlPage` (`/procurement/landed-cost-control`) |
| Navigation | *Imports → Landed Cost Control* |

## Verification

- `pnpm web:typecheck` clean; eslint: warnings only (`any`, as the rest of the file).
- Walk-through against the local API (seed profile) with Playwright: voucher submitted → approved → posted from the UI,
  Accounting / Approval / Trace tabs, edit stepper with autosave and the review journal, all five control tabs, phone
  width; **zero console errors**.

## Not delivered (listed in the ADR)

- Historical tax review list; GRN panel buttons.
