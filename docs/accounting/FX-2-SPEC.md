# FX-2 implementation specification (owner decisions 2026-11-20)

- **Status: draft for owner approval.** Nothing in this spec is built.
- Inputs:
  - [FX gain/loss audit](../audits/2026-11-20-fx-gain-loss-audit.md);
  - [ADR-S081 (FX-1)](../adr/ADR-S081-fx-gain-loss-correctness.md);
  - ADR-S054, ADR-S076, ADR-S080;
  - the owner's six policy decisions of 2026-11-20.
- Scope: workstreams A–G below. Decision 2 (receipt-date rate) is in this spec as workstream G, even though the audit
  plan called it FX-3, so all six decisions are specified together. Reporting (FX-4) and automatic rate fetch (FX-5)
  follow later. IAS 29 (hyperinflation) is a separate accounting assessment, out of scope.

## 0. Non-negotiable constraints

1. **`PostingService` stays the sole GL writer.** Every posting goes through `PostingService.post` / `reverse`, with
   periods, locks, idempotency keys and audit.
2. **One FX engine.** All rate resolution goes through `CurrencyService`. Carrying value goes through `DocumentFxRates`
   (documents) and `ForeignCash` (cash). The settlement math currently inside `PaymentService.fxSettlement` is
   extracted into `accounting/application/FxSettlementMath` (pure functions) and reused by payments, expenses,
   deposits and refunds. No module computes exchange differences on its own.
3. **No historical rewrite.** Migrations add columns and tables only. Existing postings, documents, stock costs and
   rates keep their values. Classification of historical records is by explicit review, never by blanket update.
4. **No production revaluation or deployment** without separate owner authorisation.
5. Tenancy on every new table (`organizationId` + RLS like V320). Audit on every policy, classification and approval
   change. Money in `BigDecimal`, 6 dp `HALF_UP`, foreign amounts within `Currency.decimalPlaces`.

## A. Functional currency (decision 1)

**Rule:** each company has a functional currency. It is mandatory before any financial posting and locked after the
first one.

- `PostingService.post` refuses when the organisation has no currency: "Set the company's functional currency before
  posting". Today it silently falls back to "USD".
- `FunctionalCurrencyLock.assertChangeable` also refuses clearing the currency (null or blank). Today an explicit null
  is accepted.
- The lock trigger stays "a posted or reversed journal entry", which is the first financial posting.
- Remove the backend fallbacks that only fire for a null org currency: `"USD"` in `PostingService`, `ForeignCash`,
  `PurchaseOrdersService`, CRM, fleet and print context; `"UGX"` in `ExpensesService` and `SalesMobileMoneyService`.
  Each becomes a read of the now-guaranteed currency.
- **Migration V584:** `ALTER TABLE "Organization" ALTER COLUMN "currencyCode" SET NOT NULL`, guarded. A `DO` block
  raises a clear exception listing the offending org codes if any row is null, so the deploy stops instead of guessing.
  Live today: 0 of 2 orgs are null.
- Bootstrap (`POST /organizations/bootstrap`) already sets a currency (default USD). The web onboarding must require an
  explicit choice. No silent default for new companies in the UI.

**Acceptance criteria**
- A company with a null currency cannot post.
- The currency can't be set to null.
- The currency can't change after the first posting.
- The migration fails loudly on a null row.

**Tests:** `FunctionalCurrencyIT`: null refusal on post, clearing refused, lock after the first posting,
bootstrap requires a currency.

## B. Rate types and policies (decision 4)

**Model**
- `CurrencyRate.rateType`: one of `official | bank | cash | market`, default `official`. The unique key becomes
  `(coalesce(org,''), fromCode, toCode, asOf, rateType)`.
- Rate approval:
  - Columns `status` (`pending | approved | rejected`), `approvedBy`, `approvedAt`.
  - Existing rows and rows from shared seeds are backfilled as `approved`. This is not a reclassification: they were
    already in use.
- New table `FxRatePolicy`, one row per (org, context):
  - Contexts: `sales`, `purchase`, `goods_receipt`, `expense`, `payment_in`, `payment_out`, `treasury`, `deposit`,
    `revaluation`, `opening_balance`.
  - Columns: `rateType`, `requireApprovedRate` (bool), `stateRateTolerancePct` (numeric, nullable), `allowStatedRate`
    (bool).
  - Defaults are seeded per org from settings: `official` for every context, approved rates required, stated rates
    allowed with no tolerance check. This keeps today's behaviour.
- `JournalEntryFxRate` gains `rateType`, `context`, `policyRate` (the table rate at the time), `overrideReason`,
  `approvedBy`. Today it stores `source` (document/table/carrying) only.

