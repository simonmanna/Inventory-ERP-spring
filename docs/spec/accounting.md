# Accounting, treasury and cash — `accounting`

| | |
|---|---|
| Phase | P2 |
| Nest source | `reference/nest-api/src/modules/accounting` |
| Routes | 110 — full table: [generated/accounting.md](generated/accounting.md) |
| Feature flag | always on |
| Java package | `com.inventoryerp.accounting` |

## Purpose

Chart of accounts and categories, account mappings (posting keys → accounts), journals and journal entries (post/reverse), cost centers, currencies and FX rates/revaluation, fiscal period close/lock/reopen, bank accounts, treasury transfers, bank reconciliation, financial reports and snapshots, money operations (`accounts/cash-flow`), POS payment methods, cash registers, cash sessions (shifts), tender settlements.

## Functional areas

| Area | Endpoints / scope |
|---|---|
| chart of accounts | `/accounts` CRUD, tree, reorder, deprecate; `/account-categories` read + usage; `/account-mappings` registry/missing/PUT |
| journals | `/journals` CRUD; `/journal-entries` CRUD + post + reverse; posting engine in `accounting/posting` |
| periods | `/fiscal-periods/:id/close|lock|reopen` (period close checks) |
| fx | `/currencies`, rates, revaluation (`FxRevaluation`) |
| bank | `/bank-accounts`, `/treasury/transfer` (idempotent), `/bank-reconciliation` import/match/unmatch/status |
| reports | `/reports/accounting/*` (trial balance, GL, account ledger, P&L, balance sheet (+detailed), cash flow, tieout, pos-gl-reconciliation, rebuild snapshots), `/reports/export/*.csv|pdf`, `/reports/inventory/valuation|gl-tieout`, `/reports/dashboard-kpi|dashboard-activity` |
| money operations | `/accounts/cash-flow`: overview, activity, operation types, transactions, deposit, withdraw (idempotent) — `money-activity*`, `money-overview`, `money-settlement` |
| payment methods | `/accounts/pos-payment-methods` CRUD (tender → account) |
| cash registers | `/cash-registers` CRUD + drawer balance |
| cash sessions | `/cash-sessions/*`: open, close, movement, force-close, banking, reconcile, daily report/reconcile, variance, expected, movements, history, tender-settlements |

## Business rules to reproduce exactly

1. Journal entry must balance (Σdebit = Σcredit, `approxEqual` 0.0001) and date must fall in an open period; `postingKey` unique per org makes postings idempotent; reversal creates a mirrored entry linked by `reversalOfId`.
2. Account mappings: every automatic posting resolves accounts through mapping keys (`packages/shared/src/accounting/account-mappings.ts`); missing mapping → error listing the key (verbatim).
3. Period close/lock: closed periods reject postings; lock is irreversible except `reopen` with permission `fiscal_period:reopen`.
4. Cash session open: register must have its own active drawer cash account; opening count may not be below the drawer ledger balance (unless `autoAdjust`); added float requires a funding account (safe/bank) with sufficient recorded funds and a reason.
5. Movements: `pay_in | pay_out | adjustment`, non-zero amount, reason, counterpart account, cannot overdraw the drawer, only the session owner (or manager); pay_out requires manager sign-off (`approvedById`/`managerPin`); corrections to a closed shift = adjustment with `correctionOfSessionId` and permission `cash_session:correct`.
6. Close: expected cash computed per payment method; counted cannot be negative; a variance needs a reason and approval from a holder of `cash_session:approve_variance` (manager PIN); `autoAdjustCash` records the difference as pay-in/pay-out; closing first drains pending stock postings and is blocked by open sync dead letters; variance and bank deposit post to GL.
7. Force-close: requires `cash_session:force_close`, a reason, and must target another cashier's shift. Any holder of `cash_session:close` may close an open shift on a shared terminal (recent change, commit 93c3414).
8. Handover (`/pos/shift/handover`): different incoming cashier; approving manager must be neither cashier and hold `cash_session:approve_variance`.
9. Closing amounts visibility is controlled by a setting (commit 17126b5) — respect it in responses.
10. Status machine: `open → closed → reconciled`.

Error messages: copy verbatim from the "Error messages" table in the generated spec.

## Events

`journal.posted`, `journal.reversed`, `cash.received`, `cash.paid`, `bank.transfer`, `cash.session.opened/closed`, …

## Scheduled jobs

report snapshot cron (`snapshot-cron.worker.ts`).

## Notes and pitfalls

- Port `accounting/treasury` specs (5) and `accounting/posting` specs (3) first — they pin the cash rules.
- Trial balance / P&L queries are heavy SQL: port the raw SQL as-is via `JdbcClient`.

## Porting checklist

- [x] Read every file under "Source files to port" in the generated spec
- [x] Entities tuned (collections/locks/query methods) — generator marker removed where edited
- [x] DTO records with Bean Validation mirroring class-validator rules
- [x] Services: rules above + verbatim errors + same transaction/locking boundaries
- [x] Controllers: same paths/verbs/status/permissions/response shapes
- [x] Events published/consumed as in Nest
- [x] Jest specs ported (module unit specs); integration tests; authz + tenant isolation tests
- [ ] Parity scenarios `contract-tests/scenarios/accounting-*.json` — written, not yet run against Nest
- [ ] Web screens for this module work against Spring (no console errors) — not yet checked
- [x] `docs/PORT_STATUS.md` updated

## Deviations

Foreign-currency treasury (R-8, [ADR-S076](../adr/ADR-S076-foreign-currency-treasury.md), owner-approved 2026-11-16):

