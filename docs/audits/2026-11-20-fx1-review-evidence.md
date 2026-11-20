# FX-1 review evidence (2026-11-20)

Branch `phase/fx-gainloss-fx1`, based on `main` dfd650c9. Decision record:
[ADR-S081](../adr/ADR-S081-fx-gain-loss-correctness.md). Audit:
[2026-11-20-fx-gain-loss-audit.md](2026-11-20-fx-gain-loss-audit.md). Owner approval of the five refusals: 2026-11-20.

## 1. Branch diff (17 files, +650 / −43)

| File | Change | Why |
|---|---|---|
| `accounting/application/CurrencyService.java` | Refuses a rate ≤ 0 on upsert. The lookup ignores a stored direct rate ≤ 0. | Approved refusal 1. |
| `accounting/application/JournalEntryService.java` | The generic reverse refuses an entry whose `reversalOfId` points to an `fx_revaluation` entry. | Approved refusal 2. |
| `accounting/application/RevaluationService.java` | `pg_advisory_xact_lock` per org in `run`. Category `prepaid_expense` is excluded from `compute`. | Concurrent double posting; approved refusal 5. |
| `expenses/application/ExpensesService.java` | New `inExpenseCurrencyLegs`, used by `recordPayment` and `settleAgainst`. The entry posts in base; expense and payable lines are tagged with `Line.inCurrency`; the functional cash or advance line is untagged. | Functional bank lines were tagged with the foreign currency. The advance offset left a phantom foreign payable. |
| `invoicing/application/CreditNoteService.java` | Passes the currency to `realiseOnReceivable`. `takeOpenCredit` refuses an open foreign credit. | The FX line was double counted by revaluation; approved refusal 3. |
| `invoicing/application/DocumentFxRates.java` | New `effectiveRate(doc, date)` through `CurrencyService.resolve`. `realiseOnReceivable` tags the receivable line with the currency and a zero foreign amount. Adds a `CurrencyService` dependency. | Bills store rate 1; the FX line was untagged. |
| `invoicing/application/InvoicingWorkflowsInitializer.java` | `postBill` uses `effectiveRate` for the variance and for `remeasureVoucheredPayable`. | The remeasurement of AP to the bill rate never ran. |
| `invoicing/application/PaymentService.java` | `ForeignCash.assertFunds` before relieving an outbound payment from a foreign account. | Approved refusal 4. |
| `procurement/application/LandedCostVoucherService.java` | A foreign charge at rate 1 (not stated) resolves from the rate table on the invoice date, else it is refused. Adds a `CurrencyService` dependency. | It was capitalised at 1 while its bill posted at the table rate. |
| `web/src/pages/accounting/currency.tsx` | Sends `asOf` (date field, default today). The pair defaults to USD → functional. Lists the selected pair. Blocks rate ≤ 0. | Every save returned 400; the list was always empty. |
| `web/src/lib/utils.ts` | `formatCurrency` uses the org currency and its ISO minor digits. | It hardcoded "UGX" with no decimals. |
| `test/.../FxGainLossFx1IT.java` (new) | 7 scenarios. | See §3. |
| `test/.../ProcurementIntegrityIT.java` | New test `poReceiptThenForeignBillAtAnotherRate`. | Bill remeasurement. |
| `test/.../LandedCostAuditIT.java` | New test `foreignChargeWithoutRateUsesTable`. | Landed-cost rate. |
| `docs/adr/ADR-S081-…`, `docs/audits/…`, `docs/spec/accounting.md` | Decision record, audit, deviations. | |

The complete patch is generated with `git diff main...phase/fx-gainloss-fx1` (971 lines).

## 2. Migration impact

- **No migrations.** No schema, no reference data, and no change to `V1`/`V2`. Flyway version stays V583.
- No existing journal line, document or rate is rewritten. All fixes apply to new postings only.
- No route, verb, path or response field changes. The new 400 refusals are listed in `docs/spec/accounting.md` →
  Deviations.
- Constructor changes (`DocumentFxRates`, `LandedCostVoucherService`) are Spring-wired. No code constructs them by hand.

## 3. Test evidence

All runs used `fastTest` on a dedicated Postgres 16 container (`erp-itdb-fx1`), first on a fresh database. The full
`./gradlew build` was **not** run, per the project build rule; CI runs it on push.

