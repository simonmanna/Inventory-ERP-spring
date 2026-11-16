# Foreign-currency treasury — architecture and migration plan (R-8)

- **Status:** approved by the owner 2026-11-16 (all five §10 recommendations accepted) and **built** 2026-11-16, all
  five phases, on `phase/fx-treasury` — see [ADR-S076](../adr/ADR-S076-foreign-currency-treasury.md) for what was
  built and where it narrows this plan. R-8 stays open until the implementation, migration and regression review are
  independently verified.
- **Origin:** accounting review 2026-11-15, finding R-8 (`docs/audits/2026-11-15-accounting-review.md`). Owner brief
  "R-8 – Foreign-Currency Treasury: Design and Implementation Decision" (policy requirements 1–9, phases 1–5).
- **Branch:** `phase/fx-treasury` (from `main` @ `d014ecfe`). One branch / PR per phase, review stop after each.
- **New ADR:** ADR-S076 "Foreign-currency treasury" (S073–S075 were taken by the GL automation, compliance and
  contractor branches). Extends ADR-S054; touches ADR-S070 only by reference.

---

## 1. Current state (inspected 2026-11-16)

| # | Area | What exists | Where |
|---|---|---|---|
| C-1 | Functional currency | `Organization.currencyCode` (default `USD`). **Changeable at any time**, even after postings, by two endpoints, with no guard: a change silently re-denominates every functional amount. | `OrganizationsService.updateSettings` (~l.192), `CompanySettingsService.putCompanySettings` (`baseCurrencyCode`, ~l.107) |
| C-2 | Currency master and rates | `Currency(code, decimalPlaces)`. `CurrencyRate(fromCode, toCode, asOf, rate numeric(18,8), source)`: 1 FROM = `rate` TO. `findRate` = latest at/before the date, direct or reciprocal of the inverse pair. `resolve` = ADR-S054 precedence (stated document rate → table → refused). | `CurrencyService` |
| C-3 | Journal line | `debit`/`credit` = transaction amounts (numeric 20,6); `currencyId` = currency **code**; `exchangeRate` (18,8) = functional per unit; `baseDebit`/`baseCredit` (20,6) = functional. Entry-level currency + per-line override `PostingRequest.Line.inCurrency` and `PostingMath.BASE_ONLY` (review G-1). Base must balance (≤ 0.01 goes to a base-only `ROUNDING` line); non-override lines must balance in the entry currency (G-6). V499 `NOT VALID` checks: non-negative, one side per line. | `PostingService`, `PostingMath`, `PostingRequest` |
| C-4 | Rate snapshot | `JournalEntryFxRate`: **one row per entry** (unique on `journalEntryId`), written by `PostingService` for foreign-currency entries. Cannot hold the two pairs of a cross-currency transfer. | V291, `PostingService` |
| C-5 | Account denomination | `Account.currencyId` (code; null = functional) on cash / bank / mobile-money accounts. Set at creation (`POST /accounts/cash-flow`, bank-account routes); **changeable later regardless of postings**. | `CashFlowService.create` / `update` (~l.168, l.196), `TreasuryController` |
| C-6 | Treasury | Deposit / withdrawal post the typed amount **base-only** (no currency, no rate). Transfer refuses different currencies and posts base-only. Funds check is a base balance. | `CashFlowService` (~l.260–310), `TreasuryService.transfer`, `TreasuryGuards.assertSufficientFunds` / `postedBalance` |
| C-7 | Payment tenders | `TenderAccount.resolve` refuses any account whose currency ≠ functional ("POS tenders must use the organization currency; convert foreign currency through treasury first"). Callers: `PaymentService`, `CustomerDepositService`, `PosInvoiceService`, `PosLoyaltyService`, `BranchSaleRecognitionService`, `StockPostingService` (PO pay). | `TenderAccount` |
| C-8 | Payments | ADR-S054: payment currency + rate resolved; documents relieved at their carrying rate; AR / AP line in the documents' currency (G-1); **cash line base-only**; difference to `fx_gain` / `fx_loss`; voids reverse. Foreign payments settle documents only. | `PaymentService`, `DocumentFxRates` |
| C-9 | Expenses, petty cash, advances | Funds checks and postings in base. | `ExpensesService`, `PettyCashService`, `ExpenseAdvanceService` |
| C-10 | Bank reconciliation | `BankStatementLine.currencyCode` stored, **not validated** against the account. Summary / statement ledger side = base; outstanding payments and statement lines = their own amounts; auto-match on amount equality. | `BankReconciliationService` |
| C-11 | Revaluation | Revalues each (account, currency) balance of tagged lines on reconcilable categories; adjustment lines base-only; auto-reversed on the next org-local day. `FxRevaluation` unique on (org, period, **account**) — two currencies on one account (AR / AP) overwrite each other. | `RevaluationService`, V1 |
| C-12 | Reporting | Statements use `baseDebit`/`baseCredit` (functional) — correct. Account ledger and treasury transaction lists show base only. | `FinancialReportsService`, `CashFlowService.getTransactions` |
| C-13 | Legacy data | Any account with `currencyId` ≠ functional and posted base-only lines holds numbers of **unknown denomination** (C-6 posts typed amounts as base). They cannot be converted automatically. | data |