**Resolution**
- `CurrencyService.resolve(from, to, stated, date, context)` is a new overload; the old signature maps to the context
  `general`.
  1. A stated rate is allowed when `allowStatedRate`. If it deviates from the policy rate by more than the tolerance,
     it needs permission `currency:rate_override` plus an `fxRateOverrideReason` field on the request (additive).
     Otherwise it is refused: "The stated rate 3,950 differs from the bank rate 3,700 by more than 2%; give a reason
     (permission currency:rate_override)".
  2. Otherwise the policy's `rateType` is used: latest **approved** rate at or before the date (or any rate when
     approval is not required).
  3. Otherwise refused, with the existing message plus the rate type.
- Maker-checker on rates: a user cannot approve a rate they entered (`SegregationOfDutiesService`).

**Routes (additive)**

| Route | Purpose |
|---|---|
| `POST /currencies/rates` | Gains `rateType` |
| `GET /currencies/rates` | Gains `rateType`, `status` filters |
| `POST /currencies/rates/{id}/approve` | Approve a rate |
| `POST /currencies/rates/{id}/reject` | Reject a rate |
| `GET /fx-rate-policies` | Read policies |
| `PUT /fx-rate-policies/{context}` | Permission `currency:policy` |

**Web**
- The Currency page gets a rate-type tabbed list, pending approvals, and a policies tab.
- Money dialogs show the resolved rate and its type, and ask for a reason on override.

**Migration V585:** the rate columns and backfill, `FxRatePolicy` with RLS, the snapshot columns, and the new
permissions in `packages/shared` (regenerate `Permissions.java`).

**Acceptance criteria**
- Each context resolves its configured rate type.
- An unapproved rate is ignored when approval is required.
- An over-tolerance stated rate is refused without permission and a reason, and accepted with both.
- Every foreign entry's snapshot shows type, context, policy rate and override reason.
- Default policies reproduce today's results exactly: all existing FX ITs stay green unchanged.

**Tests:** `FxRatePolicyIT` (per context, approval, tolerance, SoD on approval, tenant isolation),
`CurrencyServiceResolveTest` (extended).

## C. Monetary configuration and partner-level revaluation (decision 6)

**Model**
- `Account.fxRevaluation`: one of `inherit | monetary | non_monetary`, default `inherit`.
- Category defaults (a new `AccountCategory.monetaryDefault`, set in the contracts catalog):
  - **Monetary:** cash, bank, mobile money, petty cash, receivable, payable, tax, current and non-current liabilities
    (loans), and the refundable-deposit account (D).
  - **Non-monetary:** prepaid expense (as in FX-1), inventory, fixed assets, deferred revenue, equity, income and
    expense, and the advance-deposit account.
- An effective-monetary SQL function replaces the category-flag filter in `RevaluationService.compute`.

**Transaction-level validation (in `PostingService`, posting time)**
- A foreign-tagged line on a monetary **control** account (AR, AP, refundable deposits) must carry a `partnerId`:
  "Foreign-currency receivable lines need a partner".
- A foreign-tagged line on a functional cash account is refused unless its source is in the base-only allow-list
  (revaluation, designation). This closes the FX-1 defect class at the engine.

**Partner-level revaluation**
- `compute` groups by (account, currency, partner) for accounts that carry partners. It posts one line per partner
  with `partnerId`, plus one gain/loss line per sign.
- `FxRevaluation` gains `partnerId`. The unique key becomes (org, period, account, currency, coalesce(partner,'')).
- `booked()` filters by partner.
- AR/AP aging in functional currency then ties to the GL on revaluation dates.

**Migration V586:** account column, category default column and catalog update, `FxRevaluation.partnerId` and its
unique index. History is not restated: old revaluation rows stay as they are. The first run after deploy computes
per-partner from the original lines, which works because carrying excludes revaluations.

**Preview warnings, which block the run unless acknowledged with `acknowledgeWarnings: true` (additive)**
- Foreign lines on monetary control accounts with no partner (legacy).
- Open unclassified foreign deposits (D).
- Accounts set `monetary` that have no foreign lines.

**Acceptance criteria**
- Loans in USD are revalued.
- Prepaid, inventory and advance deposits never are.
- The AR subledger per partner equals the GL after revaluation.
- The reviewer sequence still ties.
- Per-partner lines are re-run idempotent.

**Tests:** `FxRevaluationPartnerIT` (two customers in one currency; aging tie-out; loan; prepaid; legacy
partnerless warning).

## D. Deposits: advance vs refundable (decision 3)

**Model**
- `CustomerDeposit.kind`: one of `advance | refundable`, nullable. Null means **unclassified**, which every existing
  row is.
