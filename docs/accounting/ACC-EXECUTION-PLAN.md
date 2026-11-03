# Accounting remediation: revised execution plan (after ACC-0)

Written from **confirmed** findings only; see `ACC-0-verification.md` for the evidence. This is the plan of record for
ACC-1 … ACC-8.

**Rules for every phase:**

- One PR per phase, on branch `phase/ACC-n-<area>` (worktree).
- **Stop for owner review after each phase.** The report covers what was done, what was not done, and what comes next.
- `PostingService` stays the single GL writer. Posted history is never edited; corrections are approved, audited
  entries.

## Acceptance criteria (every phase)

| Criterion | Gate |
|---|---|
| Precision | No `double`/`float` in touched money paths; BigDecimal with HALF_UP to currency scale |
| Double entry | `LedgerInvariants.entriesBalanced` (base) + `fxLinesValid` on every touched flow |
| Idempotency | Replay of the same business transaction creates no journal; concurrent same-key returns the winner |
| Tenancy | Org A cannot read or alter org B; new tables carry `organizationId` + RLS |
| Branch / dimensions | `branchId`, `costCenterId` and dimensions preserved on every generated line |
| Historical accuracy | Mapping or policy changes never alter posted journals |
| Period integrity | No posting into closed or locked periods without the defined authorisation path |
| Auditability | Every reversal links `reversalOfId` / `reversedEntryId`; audit row for each control action |
| Reconciliation | AR, AP, inventory, FA and tax sub-ledgers reconcile to GL (`LedgerInvariants`) |
| Reporting | Reports agree with the posted ledger (`tbAsOf` vs report endpoints) |
| Authorization | Route permission matrix test; approval and posting permissions enforced separately |
| Tests | The phase's `acc-known-defect` tags are removed and the classes added to `accountingRegression`; nothing is weakened; `./gradlew build` green |
| Docs | `docs/spec/accounting.md` Deviations updated (owner-approved); `PORT_STATUS.md` updated |

Migrations use the next free high V-number. Check `db/migration` on main and the other open branches first.

---

## ACC-1: GL integrity and financial reporting (P0)

**Findings:** B1, B2, B10. The lifecycle P&L assertion.

1. **Closing-aware reporting.**
   - P&L excludes `sourceType IN ('period_close','fiscal_year_close')` in `FinancialReportsService.pnl` (live and
     snapshot) and in the `reporting/LedgerAggregator` P&L paths.
   - Reuse the filter already in `FiscalYearService.profitAndLossNet`.
   - BS and the post-closing TB keep the closing entries.
2. **Trial-balance views.** Optional `view=pre_closing|post_closing` query parameter (default = current behaviour).
   This is additive, so the API stays compatible.
3. **Fiscal-year close in base.** `FiscalYearService` lines 230, 270 and 400 switch to `baseDebit`/`baseCredit`.
4. **Snapshot P&L.** Persist and return other income / other expense, and honour `from`. The snapshot is used only when
   the range matches; otherwise compute live.
5. **Balance sheet.** Include inactive accounts that still carry a balance.

- **Migration:** maybe (`ReportPnLSnapshot` other-income columns, if absent).
- **API:** additive only.
- **Exit:** `Acc0FinancialReportingIT` all green; `Acc0LifecycleIT` P&L assertion green.

## ACC-2: FX and currency integrity (P0)

**Findings:** B3, B4, B14.

1. **Organization-scoped rates (B4).**
   - New `V{n}`: nullable `organizationId` on `CurrencyRate`, RLS, unique index `(organizationId, fromCode, toCode, asOf)`.
   - The resolver prefers the org rate, then falls back to global.
   - Rate `source` and `createdBy` are audited; an optional approval step goes behind `ControlledActionService`.
2. **Revaluation lifecycle (B3).** Owner decision C1; recommended: valuation-adjustment account plus day-1 reversal.
   - The carrying basis comes from original lines only.
   - `preview(asOf)` → `post(periodId)`, with deterministic key `fx_reval:<period>:<account>:<ccy>`.
   - Automatic reversal on the first day of the next period, linked to the original.
   - Covers AR, AP and bank, broken down by branch. BigDecimal only.
   - Routes: `POST /fx-revaluations/preview`, `POST /fx-revaluations`, `GET /fx-revaluations`. These are new
     (Nest has none) and need owner approval as a deviation. Plus a web page.
3. **Foreign expenses (B14).** Post the accrual and payment with the expense's currency and rate, so the FX snapshot is
   written.
4. **FX reconciliation report.** Per account and currency: foreign balance, carrying base, revalued base, open
   adjustment.

- **Exit:** `Acc0FxIT` green; a rerun posts 0; the next period posts only the incremental difference.

## ACC-3: Posting integrity (P0)

**Findings:** B6, B7, B12, P1-a. B13 payroll, after reproducing it.

1. **Reversal-date policy (B6).**
   - Org setting, default: open period → original date; closed → first permissible open date; locked → first open date
     plus `ControlledAction` authorisation.
   - Applied in `PostingService.reverse` when the caller passes null.
   - Both entries keep their own dates and the permanent link.
2. **Invoice cancellation with stock (B7).**
   - Find the original `stock.issue` movements by invoice reference.
   - Check later consumption and cost layers: AVCO restores at the issue cost; for FIFO the consumed layers go to a
     controlled return valuation.
   - Refuse if valuation cannot be reconstructed.
   - Post a linked compensating movement plus the COGS reversal. Block duplicates.
   - Delivered-and-accepted goods go through a credit note plus return instead (owner decision D-B7b on the second-cancel
     response).