### 1.1 Assumptions that equate account currency with functional currency

| # | Assumption | Where | Phase |
|---|---|---|---|
| A-1 | A cash / bank balance is `Σ base` | `TreasuryGuards.postedBalance`, `CashFlowService` balances / running balances, bank-rec summary and statement | 1, 2, 4 |
| A-2 | A treasury amount is a functional amount | `CashFlowService` deposit / withdrawal, `TreasuryService.transfer` | 2 |
| A-3 | A payment account is functional (else refused) | `TenderAccount.resolve` | 3 |
| A-4 | The cash leg of a payment is a base line | `PaymentService.fxLines`, `StockPostingService.postPurchasePayment` | 3 |
| A-5 | Expense / petty-cash / advance funds and postings are functional | expenses module | out of scope (refused explicitly, §4 P-12) |
| A-6 | Statement lines can be compared with the base ledger | `BankReconciliationService` | 4 |
| A-7 | One FX snapshot per entry | `JournalEntryFxRate`, `PostingService` | 1 |
| A-8 | One revaluation row per (period, account) | `FxRevaluation` key | 5 |
| A-9 | The functional currency and an account's currency never change | C-1, C-5 | 1 |
| A-10 | POS drawers / cash sessions are functional | `CashSessionService`, `SyncPullService`, POS | kept (stated) |

---

## 2. Policy (owner requirements → rules)

| Req | Rule |
|---|---|
| 1 Functional currency | `Organization.currencyCode`. **Immutable once the organization has any posted or reversed journal entry** (both endpoints refuse: "The functional currency cannot change once entries are posted"). Deviation from Nest (data safety). |
| 2 Bank denomination | `Account.currencyId` (null = functional). **Immutable once the account has a posted line.** |
| 3 Both amounts kept | Every line on a foreign-denominated account carries `currencyId` = denomination, `debit`/`credit` = foreign amount, `baseDebit`/`baseCredit` = functional, `exchangeRate` = functional ÷ foreign. Uses the existing `inCurrency` override; no new columns on `JournalLine`. |
| 4 Rates snapshotted | Each currency pair used by an entry writes one `JournalEntryFxRate` row (pair, rate, rate date, source) in the same transaction. |
| 5 Balanced in functional | Unchanged engine rule: base balances exactly, ≤ 0.01 to the base-only rounding line, otherwise refused. Each foreign leg is internally consistent (foreign × rate = base at 6 dp). |
| 6 Reconcile in denomination | Ledger side = Σ (`debit` − `credit`) of the account's lines in its denomination. Statement lines must be in the denomination (import refuses others). |
| 7 Realised / unrealised FX | Realised at relief (P-6). Unrealised by the existing period-end revaluation with next-day reversal, per (account, currency). |
| 8 Cross-currency transfers | Supported (P-8). |
| 9 Backward compatible | Functional-denominated accounts and every existing flow post exactly as today (same lines, same amounts). Regression check §6. |

---

## 3. Rules: rates, precision, rounding, carrying value