- New mapping `customer_refundable_deposit` (template account 2176 "Refundable customer deposits", monetary category).
  `customer_deposit` stays the advance account.
- New deposits must state `kind` (additive request field):
  - Sales-order deposits and down-payment invoices default to `advance`.
  - Rental and security-deposit flows default to `refundable`.
  - The record-advance dialog asks.

**Accounting**
- `advance`: unchanged ADR-S080 behaviour (receipt rate, never revalued, realised FX on application or refund).
- `refundable`: monetary. It is posted to the refundable account with partner and currency, revalued at period end
  (C), and refunded with FX at its carrying value. It cannot be applied to invoices unless it is first converted to an
  advance (an explicit action with audit; the transfer is at carrying value, no FX).

**Historical review (no blanket classification)**
- `GET /customer-deposits/classification-review` lists unclassified deposits with their source, order, currency and
  open amount.
- `POST /customer-deposits/{id}/classify` takes `{kind, reason}`, needs permission `deposit:classify`, and is audited.
- Classifying an open deposit as `refundable` posts a transfer from 2175 to 2176 at carrying value, through
  `PostingService`, with source `deposit_classification`.
- Closed deposits are classified for reporting only; nothing is posted.
- Unclassified **open foreign** deposits produce a revaluation warning (C). Unclassified functional deposits never
  affect FX.
- The rental module's `RentalDeposit` (subledger only, `double` math) is listed as a follow-up. It is not
  reclassified here.

**Migration V587:** `kind` column (nullable, no default), new mapping and template account for new orgs, and for
existing orgs the account is created on first use (`ensureByCode`). Permission `deposit:classify`.

**Acceptance criteria**
- New deposits always have a kind.
- Existing rows stay null until reviewed.
- Refundable foreign deposits are revalued per partner.
- Advances are not.
- The classification transfer leaves no FX and is reversible only through a reclassification.

**Tests:** `FxDepositKindIT` (both kinds through receipt, revaluation, application or refund; review queue;
classification transfer; refusal to apply a refundable deposit).

## E. Expenses funded from functional or foreign accounts (decision 5)

Remove the ADR-S076 decision-4 refusal (`ForeignCash.assertFunctional`) for expense payments, expense advances and
petty-cash replenishment when the account is held in the **expense's** currency. Paying from an account held in a
third currency stays refused.

**Pay request (additive)**
- `exchangeRate`: the payment-date rate, resolved by context `expense` when absent.
- `foreignAmount`: required for a foreign expense paid from a foreign account.
- `amount` keeps its meaning (functional) for functional accounts.

**Three cases, all through `FxSettlementMath`**

| Case | Cash side | Expense / payable side | FX |
|---|---|---|---|
| Accrued USD expense paid from a functional account | Base = foreign × pay rate, untagged | Payable relieved at its carrying rate (accrual) in USD | Difference realised |
| Accrued USD expense paid from a USD account | Tagged USD, relieved at `ForeignCash` carrying value | As above | Netted into one realised line |
| Immediate USD expense from a USD account | Relieved at carrying value | Expense recognised at the transaction (spot) rate | Cash-disposal gain or loss (reviewer issue 6) |

- Funds are checked in the account currency (`ForeignCash.assertFunds`).
- Expense advances and petty cash in foreign currency are held at carrying value and settle with realised FX the same
  way.

**Acceptance criteria**
- The reviewer's example passes: USD cash carried at 5,000, a $500 expense at 5,500, gives expense 2,750,000, cash
  relieved 2,500,000 and FX gain 250,000.
- An accrued expense paid later at a new rate realises FX.
- No functional cash line is ever tagged.
- Void reverses everything.

**Tests:** `FxExpenseFundingIT` (all three cases, partial payments, void, advance and petty cash in USD,
third-currency refusal).

## F. Advances, refunds, credit-note allocation, loans, opening balances (needed to make A–E complete)

- **Advance allocation:** `POST /payments/{id}/allocations` (`payment:allocate`, idempotent) applies an unallocated
  remainder to same-currency documents. The advance is relieved at its payment rate and the document at its carrying
  rate; the difference is realised FX.
- **Credit-note allocation:** `allocate()` posts realised FX (credit-note rate vs invoice carrying rate) like
  `realiseFx`.
- **Foreign credit refunds:** an outbound refund from an account held in the credit's currency, allocated against the
  credit note. The credit is relieved at its carrying rate and the cash at carrying value, with realised FX. This lifts
  the FX-1 refusal.
- **Foreign loans:** `loan_received` / `loan_repayment` on a foreign account tag the liability line with currency and
  partner. Monetary per C, so it is revalued; repayment relieves at carrying value with FX.