3. **Money precision (B12).**
   - `VendorBillService.syncPoLinks`: BigDecimal split, with the remainder on the last link.
   - `HrPayrollService` legacy path to BigDecimal.
   - POS tender sums to BigDecimal (clean-up, not a defect).
4. **Concurrent posting key (P1-a).** `PostingService.post` catches the unique-violation on `postingKey`, re-reads and
   returns the winner. Applied inside a savepoint so the caller's transaction survives.
5. **Deterministic keys.** Invoice post, credit note, depreciation, revaluation and opening balance.
6. **Payroll posting date (B13).** Add the payroll IT fixture and reproduce. If confirmed, post at the period end
   date.
7. **Expense payment date (gap).** Optional `paymentDate` on `PayExpenseRequest`; defaults to today.

- **Migration:** none expected.
- **API:** additive (`paymentDate`).
- **Exit:** `Acc0ReversalIT`, `Acc0MoneyPrecisionIT`, `Acc0PayrollPrecisionTest`, `Acc0IdempotencyIT.concurrentSameKey` green.

## ACC-4: AR/AP document lifecycle (P1)

**Findings:** B8, B9, B15. Plus capability gaps.

1. **PO-backed bill open item (B15).**
   - The vouchered bill carries the voucher's AP as its residual, or the voucher becomes the open AP document.
     Design to be agreed with the owner.
   - Supplier payments and the AP sub-ledger then reconcile.
2. **Credit notes (B9).**
   - Validate quantity and amount against the referenced invoice lines.
   - Write a `PaymentAllocation`-style credit allocation, linked to 1..n invoices.
   - Keep the unallocated residual on the credit note.
   - Add cancel and reverse. Remove the dead `credit_note` workflow.
   - Refunds are a separate flow.
3. **Debit notes and supplier credits (B8).**
   - Tax lines on inbound and outbound notes.
   - Link to and adjust the referenced document's residual.
   - New supplier-credit document that reduces AP and reverses expense, stock and input VAT, by commercial event.
4. **Open-item matching.** Allocate existing unapplied payments and credits later, for AR and AP. Supplier-advance
   account.
5. **Payment terms.** Installments, early-payment discount posting, T&C text and term name on the PDF.

- **Migration:** credit-allocation table (or reuse `PaymentAllocation` with a source type), plus approved backfill.
- **Exit:** `Acc0ArApIT` and `Acc0LifecycleIT` AP assertions green; `partnerControlReconciles` for AR and AP.

## ACC-5: Fixed assets (P1)

**Finding:** B11.

- **Postings:**
  - Acquisition: Dr FA / Cr AP or bank.
  - CIP capitalisation: Dr FA / Cr CIP.
  - Disposal: remove cost and accumulated depreciation, book gain/loss.
  - Impairment.
  - Revaluation per the configured framework policy: increase → surplus/OCI; decrease → P&L per history.
  - Transfer: branch and custodian.
- **Other:** per-category accounts; DDB = 2× straight-line rate; units-of-production method; residual floor (already
  correct); posting preview; `@Idempotent` on money routes.
- **Owner decision D-B11:** cut-over date for catch-up entries on existing assets.
- **Exit:** `Acc0FixedAssetIT` green; `fixedAssetsReconcile` invariant on register vs GL.

## ACC-6: Bank, cash and treasury (P1)

**Finding:** B5 (API mode).

- Fix unsigned mode, or make signed the default (D-B5).
- Exclude payments matched in earlier runs.
- Match GL lines as well as payments.
- Partial and grouped matches.
- Rules: reference, partner, amount tolerance.
- Bank charges and interest proposals; authorised adjustment entries.
- Statement opening/closing proof: closing = opening + credits − debits.
- Lock completed reconciliations.
- One import screen instead of two. OFX.
- **Exit:** `Acc0BankReconciliationIT` green; lifecycle bank match green.

## ACC-7: Tax and fiscal positions (P1/P2)

These are capability gaps; no confirmed defects beyond B8's VAT.

- Fiscal-position tax/account mapping tables, auto-detection by partner/country, applied in `TaxCalculationService` and
  account determination.
- Tax groups; multiple taxes per line; compound-inclusive fix; WHT on bills; VAT settlement entry; tax-period lock.
- `taxReconciles` invariant against the VAT return.

## ACC-8: Reporting and UI (P2)

- TB, P&L and AR-aging filters, export (backend CSV exists) and drill-down.
- CoA deprecate/delete/reorder; JE draft/approve flow.
- FS layout editor; GRNI and AR analysis screens; FX revaluation page if not built in ACC-2.
- Error states; route-level permissions; i18n phrases; dark-mode tokens.
- Branch and consolidated report reconciliation test; backup-restore integrity run with `LedgerInvariants`.
- Defence in depth: explicit organization predicate plus 404 on `findOne` routes (D-O1).

## Later

- Versioned, effective-dated posting rules.
- Posting simulation for every document type.
- Reusable reconciliation engine and **Accounting Integrity Dashboard**: TB, AR/AP/inventory/FA/bank/tax
  reconciliation, posting exceptions, close readiness, per branch.
- Accounting-policy configuration: org-scoped, effective-dated, audited, never altering history.
- Recurring journals, deferred revenue, accruals, cash forecasting.
- Country CoA packs.

## Parallel track

PC-1 inventory valuation (WP3+, branch `phase/PC-1-valuation`), coordinated with ACC-3 (stock compensation) and ACC-4
(B15 GRNI/AP model).