- **R-direction.** A rate row `(FROM, TO, r)` means 1 FROM = r TO. Conversion to functional F: `base = foreign × rate(CUR→F)`
  resolved by `CurrencyService.resolve` (ADR-S054: stated rate → table → refused, nothing written). A cross rate A→B is
  never stored or looked up directly; each leg is converted to F on its own and both legs' rates are snapshotted.
- **R-precision.** Rates: 8 dp (`CurrencyRate`, `JournalLine.exchangeRate`), 10 dp in the snapshot (as today).
  Amounts: numeric(20,6). A foreign amount with more decimals than its `Currency.decimalPlaces` is refused
  ("USD amounts take at most 2 decimals").
- **R-rounding.** `base = round(foreign × rate, 6, HALF_UP)` per line — the engine's current rule (`PostingMath`). A
  realised-FX line takes the exact residual, so FX entries balance without a rounding line. No change to existing
  rounding behaviour.
- **R-carrying.** The carrying rate of a foreign account = Σ tagged base ÷ Σ tagged foreign of its posted lines up to
  the posting date, read under the account's treasury lock (`TreasuryGuards.lockAccounts`). Revaluation and rounding
  lines are base-only and excluded (revaluations reverse the next day). An outflow relieves `foreign × carrying`. An
  outflow that empties the foreign balance relieves exactly the remaining tagged base, so no base residual is ever
  left on a zero foreign balance (which revaluation could not clear, because it reverses).
- **R-funds.** The funds check on a foreign account is in its denomination (Σ tagged foreign ≥ amount).
- **R-revaluation.** Unchanged algorithm; the revaluation row is keyed per (period, account, currency). Foreign bank
  accounts are revalued because their lines are now tagged.

---

## 4. Decisions proposed (defaults; owner to confirm in review)

- **P-1 / P-2** Locks as in §2 rows 1–2.
- **P-6 Realised FX** = functional value relieved (carrying) − functional value of the counter-leg at the
  transaction rate → `fx_gain` (credit) / `fx_loss` (debit), the ADR-S054 accounts.
- **P-7 Unrealised FX** = existing revaluation + reversal. Settlement after a revaluation does not double count,
  because the revaluation reverses on day +1 and the carrying rate excludes it.
- **P-8 Transfers.**
  - Same currency between two foreign accounts: the foreign amount moves at the source's carrying value; no FX.
  - Cross-currency: the user states the **amount sent** (source currency) and the **amount received** (target
    currency) as the bank executed it. The source is credited at carrying value; the target is debited at
    received × target rate (exact when the target is functional); the difference is realised FX.
  - Open: should a bank spread go to a bank-charges account instead of FX? Default: FX.
- **P-9 Payments.** A payment into / out of a foreign account must be in that account's denomination (no implicit
  conversion; convert through treasury). A functional account receiving a foreign payment keeps today's behaviour
  (cash line in base at the payment rate). Outbound from a foreign account relieves the cash at carrying value; the
  one FX line nets the document leg (ADR-S054) and the cash leg.