| Run | Classes | Result |
|---|---|---|
| New and extended | `FxGainLossFx1IT`, `ProcurementIntegrityIT`, `LandedCostAuditIT` | Green (41 tests, plus the 7 FX-1 tests rerun after fixture fixes) |
| Regression, fresh DB | `FxGainLossFx1IT SharedFxIT FxPaymentsIT FxDepositsIT FxTreasuryIT Acc0FxIT ReportingIT CurrencyServiceResolveTest ForeignCashTest Acc0ReversalIT ExpenseAccrualIT ExpenseClaimsIT ExpenseControlsIT ExpenseTreasuryIT ExpensesFlowIT SalesCreditPolicyIT SalesReturnFlowIT SalesReturnIT SalesStabilizationIT LandedCostAuditIT LandedCostVoucherIT LandedCostGapsIT LandedCostEndToEndIT ImportLandedScenarioIT PoBillApplicationIT ProcureToPayGapsIT ProcurementIntegrityIT ReadinessBlockersIT` (28 classes) | BUILD SUCCESSFUL, 0 failures |
| Web | `tsc --noEmit` | Exit 0 |
| Format | `spotlessApply` | Applied |

Scenario coverage in `FxGainLossFx1IT`:
- zero rate refused;
- foreign expense paid from a functional bank (bank untagged; payable clears in currency and in base);
- the credit-note FX bucket ties to residual × invoice rate;
- foreign credit refund refused, with the credit still open;
- foreign payment funds check;
- **the reviewer's sequence**: revalue 1,000 AP at 3,900 (−100,000) → reversed next day → pay 400 at 3,950 (realised
  60,000, no double count; 600 left at 3,800) → revalue 600 at 4,000 (−120,000, preview carrying −2,280,000) →
  reversed; manual reversal of the automatic reversal refused; balanced entries asserted;
- prepaid not revalued.

## 4. Self-review notes (behaviour to be aware of)

1. A posted foreign bill now resolves its rate before matching. A foreign bill whose lines are all vouchered (no bill
   journal) is refused when no rate exists, where it used to pass silently at rate 1.
2. Expense payment entries now post in base. Their foreign legs carry their own rate on the line (`exchangeRate`
   column = base ÷ foreign), and no `JournalEntryFxRate` row is written for them. The accrual entry keeps its snapshot.
3. A foreign landed-cost charge stated at exactly 1 is treated as "not stated" (the ADR-S054 legacy rule) and takes
   the table rate.
4. Realised FX when an accrued foreign expense is paid later at a new rate is **not** in FX-1. It is FX-2 workstream E.
5. Relief is rounded to 6 dp per payment, so any final-settlement residue is at most 0.000001 per payment. Not
   material.

## 5. Live DEMO diagnostics (read-only)

Run 2026-11-20 against the live `erp` database. Every query ran inside `BEGIN TRANSACTION READ ONLY … ROLLBACK`, for
organisation DEMO only (functional currency UGX).

| Check | Result |
|---|---|
| D0 exchange rates (org and shared) | 0 rows, 0 non-positive |
| D1 foreign-currency documents | none |
| D2 foreign-tagged lines on functional cash or bank accounts (expense defect) | none |
| D3 `credit_note_fx` entries with an untagged receivable line | none |
| D4 foreign AR/AP buckets per partner, open or with base residue | none |
| D5 posted foreign bills missing the bill-rate remeasurement | none |
| D6 foreign landed-cost charges at rate 1 | 0 |
| D7 designated foreign cash accounts | none |
| D8 FX revaluation entries / rows; manually re-reversed reversals | 0 / 0 / 0 |
| D9 foreign lines on prepaid expense accounts | 0 |
| D10 foreign expenses | none |
| D11 foreign credit notes | none |
| D12 customer deposits | 1, cash sale, UGX, closed |
| D13 foreign payments | none |
| D14 rental deposits | 0 |
| Organisations without a functional currency (whole DB) | 0 of 2 |

**Conclusion:** DEMO has no foreign-currency history, so FX-1 needs **no correcting journals** there and nothing
historical was touched. The diagnostic script is reusable for any organisation (only the org id changes). GQDEMO,
which holds USD bills, was not examined, because only DEMO was authorised. Run the same script for it on request.