- **Functional-currency lock.** `PATCH /organizations/me/settings` (`currencyCode`) and `PUT` company settings
  (`baseCurrencyCode`) refuse a change once entries are posted: "The functional currency cannot change once entries
  are posted". Nest allowed it at any time.
- **Account-currency lock.** `PATCH /accounts/:id` and `PATCH /accounts/cash-flow/:id` refuse a `currencyId` change
  once the account has postings.
- **Foreign cash accounts in the engine.** A line on a designated foreign cash, bank or mobile-money account must be in
  its currency ("Account X is held in USD; post it in USD"). Deposits, withdrawals and transfers on such accounts take
  amounts in that currency and post realised FX.
- **Restrictions.** Expenses, expense advances, petty-cash replenishment and shift banking refuse foreign accounts.
  Statement import into a foreign account refuses lines in another currency. Reconciliation of a foreign account runs
  in its currency.
- **Additions.** New routes `/fx-treasury/accounts|designations|summary`; additive request fields `exchangeRate`
  (deposit / withdrawal) and `receivedAmount` / `exchangeRate` (transfer).
- **Revaluation.** It covers cash-equivalent accounts too, and records one row per (period, account, currency).

Post-parity hardening (owner-approved 2026-11-10): opening shortage approval (`adjustmentReason` /
`approvedById` / `approverEmail` / `managerPin` on `POST /cash-sessions/open`), auto-adjusted close keeps the variance
review, `cash.varianceApprovalThreshold` setting, approver-PIN lockout through `CredentialGuard`, ambiguous-shift
error, per-branch close lock and open-order check, handover carries open orders. Details: `docs/spec/pos.md`
"Post-parity hardening".

Pending owner approval:

0. **Accounting day = organisation-local calendar day** (ADR-S070). Nest buckets the ledger by UTC day: period
   control, entry-number year and every ledger report compare `postingDate` against UTC day bounds. Here
   `Organization.timezone` decides: a bare report date is a local day, fiscal-period / year / VAT-settlement /
   books-lock bounds are local wall clock, and a date-only posting (UTC midnight) is stored at local noon of its date.
   A Kampala sale at 22:30 UTC on 31 January is a 1 February entry. UTC organisations are unchanged (every
   conversion is the identity).
1. **`GET /reports/accounting/pos-gl-reconciliation`**: `inventory.jobCounts` values are numbers and
   `unpostedJobs` is their sum. Nest groups with `_count: { _all: true }`, so in production each value is
   a `{ "_all": n }` object and `unpostedJobs` becomes the string `"[object Object]00"` (the note then
   always says every job has posted). The Jest spec mocks `_count` as a number, which is the behaviour
   kept here.
2. **`GET /reports/export/invoice/:id.pdf`** is rendered with OpenPDF instead of PDFKit: same text,
   order and alignment, different bytes and font metrics. Like Nest, the id still comes from `?id=`
   (the path segment is ignored) and a missing `id` renders the organization's first document.
3. **Unhandled errors** (an invalid date in a report query, `to` before `from` on POS → GL
   reconciliation, a non-numeric `limit` on dashboard activity) answer 500 like Nest, but the
   development-only `error` detail text comes from Java rather than Prisma.