- **P-10 Statement import.** Refused when a line's currency ≠ the account's denomination.
- **P-11 Legacy accounts (no silent conversion).**
  - An account with currency ≠ functional and any posted untagged line is **undesignated**.
  - FX treasury, payments and FX reconciliation refuse it ("Account X has balances of unknown currency; designate its
    USD balance first"). Every other behaviour stays as today.
  - **Designation** is an explicit, audited action: the user states the foreign balance at a date. The system posts
    one balanced, **base-neutral** entry on the account (source `fx_designation`):
    - Cr base-only = the account's current untagged base balance;
    - Dr in the denomination = the stated foreign amount, with that same base.
  - No historical line changes and functional totals are unchanged. From then on the account carries the stated
    foreign balance at that carrying value; revaluation recognises any gap at period end.
  - A foreign account created after the migration, with no lines, is designated at creation.
- **P-12 Out of scope (stated, refused explicitly, not silently).** POS drawers and cash sessions (functional only),
  expense / petty-cash / advance payments from foreign accounts, payroll, customer deposits / store credit in foreign
  currency, intercompany. Open: should expenses from foreign accounts join phase 2?

---

## 5. Architecture

- **No second posting engine.** Every entry goes through `PostingService.post` / `reverse` with
  `PostingRequest.Line.inCurrency` (exists) and `BASE_ONLY`. Existing validation (period / VAT locks, account rules,
  idempotency keys, advisory locks, V499 checks) stays in the path.
- **New shared helper** `accounting/application/ForeignCash` (package-private API through `TreasuryGuards`):
  - `denomination(account)`;
  - `designated(account)`;
  - `carrying(account, asOf)` under the treasury lock;
  - `relieve(account, foreignAmount)` → base;
  - `inflow(account, foreignAmount, rate)` → tagged line.
- **Callers changed:**
  - phase 2: `CashFlowService`, `TreasuryService`;
  - phase 3: `TenderAccount`, `PaymentService`, `StockPostingService.postPurchasePayment`;
  - phase 4: `BankReconciliationService`;
  - phase 5: `RevaluationService`.
- **`PostingService` snapshot:** one row per distinct (fromCode, toCode) among the entry's tagged lines, with the rate
  and source from each resolution.
- **Tenancy and audit:**
  - every new query is filtered by `organizationId`;
  - designation, locks and transfers write `AuditService` records;
  - new routes carry permissions (`treasury:transfer`, `account:update`).
- **Money routes** (designation, FX transfer) are `@Idempotent(required = true)`.

### 5.1 Migrations (no change to historical rows)

Built as **V510** (M-1, M-3) and **V511** (M-2); V500 is A4 and V501–V509 belong to the GL automation, compliance and
contractor branches.

| # | Change |
|---|---|
| M-1 | `JournalEntryFxRate`: replace the entry-unique index with unique (`journalEntryId`, `fromCode`, `toCode`). |
| M-2 | `FxRevaluation`: unique key (org, period, account, `currencyCode`); existing rows already carry `currencyCode`. |
| M-3 | `Account`: add `"currencyDesignatedAt"` TIMESTAMP and `"currencyDesignationEntryId"` TEXT (null = functional or undesignated). |
| M-4 | No backfill and no conversion. `GET /accounting/fx/accounts` lists foreign accounts with their state (designated / undesignated, untagged base balance) for the accountant. |

---

## 6. Phases (independently testable; review stop after each)

### Phase 1 — Currency and accounting foundation

- Functional currency lock (both endpoints); account denomination lock.
- `ForeignCash` read side, precision validation.
- M-1, M-3; multi-pair snapshot in `PostingService`.
- Legacy detection (`GET /accounting/fx/accounts`) and the designation action (`POST /accounting/fx/designations`).
- ADR-S075 draft; `docs/spec/accounting.md` deviations.
- **Tests:**
  - unit: rate direction, rounding, carrying rate, empty-balance relief, decimals;
  - IT: the locks refuse after a posting and allow before; legacy account detected; designation entry is
    base-neutral, balanced, audited and idempotent; multi-pair snapshot;
  - single-currency regression: a fixed set of existing flows produce identical lines.

### Phase 2 — Foreign-currency treasury

- Deposit / withdrawal on designated foreign accounts (counter-leg at the transaction rate; outflow at carrying with
  realised FX); funds check in the denomination.
- Same-currency and cross-currency transfers (P-8).
- Treasury lists show foreign amount and running balance in the denomination.
- Expenses / petty cash / advances refuse foreign accounts explicitly.
- **Tests:** deposit → withdrawal at another rate (realised FX); transfer USD→USD; USD→functional; functional→USD;
  USD→EUR (two snapshots); insufficient foreign funds; undesignated account refused; period-lock respected.

### Phase 3 — Customer and supplier payments

- `TenderAccount` allows designated foreign bank / mobile-money accounts when the payment currency = denomination
  (POS and drawers keep refusing).
- Cash line tagged; outbound relieved at carrying; FX netted; voids reverse.
- PO pay from a foreign account (when the PO currency = denomination) or refused — owner call.
- **Tests:** customer receipt into a USD bank (invoice at 3,700, receipt at 3,750); supplier payment from a USD bank
  carried at 3,600 (document and cash FX); partial payment; void; functional-account behaviour unchanged.

### Phase 4 — Foreign-currency bank reconciliation

- Statement import currency check.
- Summary, statement, candidates and auto-match in the denomination.
- Lock statement unchanged.
- Undesignated accounts keep today's behaviour with a warning flag.
- Coordinate with `phase/acc-gl` (ADR-S073 bank rules also change `BankReconciliationService`): rebase on it first if
  it merges earlier.
- **Tests:** import USD statement; match receipts / payments / transfers; outstanding and unrecorded items;
  reconciled = true in USD while the base differs; a mismatched-currency import is refused.

### Phase 5 — Period-end revaluation and financial reporting

- M-2; revaluation of foreign bank balances.
- Reversal and later settlement without double counting.
- Designation gap recognised.
- Account ledger and treasury reports show the foreign column.
- Realised vs unrealised FX visible (by source type) in the P&L detail.
- **Tests:**
  - revalue a USD bank at month end, reversal on day +1, withdraw afterwards — total FX = realised only;
  - AR and AP in two currencies on one account revalued separately (M-2);
  - trial balance and balance sheet balance in functional; statements unchanged for a single-currency organization.

---

## 7. Acceptance matrix (owner brief → tests)

| Lifecycle | Phase | Test class (planned) |
|---|---|---|
| Foreign-currency deposit | 2 | `FxTreasuryIT.deposit` |
| Withdrawal with realised FX | 2 | `FxTreasuryIT.withdrawalRealisedFx` |
| Same / cross-currency transfers | 2 | `FxTreasuryIT.transfers*` |
| Customer receipt into a foreign bank | 3 | `FxPaymentsIT.customerReceipt` |
| Supplier payment from a foreign bank | 3 | `FxPaymentsIT.supplierPayment` |
| Bank reconciliation in denomination | 4 | `FxBankReconciliationIT` |
| Realised FX differences | 2, 3 | above |
| Period-end revaluation (+ reversal, no double count) | 5 | `FxRevaluationLifecycleIT` |
| Legacy account designation, no silent conversion | 1 | `FxFoundationIT` |
| Single-currency behaviour unchanged | every phase | existing suites (§8) |

## 8. Regression strategy

- Every phase runs the existing accounting suites on a fresh database:
  - accounting: `AccountingIT`, the Acc0 classes, `Acc7TaxIT` / `Acc71TaxIT`, `SharedFxIT`, `ReportingIT`,
    `BankReconciliationIT`;
  - sub-ledgers: `InvoicingIT`, the Procurement classes, the POS classes, `SyncIT`, the expenses classes,
    `FiscalYearIT`, `FinancialStatementsIT`, `AccountingDayIT`, `GetRouteSweepIT`.
- A golden-line check posts representative functional flows and compares every line field with the pre-change values.
- The full `./gradlew build` runs in CI on push (owner rule).
- Parity: the new routes and fields are additive; existing routes keep their contracts except the two documented locks.

## 9. Docs and ADRs affected

- **New ADR-S075** "Foreign-currency treasury" (policy §2–§4).
- **ADR-S054 addendum:** cash line tagging for foreign accounts, outbound relief at carrying, netted FX.
- **ADR-S070:** no change (dates as today).
- **Specs:** `docs/spec/accounting.md` deviations (locks, statement import refusal, new routes); `docs/spec/invoicing.md`
  (payments from foreign accounts).
- **Status docs:** `docs/PORT_STATUS.md` row per phase; the review doc keeps R-8 open until independent verification.
- **Operations:** `docs/OPERATIONS.md` (rate maintenance; how to designate legacy accounts).
- **Web:** treasury forms (amount sent / received, currency display), bank-rec currency labels, designation screen.
  These are client changes, listed per phase for owner OK.

## 10. Open questions for the owner

1. Lock the functional currency once entries exist (deviation from Nest)? **Recommended: yes.**
2. Designation as the only way to bring legacy foreign accounts into FX treasury? **Recommended: yes.**
3. Bank spread on cross-currency transfers: FX gain / loss, or a bank-charges account? **Default: FX.**
4. Expenses / petty cash / advances from foreign accounts: refuse for now, or add to phase 2? **Default: refuse.**
5. PO pay (`/purchase-orders/:id/pay`) from a foreign account in phase 3, or refused? **Default: allowed when the PO
   currency = the account's denomination.**
