# Accounting review — process flow, UI, data flow and integrity (2026-11-15)

Branch `phase/acc-review-fixes` (from `phase/I2-integrity` @ `3d493469`). Four read-only reviews (GL core, sub-ledger
postings, reports / tax / treasury, web UI) traced the code; the most severe findings were re-checked by hand. The owner
asked for all of them to be fixed ("fix the above"). Deviations from Nest are recorded in
`docs/spec/accounting.md` → "Accounting review deviations".

What was found sound: posted entries are immutable (V1 triggers), every entry balances in base, posting keys plus an
advisory lock make posting idempotent, accounts are tenant-scoped and group / inactive / deprecated accounts are refused,
every posting path checks the fiscal period, documents and their journal commit together (POS COGS by a retried job),
reports use base amounts and exclude drafts, the balance sheet balances.

## Findings and status

| ID | Severity | Finding | Status |
|---|---|---|---|
| R-1 | High | Bare-date `to` / `asOf` dropped the whole end day from most reports (Nest same) | fixed |
| G-1 | High | FX revaluation kept revaluing paid foreign items (settlement lines had no currency) | fixed |
| G-2 | High | VAT lock bypassed by draft approval and reversals | fixed |
| P-1 | High | Vendor-bill cancel left the auto-received stock and a GRNI credit (Nest same) | fixed |
| R-3 | High | VAT settlement missed taxes' own accounts; tax payments reduced the next period's VAT | fixed |
| R-2 | High | Closed months showed zero spend in budget check / consolidated / dashboard / cost-centre / analytic | fixed |
| P-3 | High | Foreign bill vs non-PO receipt mixed currencies in the price variance | fixed |
| U-1 | High | Account create / edit always 400 (`isGroup`, `isControlAccount`) | fixed (web); a group account is created then flagged on edit |
| U-2 | High | "Post entry" only saved a draft; no post / discard in the UI | fixed (web) |
| U-3 | High | Ledger drill-down opened "Journal entry not found" | fixed (backend `journalEntryId` + web) |
| G-3 | Medium | A closed year's period could be reopened and posted into | fixed |
| G-4 | Medium | Year `netIncome` counted reversals of closing entries | fixed |
| G-5 | Medium | A reopened period with no new activity could not be closed again (Nest same) | fixed |
| G-6 | Medium | Balance checked in base only; rounding line mixed currencies; no DB check | fixed (V495 `NOT VALID` checks) |
| G-7 | Low | Concurrent reversals / approvals not row-locked | fixed |
| G-8 | Low | Draft approval did not re-check accounts | fixed |
| G-9 | Low | Opening balances bypassed the manual-posting rule | fixed |
| P-4 | Medium | Foreign PO receipt voucher AP vs bill-rate payment left AP residue | fixed (voucher AP in PO currency; bill re-measures vouchered share) |
| P-5 | Medium | Return to vendor: no VAT reversal, bill residual untouched, stock-adjustment accounts (Nest same) | fixed, except GRNI for unvouchered receipts |
| P-6 | Medium | POS tax groups posted to one account; refunds to another | fixed |
| P-7 | Medium | Credit note in another currency settled the invoice by the raw number | fixed (refused) |
| P-8 | Medium | Asset capitalisation credited AP with no open item | fixed (asset clearing) |
| R-4 | Medium | Stale snapshot served after a back-dated posting (Nest same) | fixed |
| R-5 | Medium | TB CSV export counted drafts and future lines (Nest same) | fixed |
| R-6 | Medium | Bank-rec summary book balance wrong after a reversal | fixed |
| R-7 | Medium | Concurrent auto-match could match one payment twice | fixed (per-account lock) |
| R-8 | Medium | Reconciliation statement mixes base and account currency; wall-clock vs instant dates | **not fixed** — needs treasury / bank postings to carry the account currency first |
| R-9 | Medium | Ageing added document currencies together and tied them to the base control account | fixed |
| R-10 | Medium | Tax report did not convert procurement debit notes | fixed |
| R-12 | Low | Account ledger had no opening balance (Nest same) | fixed |
| — | Medium | `POST /fx-revaluations` not idempotent | fixed |
| U-4…U-17 | Medium/Low | Client-side filtering of one page, 200-account cap, silent reversal errors, retry duplicates, local-date shifts, fixed 2 decimals, float balance check, permission gates, confusing close screens, missing current-year earnings row, uncleared journal defaults, no bank-rec sign-off | fixed (web) |

Not in scope (reported as Low by the reviews, not listed for fixing): midnight-UTC normalisation of real timestamps,
`numeric(18,8)` stored rates, the `FxRevaluation` audit key without currency, offline POS sales into a closed period,
cash over/short dated now, non-V2 payroll deductions, events published before rollback, treasury postings without
currency, back-dated withdrawal funds check, the CSV "1.500" thousands heuristic.

## Verification

See the PR description / commit message for the exact test classes run. The full `./gradlew build` was not run (CI runs
it on push).