Approved by the owner (`phase/inv-hardening`, [inventory deviation](inventory.md#deviations) 20):

4. **Inventory GL tie-out date basis + daily check.** `GET /reports/inventory/gl-tieout` and the historical
   `GET /reports/inventory/valuation?asOf=` date stock ledger rows by their source's inventory journal posting date
   (else `createdAt`); job `inventory-gl-tieout-check` (daily 02:40 UTC) raises an `InventoryException`
   (`gl_tieout_variance`) + in-app alert above `inventory.glTieOut.tolerance` (setting `inventory.glTieOut.enabled`).

Same as Nest, kept on purpose:

- `PATCH /cash-sessions/:id/reconcile` writes `notes` as sent. The `guard_cash_session` trigger lets a
  closed shift's notes only be appended to, so a body whose `notes` does not start with the stored notes
  fails with 500, exactly as in Nest. Omitting `notes` keeps the stored value.
- Re-closing a period after `reopen` answers "Nothing to close" until new revenue/expense is posted (the
  reversal of the closing entry posts today and nets the period to zero).
- `GET /reports/export/trial-balance.csv` keeps Nest's SQL verbatim: the entry status/date filter sits in
  the `LEFT JOIN`, so lines of draft or later entries still count in the sums.
- `RevaluationService` (FX revaluation) has no route in Nest either; it is module API only.

## Accounting remediation deviations (ACC-1 … ACC-4, owner-approved 2026-11-03)

Evidence and the original defects are in `docs/accounting/ACC-0-verification.md`. The phase plan is in
`docs/accounting/ACC-EXECUTION-PLAN.md`.

**ACC-1: reporting**

- **B1.** Closing entries are left out of P&L and of every ranged movement in the reporting engine. This covers
  `sourceType` `period_close` and `fiscal_year_close`, including their reopen reversals. A closed period still reports
  its result. Point-in-time balances (balance sheet, post-closing TB) keep the closing entries.
- **B1.** `GET /reports/accounting/trial-balance` takes an optional `view=pre_closing|post_closing`. The default is
  unchanged. The `view` field is echoed only when it was asked for.
- **B10.** `GET /reports/accounting/profit-and-loss` always computes from the ledger. The cumulative `ReportPnLSnapshot`
  ignored the date range and carried no other income/expense.
- **B10.** The trial-balance snapshot answers only open-ended post-closing requests.
- **B10.** The balance sheet keeps inactive accounts that still carry a balance.
- **B2.** Fiscal-year close checks, and the `FiscalYearBalance` snapshot, use base amounts (`baseDebit`/`baseCredit`).
  Before this, a multi-currency organization could not close its year.

**ACC-2: foreign currency**

- **B4: rates per organization.**
  - V320 adds `CurrencyRate."organizationId"` (null = shared), the unique key `(org, from, to, asOf)` and RLS.
  - A tenant's `POST /currencies/rates` writes its own rate.
  - Resolution, rate lists and the rate-history report use the organization's own rate first, then the shared one.
  - Intercompany consolidation still averages across all rows; it is group-level by design.
- **B3: revaluation lifecycle.** Owner decision C1, simplified:
  - Each adjustment posts at `asOf` with a linked automatic reversal on the next day.
  - The carrying basis is the original foreign-currency lines only.
  - A re-run for the same date posts only the difference. Amounts are BigDecimal.
  - The adjustment hits the monetary account itself; a separate valuation-adjustment account is a later option.
  - New routes (extension, Nest has none): `GET /fx-revaluations`, `POST /fx-revaluations/preview` (`currency:read`)
    and `POST /fx-revaluations` (`currency:update`), body `{asOf}`.
- **B14.** Expense accrual and payment entries post in the expense's currency and rate. Line amounts are base ÷ rate;
  `PostingService` converts back and writes the FX snapshot.

**ACC-3: posting integrity**

- **B6: reversal-date policy (D-B6).** `PostingService.reverse` without a date reverses on the original's own date
  while its period is open; otherwise it uses today. A closed or locked period is never posted into.
  - This replaces Nest's "always today".
  - Reopening a period therefore reverses its closing entry inside that period.
- **B7: invoice cancel returns the invoice's own auto-issued stock.**
  - It posts a linked `return_in` at the issue cost (`sales_invoice_cancel`). Stock quantity, value and COGS come back.
  - The return runs once only.
  - Goods issued by a delivery are left to a credit note plus return.
  - A repeated cancel keeps Nest's 200 echo (D-B7b) and changes nothing.
- **B12: decimal arithmetic.**
  - Vendor-bill PO links split at 2 dp; the last link takes the remainder.
  - The legacy payroll progressive tax uses BigDecimal.
- **P1-a.** Posts with the same `postingKey` serialize on a transaction advisory lock and replay the first entry. Before,
  the second caller got a 500 from the unique index.
- **B13.** A payroll run posts on its pay-period end date while that period is open, otherwise today. Expense payments
  still post today: there is no payment-date field yet.

**ACC-4: AR/AP documents**

- **B15.** A posted vendor bill's `amountResidual` is its whole open payable: the postable part plus the vouchered
  share at bill price.
  - Before, a PO-backed bill showed residual 0 (or only the price variance) because the receipt voucher had already
    raised AP. The supplier could not be paid against it, and the AP sub-ledger did not match the GL.
  - The GL postings are unchanged.
- **B9: customer credit notes.**
  - Refused when they credit more of a product than the referenced invoice sold, or more than its total, net of earlier
    credit notes.
  - Each application to an invoice is recorded in `CreditAllocation` (V321).
  - The unapplied remainder stays on the credit note's residual.
  - New extension `POST /credit-notes/:id/allocate {documentId, amount}` (`credit_note:post`, Idempotency-Key optional)
    applies open credit to a later invoice of the same customer. It posts nothing to the GL.
- **B8: debit notes.** Debit notes (non-RTV) post line tax from `taxId`: input VAT inbound, output VAT outbound. The
  AR/AP leg carries subtotal + tax, `taxAmount`/`totalAmount` are stored, and the referenced posted bill or invoice's
  residual grows by the note total.
  - The return-to-vendor path is unchanged.
  - A supplier credit (a claim that reduces AP) is still a capability gap.

**ACC-5: fixed assets (B11)**

- **Acquisition record** (first create) capitalises the asset: Dr `fixed_asset_valuation` / Cr the vendor's payable,
  or `suspense` (asset clearing) when no vendor is known.
  - The amount is the capitalised cost, else purchase + freight + install + other.
  - Removing the record reverses it. Updating it never capitalises twice.
- **Revaluation** posts the change in carrying amount. An increase goes to Cr `asset_revaluation_surplus`. A decrease
  first releases that asset's surplus, and the rest is a loss on `asset_gain_loss`.
- **Disposal** posts Dr posted accumulated depreciation and Dr proceeds (default cash), Cr cost (plus revaluations),
  with the gain/loss going to `asset_gain_loss`.
- **Depreciation entries** carry the key `asset_depreciation:<id>`.
- **`double_declining`** = 2 × the straight-line rate. Nest used 4 ×; `declining_balance` keeps Nest's 2 ×.
- **CIP capitalisation** (ADR-S074): a capital project's cost accumulates in 1690 Construction in Progress (mapping
  `construction_in_progress`). A capitalisation posts Dr `fixed_asset_valuation` / Cr CIP and creates `constructed`
  assets with an in-service date, where depreciation starts. It can be reversed while no depreciation is posted.
- **Impairment (ADR-S073).** It posts Dr `asset_impairment_loss` / Cr `accumulated_impairment`. Depreciation after an
  impairment is re-based, a reversal is capped, and disposal clears the impairment. See
  [§ ADR-S073](#adr-s073-gl-automation).
- **Not built:** units of production and per-category accounts.

**ACC-6: bank reconciliation (B5)**

- **D-B5: auto-match is always direction-aware.** A negative line takes an outbound payment, a positive line an inbound
  payment. A payment already matched in any run is never taken again.
  - Nest's unsigned rule (outgoing lines never matched; the same payment re-matched across runs) is gone.
  - `signed` is still accepted.
- **New routes (extension):**
  - `GET /bank-reconciliation/statement?bankAccountId&asOf&closingBalance` (`bank_account:read`) proves
    closing + outstanding book items = ledger + unrecorded statement items.
  - `POST /bank-reconciliation/statement {bankAccountId, asOf, closingBalance, notes}` (`bank_account:update`) locks a
    reconciled statement (V322). After that, lines on or before `asOf` cannot be imported, matched, unmatched or
    excluded.
- **Matching rules, partial and grouped matches, and bank-charge journals (ADR-S073).** See
  [§ ADR-S073](#adr-s073-gl-automation).
- **Not built:** matching a statement line to a GL line that has no payment behind it.

**ACC-7: tax and fiscal positions** (capability gaps)

- **Fiscal positions now act (V323).**
  - Each position has a map from source tax to target tax (`FiscalPositionTax`); a null target means exempt.
  - Partners carry a default `fiscalPositionId`.
  - Invoice create, update and journal preview, and documents created through the builder, resolve the position: the
    one stated on the document, else the partner's. The mapping is applied to every line's tax before the tax engine
    runs.
  - New routes (extension): `GET/PUT /fiscal-positions/:id/taxes` and `POST /fiscal-positions/:id/partners`.
- **VAT settlement:**
  - `GET /tax-settlements/preview?from&to` (`report:accounting`) shows output VAT, input VAT and the net.
  - `POST /tax-settlements {from,to}` (`journal_entry:create`, idempotent) posts Dr tax payable / Cr tax receivable for
    the input VAT and records the period (`TaxSettlement`).
  - `GET /tax-settlements` lists the settled periods.
- **Tax-period lock:** `PostingService` refuses any line on a tax account dated on or before the latest settled period
  end. Tax accounts are the output, input and withholding mappings plus each tax's own account.
- **Built since (ADR-S071, V482):**
  - tax groups;
  - several taxes per line (`taxIds`) through `TaxCalculationService`, compound and inclusive included;
  - withholding tax on vendor bills, with a residual net of withholding, `withholding_payable` and issued
    certificates;
  - withholding suffered on customer receipts (`withholding_receivable`, received certificates);
  - reverse-charge VAT on vendor bills;
  - `/withholding-certificates` routes and the filing report.
  - See [ADR-S071](../adr/ADR-S071-tax-groups-withholding-reverse-charge.md).
- **Not built:**
  - remitting withholding to the authority as a dedicated flow (today it is a journal or a supplier payment);
  - recording the partner-default position name on the document;
  - ~~an invoice cancelled after settlement still reverses on its original date when that accounting period is open~~ —
    fixed by the 2026-11-15 review (G-2): a reversal whose original date is inside a settled VAT period is dated today.

## Accounting review deviations (2026-11-15, owner-approved: "fix the above")

Findings and evidence: `docs/audits/2026-11-15-accounting-review.md`. IDs below (R-, G-, P-, U-) are that review's.
Where Nest behaves the same, the change is a deliberate deviation from the reference.

**Reports**

- **R-1 (Nest has the same bug).** A bare `YYYY-MM-DD` `to` / `asOf` is the **last** instant of that org-local day
  (`LedgerQueries.dateEnd`), so `postingDate <= to` keeps the whole end day. It applies to trial balance, P&L, balance
  sheet (summary and detailed), account and general ledger, detailed, consolidated and branch reports, POS-GL
  reconciliation, TB CSV export, tax report, statements and intercompany consolidation. Before, the end day was dropped
  and Cash Flow (end-inclusive) disagreed with P&L over the same range.
- **R-2.** Budget vs actual, the budget-control commitment check, consolidated / branch P&L, the dashboard net
  income, cost-centre and analytic reports exclude closing entries (`LedgerQueries.CLOSING_FILTER`).
- **R-4 (Nest same).** A snapshot is stale when any balance-affecting line up to `asOf` was posted after it was taken
  (`coalesce(postedAt, createdAt)`), not only when dated after it.
- **R-5 (Nest same).** The TB CSV export sums only lines whose entry passes the status / date filter.
- **R-6.** The bank-reconciliation summary's book balance counts `posted` and `reversed` entries.
- **R-7.** Auto-match and manual match take the per-account advisory lock; an auto-match update requires the line to be
  still `unmatched`.
- **R-9.** Ageing summaries, exposure and sub-ledger tie-outs add open items in base currency (`residual × document
  rate`; unapplied payments at the payment rate). Detail rows keep the document currency and amount.
- **R-10.** The tax report converts procurement debit notes at their rate.
- **R-12 (Nest same).** `GET /reports/accounting/account-ledger/:id` adds `openingBalance` (everything before `from`)
  and `normalBalance`; the running balance starts from the opening balance. Ledger rows (account and general) add
  `journalEntryId`. `GET /reports/accounting/general-ledger` accepts `accountId` and `pageSize` (1–500, default 100).
- **R-3.** VAT settlement measures output VAT on `tax_payable` **and every tax's own account**, input VAT on
  `tax_receivable`, and excludes treasury "Tax payment" entries (and their reversals). A settlement must start the day
  after the last settled period (no gaps). The settlement range ends at 23:59:59.999.

**Ledger**

- **G-1.** A foreign-currency payment's AR / AP line carries the documents' currency and the payment amount
  (`PostingRequest.Line.inCurrency`); its base stays the carrying value. FX revaluation therefore sees settled items as
  settled. A receipt voucher of a foreign PO carries the PO currency on its AP line (P0-5), and a foreign bill re-measures
  its vouchered share to the bill rate (`vendor_bill:fx_voucher:<id>`, Dr/Cr AP vs FX gain / loss; reversed on cancel).
  A foreign PO's payment (`/purchase-orders/:id/pay`) tags its AP line with the PO currency and the amount paid in it,
  so it settles that currency balance.
- **G-2.** The VAT tax-period lock also applies to staging and approving drafts and to reversals. A reversal with no
  stated date whose original date is inside a settled VAT period is dated today. A settlement entry's reversal is exempt.
- **G-3.** A period inside a closed fiscal year cannot be reopened ("reopen the fiscal year first").
- **G-4.** Fiscal-year `netIncome` excludes closing entries and their reopen reversals.
- **G-5 (Nest same).** Period reopen flips the period to open first, then reverses the closing entry, so the reversal
  keeps the closing entry's date inside the period: the period can be closed again and no closing amounts land in the
  current period.
- **G-6.** A foreign-currency entry must balance in its own currency too (tolerance 0.01). The rounding line is stored
  in base with no currency. V499 adds `NOT VALID` CHECK constraints: non-negative amounts, never debit and credit on one
  line.
- **G-7 / G-8.** Reverse and draft approval lock the entry row; approval re-checks the draft's accounts.
- **G-9.** Opening balances refuse accounts that do not allow manual posting (inventory, tax and other control
  accounts), as manual journals do.
- `POST /fx-revaluations` is `@Idempotent` (answers 200, not 201).
- **U-1.** `POST /accounts` requires `categoryId` only for a postable account (`isPostable` not `false`), with the same
  messages; a group account is created without one (Nest required it always and also forbade it on a group, so groups
  could only be made by editing). A request that fails another field's constraint does not also list the `categoryId`
  messages (validation group sequence).
- `GET /journal-entries` accepts `journalId`, `status`, `from`, `to` (inclusive org-local dates) and filters by
  organization explicitly; `GET /journal-entries/:id` too.

**Postings**

- **P-1 (Nest same).** Cancelling a vendor bill returns the stock it auto-received (`return_to_supplier`, Dr GRNI / Cr
  stock at issue cost; a cost difference clears GRNI against price variance).
- **P-3.** A foreign bill matched to a receipt without a PO converts that receipt's (functional) value at the bill rate
  before computing the price variance.
- **P-5 (Nest same).** A return-to-vendor debit note posts Dr AP gross / Cr stock, Cr input VAT, variance to purchase
  price variance (stock adjustment expense when unmapped), stores its tax and total, and lowers the referenced bill's
  open amount. Not done: debiting GRNI for unvouchered receipts.
- **P-6.** POS invoices and refunds split a group tax over its children's accounts (`PosInvoiceService.taxSplit`).
- **P-7.** A credit note must be in the credited invoice's currency; allocation refuses different currencies.
- **P-8.** Asset capitalisation credits asset clearing (`suspense`, tagged with the vendor) instead of AP; the supplier's
  bill clears it.

## Accounting controls deviations (2026-11-17, owner-approved: "fix all the above issues")

Findings: the ERP capability assessment of 2026-11-17 (risks R1–R15). Nothing here edits posted history; settled claims
and closed periods from before this change are corrected, if at all, by approved entries.

**GL integrity and periods**

- **R1 (Nest same).** `PATCH /fiscal-periods/:id` refuses `status` (400; use the close / reopen / lock routes). Dates
  change, and `DELETE` works, only on an open period with no posted or reversed entry in its old or new range. Periods
  of one org cannot overlap or end before they start (service + V512 trigger). `FiscalPeriodControl` takes the most
  restrictive covering period, and a soft-deleted closed / locked period still blocks postings.
- **R2.** V513: lines cannot be inserted into an entry posted by an earlier transaction (`JournalEntry."postedTxn"`
  stamp), and a deferred constraint trigger refuses, at commit, any posted / reversed entry whose base debits and
  credits differ. A historical unbalanced entry can no longer be reversed until corrected.
- **R7.** The evidence-purge bypass needs a superuser or the `erp_evidence_purger` role (`docs/DATABASE.md` §6).
- **R6 (partial; owner decision 2026-11-17).** Account and mapping queries carry explicit `organizationId`
  predicates. FORCE ROW LEVEL SECURITY and fail-closed policies are a separate infrastructure phase with its own
  reviewed migration plan (roles, worker context, policy normalisation, then FORCE), not part of this branch.
- **R3.** Recurring templates and deferral schedules refuse accounts that disallow manual posting and designated AR/AP
  control accounts (on save, approval and every run), and need approval by a second user holding `journal_entry:post`
  (`POST /recurring-entries/:id/approve`, `POST /deferrals/:id/approve`, V514). An unapproved post-mode template
  stages drafts; an unapproved schedule releases nothing. Existing rows start unapproved.
- **Control accounts (owner decision 2026-11-17, V518).** An account is an AR / AP control account only when
  `Account.controlAccountType` is `ar` / `ap` (seeded on 1300 / 2100; V518 backfills the accounts mapped as
  `accounts_receivable` / `accounts_payable` or used as partner receivable / payable accounts; never by name). Manual
  journal entries, recurring templates, deferral schedules, treasury deposit / withdrawal counterparts, cash-drawer
  movements and `post_entry` bank rules cannot post to them; sub-ledger postings (system context) and reversals can.
  The exception is a control-account adjustment: a manual entry with `controlAdjustment` and a mandatory reason, a
  partner on every AR/AP line, staged by a holder of `journal_entry:control_adjust` and posted by a different user
  holding `journal_entry:post` and `journal_entry:control_adjust`; both steps are audited, and reversing one also needs
  `journal_entry:control_adjust`. Setting or changing an account's designation is audited; mapping
  `accounts_receivable` / `accounts_payable` to an account designated another control type is refused.
- **Control accounts in sub-ledger documents (owner decision 2026-11-17, round 2).** A designated AR / AP account
  cannot be a user-chosen account anywhere a sub-ledger service posts: invoice, credit-note and bill lines (refused on
  create / update and again at posting, which also catches a product category or `sales_revenue` /
  `default_expense` mapping pointing at AR / AP), debit notes, expense allocations, categories and accruals, every
  payment / refund / deposit tender account, the deposit-application account, the customer write-off account and the
  Sales Settings bad-debt / returns accounts (`ControlAccountGuards`). The documents' own receivable / payable lines
  still post to the configured AR / AP, partner-tagged.
- **Write-offs through the sub-ledger.** Customer: `POST /sales/invoices/:id/write-off` (a credit note against the
  invoice, Dr a non-control write-off account / Cr AR). Supplier: `POST /vendor-bills/:id/write-off`
  (`expense:write_off`, Idempotency-Key, reason; Dr AP partner-tagged / Cr a non-control account, default
  `purchase_discount`; lowers the bill's residual; `sourceType vendor_bill_writeoff`), reversed only by
  `POST /vendor-bills/:id/write-offs/:entryId/reverse` (same permission, reason, the `document_reversal` SoD pair,
  idempotent; the amount goes back on the bill). A bill's `amountPaid` includes write-offs; the bill page shows them
  separately.
- **Document-level reversals (owner decision 2026-11-17).** `POST /journal-entries/:id/reverse` refuses an entry a
  sub-ledger document posted (`SubledgerSources`: invoices, POS sales, bills, credit and debit notes, payments,
  deposits and their applications, write-offs, claims, opening balances, goods receipts, expenses, and reversals of
  those) and names the document action. The document workflows reverse the GL and the sub-ledger in one transaction:
  invoice cancel, bill void, `POST /credit-notes/:id/reverse` (`credit_note:reverse`, unwinds credit allocations),
  `PATCH /procurement/debit-notes/:id/reverse` (`debit_note:reverse`, restores the adjusted document's residual by the
  stored `documentAdjustment`), payment void (refused while bank-matched) and
  `POST /customer-deposits/:id/applications/:paymentId/reverse`. Each needs a reason and its permission, passes the
  action's approval gate when the tenant configures one and the opt-in `document_reversal` segregation-of-duties pair,
  and writes one `DocumentReversal` row (V520, unique per document: original and reversal entries, released
  allocations, who / when / why) plus an audit row; a replay returns the first result. Invoices and bills with
  payments, write-offs, posted notes or settled claims are refused with the list of what to undo first.
- **R8.** Posting a draft re-runs the full line validation on its stored lines.

**Sub-ledgers**

- **R11 (owner decision 2026-11-17, V515, V519).** A settled supplier claim posts its own supplier credit
  (`supplier_claim:<id>`, Dr AP partner-tagged) and reduces the bill's residual, with a settlement classification:
  `price_variance` (Cr PPV on stock lines, the expense account on others, + input VAT), `expense_correction` (Cr the
  non-stock lines' expense accounts + VAT), `vat_adjustment` (Cr input VAT only, capped at the bill's unreversed VAT) or
  `stock_adjustment` (quantity lines verified by a second user; the goods leave stock through the return-to-supplier
  stock movement, Cr the stock account at issued cost, difference to PPV; Dr GRNI instead of AP for unbilled ad-hoc
  receipts; an agreed amount above the verified goods' receipt cost is credited to PPV as a price concession, so the
  whole agreed amount is credited). A claim without verified quantities never credits stock or GRNI. The bill's
  residual is lowered in the bill's own currency. No debit note is created; claims return
  `journalEntryId`. Settling against a draft or cancelled bill is refused. Legacy settlements:
  `docs/runbooks/supplier-claims-legacy-settlement.md`.
- **R12 (owner decision 2026-11-17).** A payroll run with a deduction that maps to no liability / receivable account is
  refused (400 listing the components); nothing falls back to salary expense, and a manual one-off deduction names its
  credit account when it is queued. Payroll keeps full BigDecimal precision
  internally and rounds each line once, to the currency's minor unit, when the run is calculated; gross, deductions and
  net are sums of the rounded lines, and payslips, bank files and the journal read the same stored values
  (`docs/spec/hr.md` Deviation 4).
- **R13.** Inclusive / exclusive is decided per tax, and inclusive compound taxes are backed out in compounding order.
- **R14.** Units of production depreciates by recorded usage (`/fixed-assets/:assetId/depreciation/usage`,
  `Asset.totalUnits`, V517) and never falls back to straight line; declining balance uses `AssetCategory
  .decliningFactor` (default 2.0). Each asset's register change and journal entry commit together.
- **R15.** FA-to-GL reconciliation compares register cost + revaluation and accumulated depreciation + impairment
  with the accounts fixed-asset postings use only (no CIP or intangibles).

**Treasury, FX and reporting**

- **R4.** Consolidation average rates read the parent's own then global rates only, time-weighted over the period.
- **R5.** Bank reconciliation also matches unreconciled GL lines on the bank account (`matchedJournalLineId`, V516),
  and a `post_entry` rule matches an existing payment or GL line instead of posting a duplicate.
- **R9.** Bank matching compares payments in the bank account's currency; the statement uses one org-local cut-off.
- **R10.** The direct cash-flow statement spreads cash only over opposite-side lines and reports revaluation in a new
  `effectOfExchangeRateChanges` field (IAS 7). Payments, treasury transfers and cash deposits / withdrawals honour an
  explicitly stated rate of 1 (`CurrencyService.resolveStated`; internal callers pass no rate); stored documents still
  treat 1 as "not stated" (`exchangeRate` column default).

## Accounting and costing review deviations (2026-11-17)

Owner-approved 2026-11-17 ("code & develop the above fixes & enhancements"). Audit and outcome per finding:
[`docs/audits/2026-11-17-accounting-costing-review.md`](../audits/2026-11-17-accounting-costing-review.md). The costing
items are in [costing](costing.md#review-2026-11-17--accounting-and-costing-fixes); the purchasing and bill items in
[procurement](procurement.md#deviations) and [invoicing](invoicing.md#deviations); the POS abandon route in
[pos](pos.md#deviations).

| # | Where | Before (Nest / earlier Spring) | Now |
|---|---|---|---|
| ACR-1 | `POST /fiscal-periods/:id/reopen` | took the latest `period_close` entry by date with no status filter, so a second reopen could pick the already-reversed one | the period's own posted closing entry (`sourceId`); the date window is a fallback for old entries |
| ACR-17 | period close (gate and checklist) | every pending stock document of the organisation blocked the close | only those raised up to the period end. A failed POS stock-posting job can be abandoned (`POST /pos/posting-monitor/jobs/:id/abandon`, see pos), so it no longer blocks the close for ever |

## Extensions beyond Nest (ADR-S005)

These are additive routes the owner asked for. Nest has no counterpart.
- `/budgets` (CRUD, `PUT /:id/lines`, `POST /:id/approve`, `POST /:id/reopen`, `GET /:id/vs-actual`), with
  permissions `budget:*`. Tables are `Budget` and `BudgetLine` (V8). Lines can be edited only while the budget is
  draft, and approving it locks them. Actuals are posted GL lines in each account's natural sign, filtered by the
  budget's branch or cost centre.
- `GET /reports/accounting/consolidated?from&to&statement=pl|tb` (`report:accounting`) shows each branch side by
  side, plus the consolidated total. Lines are attributed by their line `branchId`; lines without one fall into
  `unassigned`.

## Status

P2: 110/110 routes. Tests: `AccountingIT` (seed, accounts, journal entries + maker-checker, period
close/reopen/lock, journals/cost centers/currencies/mappings, authorization matrix, tenant isolation),
`TreasuryIT` (idempotent money operations, registers, full cash-session simulation against the GL),
`ReportingIT` (statements, detailed report, snapshots + tie-out, POS → GL reconciliation, inventory
valuation/tie-out, CSV/PDF exports, dashboard, FX revaluation, journal_entry workflow), `TreasurySpecsIT`
(ports of `pos-payment-method`, `cash-session` guards and the remaining `period-close`/`posting` cases),
`AccountingUnitTest` + `AccountingSpecsTest` (ports of `posting.math`, `money-activity.taxonomy`,
`org-dates`, `coa-template`, `accounting-invariant`, `pos-gl-reconciliation`, `session-reconciliation`,
`currency` specs). The listed `test/business-simulation/*` and `test/integration/*` specs drive POS,
inventory and stock-posting flows and are ported with P3–P5. Parity scenarios are written
(`accounting-ledger`, `accounting-treasury`, `accounting-reports`) but have not been run against Nest.

## ADR-S007 extensions — cost-centre accounting (roadmap 1.8)

- `CostCenter`: `parentId`, `branchId`, `category`, `description`, `managerUserId`, `validFrom`, `validTo`;
  tenancy + soft-delete filters added (**deviation / fix**: the Nest-port list was not tenant-filtered).
- Posting engine applies, per entry: split rules (`dimensions.costAllocationRuleId`), defaults (partner → branch →
  user) on revenue / expense lines, account rules (`required` / `forbidden`), validity on the posting date.
- Manual journal lines accept `branchId`, `costCenterId`, `dimensions` (additive DTO fields).
- `ensureByCode` sets `isActive` / `isPostable` explicitly on the created account (fix).

| Method | Path | Permission |
|---|---|---|
| GET | `/cost-centers/tree` | `cost_center:read` |
| GET/POST, DELETE | `/cost-centers/defaults`, `/defaults/{id}` | `cost_center:read` / `update` |
| GET, PUT | `/cost-centers/account-rules`, `/account-rules/{accountId}` | `cost_center:read` / `update` |
| GET | `/cost-centers/allocation-rules`, `/{id}`, `/{id}/preview?periodFrom&periodTo` | `cost_center:read` |
| POST/PATCH/DELETE | `/cost-centers/allocation-rules`, `/{id}` | `cost_center:allocate` |
| POST | `/cost-centers/allocation-rules/{id}/run` (Idempotency-Key) | `cost_center:allocate` |
| GET, POST | `/cost-centers/allocation-runs`, `/allocation-runs/{id}/reverse` | `cost_center:read` / `allocate` |
| GET | `/cost-centers/reports/profit-and-loss?from&to[&costCenterId]`, `/reports/matrix?from&to` | `cost_center:read` |

Tests: `CostCenterAccountingIT`. Web: Cost Centers (hierarchy fields), Cost Centre Accounting (P&L, matrix,
hierarchy, allocation rules / runs, defaults & account rules).

## Phase 4 bank reconciliation (built, unverified)

FIN-1: `GET /bank-reconciliation/{lines,runs,summary}`, `GET /bank-reconciliation/lines/{id}/candidates` (`bank_account:read`), `POST /bank-reconciliation/lines/{id}/{match,exclude}` (`bank_account:update`). `POST /bank-reconciliation/match` takes an optional `signed` flag; without it the behaviour is unchanged.
`bankAccountId` on these routes is the GL account id. Screen `/bank-reconciliation`. Test: `BankReconciliationIT`. Nothing was compiled or run.

## Phase 4 period-close checklist (built, unverified)

FIN-3: `GET /fiscal-periods/{id}/close-checklist` (`fiscal_period:read`), `PUT /fiscal-periods/{id}/close-checklist/{key}` (`fiscal_period:update`), table `PeriodCloseSignOff` (V72). The checklist does not gate `close`.
Screen `/fiscal-periods/:id/checklist`. Tests: `PeriodCloseChecklistIT`, `FinancialStatementsIT`, `core/TaxesIT`. Nothing was compiled or run.
## INT-3 bank statement files (Phase 4, built, unverified: nothing compiled or run)

`POST /bank-reconciliation/import-file` (multipart `file`; `bankAccountId, currencyCode, format` = auto, csv, mt940, camt053;
`dryRun`; `bank_account:update`) parses with `BankStatementParser` and imports through the unchanged
`BankReconciliationService.importStatement`. CSV: header row found in the first 30 rows, comma, semicolon or tab, one amount
column or debit + credit columns, day-first dates, thousands in either style. MT940: `:61:` + `:86:`, reversals flipped.
camt.053: `Ntry` elements, DTDs refused. A line without a bank reference gets a stable hash reference (date, amount, text,
occurrence in the file), so a repeat import skips it and two identical charges stay. Screen: `/accounts/bank-import`.
## Branch performance (BR-2)

Status: built, not compiled or run. `GET /reports/branches/comparison` (`report:accounting`, `BranchPerformanceService`):
ledger P&L, sales, open receivables, stock value, deliveries and production per branch plus a total. Screen:
`/reports/branches`. `ReportFilterBar` (`web/src/components/report-filter-bar.tsx`) is the shared filter row. Tests:
`BranchPerformanceIT`, `AccountingExtensionsIT.budgetByBranch`.

## ADR-S073 GL automation

Status: built 2026-11-10 (branch `phase/acc-gl`). Spring-only extensions: Nest has no counterpart. Design:
[ADR-S073](../adr/ADR-S073-recurring-deferrals-bank-rules-impairment.md). Tables (V501–V503):
`RecurringJournalTemplate`, `RecurringJournalRun`, `DeferralSchedule`, `DeferralRelease`, `BankMatchRule`,
`BankStatementMatch`, `AssetImpairment`, plus `BankStatementLine.matchedRuleId` and `BankStatementLine.journalEntryId`.

| Method | Path | Permission |
|---|---|---|
| GET | `/recurring-entries`, `/{id}`, `/{id}/runs` | `recurring_entry:read` |
| POST, PATCH, DELETE | `/recurring-entries`, `/{id}` | `recurring_entry:manage` |
| POST | `/recurring-entries/{id}/pause`, `/{id}/resume` | `recurring_entry:manage` |
| POST | `/recurring-entries/{id}/run {date?}` (Idempotency-Key) | `recurring_entry:manage` |
| GET | `/deferrals`, `/deferrals/{id}`, `/deferrals/report?asOf` | `deferral:read` |
| POST | `/deferrals`, `/deferrals/{id}/cancel` | `deferral:manage` |
| POST | `/deferrals/from-journal-line`, `/deferrals/{id}/release`, `/deferrals/run` (Idempotency-Key) | `deferral:manage` |
| GET | `/bank-reconciliation/rules?bankAccountId`, `/lines/{id}/open-payments`, `/lines/{id}/allocations` | `bank_account:read` |
| POST, PATCH, DELETE | `/bank-reconciliation/rules`, `/rules/{id}` | `bank_account:update` |
| POST | `/bank-reconciliation/rules/apply?bankAccountId`, `/lines/{id}/allocate`, `/payments/{id}/allocate` (Idempotency-Key) | `bank_account:update` |
| GET | `/fixed-assets/{assetId}/impairments` | `fixed_asset:read` |
| POST | `/fixed-assets/{assetId}/impairments`, `/impairments/reversal` (Idempotency-Key) | `fixed_asset:impair` |

**Posting keys:**
- recurring entries: `recurring:<templateId>:<date>`;
- deferral releases: `deferral:<scheduleId>:<periodIndex>`, and `deferral:<scheduleId>:initial` for the reclass;
- bank rules: `bank_rule:<lineId>`;
- impairment: `asset_impairment:<impairmentId>`.

**Jobs:** `GlAutomationJob` runs `accounting-recurring-entries` at 01:30 and `accounting-deferral-releases` at 01:40.
Both are ShedLock-guarded and gated by `app.jobs.enabled`.

**Recurring entries:**
- An occurrence is unique per template and date, so a job that runs twice or a manual re-run replays the existing
  occurrence.
- Draft mode stages a draft whose maker is the template author (maker-checker).
- A closed or locked period refuses the occurrence. The job records it as `failed` and does not advance the schedule.

**Deferrals:**
- Slices are planned when the schedule is created: `monthly` equal slices, or `daily` slices weighted by the days in
  each calendar month. Each is rounded to 2 dp and the last takes the remainder.
- Cancelling releases nothing further.
- The report compares the schedules' remaining balance with each deferral account's GL balance.

**Bank:**
- Rules run after every import (after commit, one transaction per line) and on demand.
- `post_entry` books charges, interest and fees, with optional tax included in the amount.
- `match_payment` is direction-aware (D-B5).
- Allocations support one line to several payments and several lines to one payment, partial on either side. A line
  stays `partial` until its allocations cover it.
- Outstanding book items and unrecorded statement items net the allocations.
- `unmatch` removes allocations and reverses a rule entry.
- Locked statements still freeze their lines.

**Mappings** (optional, created on first use): `asset_impairment_loss`, `accumulated_impairment`, `prepaid_expense`.

**Web:**
- Accounting has two new screens: Recurring Entries (`/recurring-entries`) and Deferrals (`/deferrals`).
- Bank Reconciliation has a matching-rules panel and a split/allocate dialog.
- The asset page has an Impairment tab.

**Tests:** `GlAutomationIT`, `GlSchedulesTest`.