- **Opening balances:** `POST /opening-balances` accepts `currencyCode` + `exchangeRate` (context `opening_balance`)
  for AR/AP documents and for foreign bank or cash lines, creating tagged lines and documents in that currency.

**Tests:** `FxAdvancesRefundsIT`, `FxLoansOpeningIT`.

## G. Goods receipt at the receipt-date rate (decision 2)

**Rule:** stock and the GRNI / AP liability for a foreign PO receipt are recognised at the **receipt-date** rate
(context `goods_receipt`). The PO's own rate stays as a planning or commitment value and is **not** rewritten.
Historical receipts keep the rate they were posted at.

**Model:** `GoodsReceiptNote.exchangeRate`, `rateSource`, `currencyCode`. Existing rows are backfilled from their PO
(the rate they were actually costed at). This records history and changes no value.

**Postings**
- At receipt: Dr Stock / Cr GRNI at the receipt rate. The receipt voucher posts Dr GRNI / Cr AP, tagged in the PO
  currency, at the receipt rate.
- At the bill: `remeasureVoucheredPayable` uses the **receipt's** stored rate instead of the PO rate. The difference
  to the bill rate goes to FX on AP and never to stock (the IAS 21 / IAS 2 split).
- At PO pay (`/purchase-orders/{id}/pay`): a payment-date rate (context `payment_out`), relief at the receipt or bill
  carrying rate, realised FX. `PurchasePayment` gains `exchangeRate`, `carryingRate`, `fxAmount`.
- Receipts without a PO can state a currency (optional; else functional), so their bills compute variance correctly.

**Migration V588:** GRN rate columns plus backfill, `PurchasePayment` rate columns.

**Acceptance criteria**
- The PO is at 3,700, goods are received when the rate is 3,750, and the bill is at 3,800:
  - stock is 20 × 3,750 and is unchanged afterwards;
  - AP is remeasured to 3,800 with FX 1,000;
  - the payment at 3,820 realises 400;
  - COGS uses the 3,750 cost.
- Historical receipts are unchanged.
- No stock revaluation on rate change.

**Tests:** `FxGoodsReceiptRateIT`, extended `ProcurementIntegrityIT`, `PoBillApplicationIT`.

## Migration strategy

| Version | Content | Data effect |
|---|---|---|
| V584 | Functional currency NOT NULL (guarded) | None. Fails loudly on a null |
| V585 | Rate types, rate approval, `FxRatePolicy`, snapshot columns, permissions | Existing rates backfilled `official` / `approved`; default policies reproduce current behaviour |
| V586 | `Account.fxRevaluation`, category monetary defaults, `FxRevaluation.partnerId` | None. Old revaluation rows untouched |
| V587 | `CustomerDeposit.kind` (nullable), refundable-deposit mapping, `deposit:classify` | Existing deposits stay unclassified |
| V588 | GRN rate columns (backfilled from PO), `PurchasePayment` rate columns | Records the rate actually used; no value change |

- Each migration is additive and forward-only.
- Each workstream is its own PR (`phase/fx2-<a…g>`) with its ITs green on `fastTest` and full CI on push.
- Order: **A → B → C → D → E → F → G.** B and C come first because everything else resolves rates through B and is
  revalued through C.
- **Before enabling on an org with foreign history (for example GQDEMO):**
  1. Run the read-only diagnostic script (FX-1 evidence §5) for that org.
  2. Present the findings.
  3. The owner approves any correcting journal individually.

## Overall acceptance (FX-2 done)

- The 12 audit scenarios plus the extras (audit §D) pass as ITs with non-integer rates. Each asserts:
  - balanced journals;
  - foreign and functional amounts;
  - account directions;
  - subledger = GL per partner;
  - audit rows;
  - idempotent replay.
- `ModularityTests` / `ArchitectureTests` clean.
- JaCoCo ≥ 80 % on the touched `application` packages.
- No route renamed or removed. All new fields additive. Deviations recorded in `docs/spec/accounting.md` and the
  module specs.
- Existing FX ITs (`SharedFxIT`, `FxPaymentsIT`, `FxDepositsIT`, `FxTreasuryIT`, `Acc0FxIT`, `FxGainLossFx1IT`,
  `LandedCostAuditIT`) green without weakening assertions.

## Open questions for the owner

1. Rate-override tolerance default: none (today's behaviour) or a percentage (e.g. 2%) per context?
2. Should rate approval be required from day one for all contexts, or only `revaluation` and `opening_balance`?
3. Template account codes 2176 (refundable deposits): acceptable, or do you have a chart-of-accounts code to use?
