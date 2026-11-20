# ADR-S082 — FX-2: functional currency, rate types and policies, monetary scope, deposits, expenses, receipts

- Status: **in progress** on branch `phase/fx2`. Owner decisions 1–6 of 2026-11-20 are the basis. Spec:
  [FX-2-SPEC](../accounting/FX-2-SPEC.md). Workstreams land as one commit each; this ADR records each one when built.
- Builds on: [ADR-S081](ADR-S081-fx-gain-loss-correctness.md) (FX-1), ADR-S054, ADR-S076, ADR-S080.
- Extension: no NestJS counterpart. New routes and request fields are additive; deviations are listed in
  `docs/spec/accounting.md`.
- Constraints kept: `PostingService` stays the only GL writer; `CurrencyService`, `DocumentFxRates`, `ForeignCash` and
  `PaymentService.fxSettlement` are extended, not replaced. No historical posting is modified.

## A. Functional currency (decision 1) — built

- `PostingService.baseCurrency()` and `ForeignCash.functional()` refuse "Set the company's functional currency before
  posting" instead of falling back to USD.
- `FunctionalCurrencyLock` refuses clearing the currency: "The functional currency is required". The existing lock
  after the first posting is unchanged.
- V584: refuses to migrate while any organisation has no currency (listing them), keeps `NOT NULL`, and adds
  `CHECK (btrim("currencyCode") <> '')`.
- Not done in A: the other `"USD"` / `"UGX"` fallbacks in other modules are left in place. They can no longer fire
  because the column cannot be null or blank. The web onboarding still pre-selects a currency.

## B. Rate types, approval and policies (decision 4) — built

- `CurrencyRate` gains `rateType` (`official|bank|cash|market`), `status` (`pending|approved|rejected`), `approvedBy`
  and `approvedAt`. Existing rows become `official` + `approved`. The unique key now includes the rate type. A new
  `CHECK (rate > 0)` is added `NOT VALID`, so existing rows are not re-checked.
- `FxRatePolicy` (organisation, context) has RLS. Contexts: sales, purchase, goods_receipt, expense, payment_in,
  payment_out, treasury, deposit, revaluation, opening_balance.
- **Defaults when a context has no row:** official rate, approval not required, stated rates allowed, no tolerance.
  This reproduces pre-FX-2 results exactly (open question 1–2 defaults: approval off, no tolerance). The spec's
  "approved rates required by default" is equivalent today, because every existing rate is approved.
- A new rate is `pending` when any policy of the company requires approved rates of that type, otherwise `approved`.
  Approve and reject follow maker-checker: the person who entered a rate gets 403.
- Resolution: `CurrencyService.resolve(…, context)` / `resolveStated(…, context)` / `policyRate(…)`.
  - A stated rate is refused when the policy disallows stated rates.
  - A stated rate beyond the tolerance needs permission `currency:rate_override` plus a reason.
  - Otherwise the policy's rate type is used: approved rates only when the policy requires approval; rejected rates
    are never used.
- **Override reason transport (deviation from the spec):** the HTTP header `X-Fx-Rate-Override-Reason`, read by
  `FxRateOverrideFilter`, instead of an `fxRateOverrideReason` field on every money DTO. This keeps all request bodies
  unchanged (forbidNonWhitelisted).
- Context wiring:
  - `PostingService` maps the posting source to a context: vendor bill, debit note and landed cost → purchase; invoice,
    credit note, POS and cash sale → sales; goods receipt → goods_receipt; expense; customer deposit → deposit; cash
    flow and treasury → treasury; fx_revaluation → revaluation; opening_balance.
  - Payments resolve as `payment_in` or `payment_out`, and now pass their rate as a leg snapshot, so a foreign payment
    records its rate.
  - Treasury, bank matching, deposits, revaluation, PO, debit note, landed cost and `DocumentFxRates` pass their
    context.
- `JournalEntryFxRate` gains `rateType`, `context`, `policyRate`, `overrideReason` and `approvedBy` (the overriding
  user).
- Routes:
  - `POST /currencies/rates` gains `rateType`.
  - `GET /currencies/rates` gains the `rateType` and `status` filters, and each row gains id, type, status, createdBy,
    approvedBy and shared.
  - `POST /currencies/rates/{id}/approve|reject` (`currency:approve`).
  - `GET /fx-rate-policies` (`currency:read`).
  - `PUT /fx-rate-policies/{context}` (`currency:policy`).
- Permissions `currency:approve`, `currency:rate_override` and `currency:policy` are granted to Administrator in V585.
  `sales:deposit:classify` is added to the contract now and granted in V587 (D).
- Web, Currency page: a rate-type selector for entry and list, a pending-approval card with Approve and Reject, and a
  policies table. Money dialogs do not yet show the resolved rate type or ask for a reason; that is left for later.

## C. Monetary scope and partner-level revaluation (decision 6) — built

- `Account.fxRevaluation` (`inherit|monetary|non_monetary`, V586).
- **Category defaults live in code**, because the global category catalog is seeded at startup, after migrations
  (`RevaluationService.MONETARY_CATEGORIES`): cash, bank, mobile money, petty cash, receivable, payable, tax, current
  and non-current liability. Everything else is non-monetary.
- `NON_MONETARY_MAPPINGS` makes three accounts inherit as non-monetary whatever their category:
  - `customer_deposit` (advance, IFRIC 22);
  - `grni_accrued`;
  - `landed_cost_clearing`.

  The two clearing accounts mix receipt-time base lines with bill-time foreign lines; their open payable is revalued
  on AP. Workstream G revisits GRNI once receipts are valued at the receipt-date rate.
- Change from the old scope (reconcilable or cash-equivalent categories, minus prepaid): foreign **liabilities**
  (loans, accruals) are now revalued. Prepaid stays out.
- Revaluation groups by (account, currency, partner):
  - one entry per (account, currency), with one line per partner (`partnerId`) plus one gain line and one loss line;
  - `FxRevaluation.partnerId` and its unique key include the partner;
  - `booked()` filters by partner, so a re-run is idempotent per partner.

  History is not restated.
- Engine validation (`ForeignCash.assertForeignLines`, called by `PostingService`), revaluation and designation
  entries exempt:
  - a functional cash, bank or mobile-money account refuses a foreign-currency line: "Account X is held in UGX; a USD
    line cannot post to it";
  - a foreign line on a receivable or payable account needs a partner.
- Warnings, which block the run unless the run is sent with `acknowledgeWarnings: true`:
  - partnerless foreign lines on AR/AP;
  - accounts set monetary that have no foreign lines.

  Unclassified deposits are added in D.
- Routes:
  - `POST /fx-revaluations/warnings`;
  - `GET /fx-revaluations/accounts`;
  - `PUT /fx-revaluations/accounts/{id}` (`currency:policy`);
  - `POST /fx-revaluations` gains `acknowledgeWarnings`;
  - preview lines gain `partnerId` / `partnerName`.
- Web, period end: warnings with an acknowledge checkbox, and a partner column. There is no web editor yet for the
  per-account setting (the API only).
- Tests changed because they posted foreign lines onto a functional bank, or onto AR/AP without a partner, which the
  engine now refuses:
  - `Acc0FxIT`: AR/AP with partners;
  - `Acc0FinancialReportingIT`: a bank held in the currency;
  - `ReportingIT`: a EUR bank;
  - `FxTreasuryIT.twoCurrenciesOneAccount`: a partner.

## D. Deposits: advance vs refundable (decision 3) — built

- `CustomerDeposit.kind` (`advance|refundable`, nullable), plus `classifiedAt`, `classifiedById` and
  `classificationReason` (V587). Existing rows stay **NULL (unclassified)**: there is no blanket classification.
- New deposits always get a kind. `POST /customer-deposits` gains an optional `kind`, defaulting to `advance`, so
  existing clients are unchanged. Sales-order, down-payment and cash-sale deposits are always advances. A sales-order
  deposit cannot be refundable.
- Refundable deposits post to account 2176 "Refundable Customer Deposits" (mapping `customer_refundable_deposit`,
  category current liability, created on first use). Being monetary, they are revalued per partner by C.
- A refundable deposit cannot be applied: "Deposit X is a refundable deposit; classify it as an advance before applying
  it to an invoice". Refunds relieve the deposit's own account at its rate; the cash side is relieved at carrying
  value, with realised FX as before.
- Review and classification:
  - `GET /customer-deposits/classification-review` lists unclassified deposits.
  - `POST /customer-deposits/{id}/classify {kind, reason}` (`sales:deposit:classify`, idempotent, audited) moves an
    open balance between the advance and refundable accounts at carrying value. Source `deposit_classification`, no
    FX. A closed deposit is classified for reporting only.
  - Reclassifying is the way back. A reason is required. Cash-sale deposits stay advances.
- An unclassified open foreign deposit adds a revaluation warning.
- Permission name: `sales:deposit:classify`, granted to Administrator in V587. The spec said `deposit:classify`; the
  deposit permissions already live under `sales:deposit:*`.
- Web, Customer deposits: a kind choice when recording, a kind line in the list, a "Needs classification" filter and
  count, a Classify dialog, and Apply hidden for refundable deposits.
- Not done: the rental module's `RentalDeposit` (subledger only, `double` math) is a follow-up, as the spec says.

## E. Expenses funded from functional or foreign accounts (decision 5) — built for expense payments

- The ADR-S076 refusal is lifted for expense payments, both `POST /expenses/{id}/pay` and CASH expenses paid on
  creation. An account held in currency X may pay X expenses. Paying a functional expense from it, or an expense in a
  third currency, is refused: "Account … is held in USD; it can only pay USD expenses".
- The pay request gains `exchangeRate` (the payment-date rate; when absent, resolved under the `expense` rate policy)
  and `foreignAmount` (units in the expense currency). `amount` keeps its functional meaning. Expense amounts stay in
  functional currency at the expense's own rate, so `amountPaid` and the residual are unchanged. A foreign payment
  relieves `foreignAmount × expense rate`, and the last payment clears the exact residual.
- The entry posts in base:

  | Case | Expense / payable side | Cash side | Difference |
  |---|---|---|---|
  | Accrued, from a functional account | Relieved at the expense rate, tagged | Foreign × pay rate, untagged | Realised FX |
  | Accrued, from an account held in the expense currency | Relieved at the expense rate, tagged | Tagged, carrying value (`ForeignCash.relieve`) | Realised FX |
  | Paid at recognition, from a currency account | Recognised at the expense rate | Carrying value | Cash-disposal gain or loss |
  | Paid at recognition, from a functional account | At the expense rate | At the expense rate | No FX (same moment) |

  Funds are checked in the account currency for currency accounts.
- Void reverses the payment entries as before.
- Web, expense pay dialog: "Amount in CCY" and "Rate on payment date" for foreign expenses.
- **Not done in E:** expense advances, petty-cash replenishment and shift banking from foreign accounts still refuse
  (`ForeignCash.assertFunctional`). Planned as a follow-up. `FxSettlementMath` was not extracted; the settlement
  lives in `ExpensesService.fxPay`, using `ForeignCash` and `CurrencyService` with no new engine.
- Interaction with C: a foreign CREDIT expense without a supplier accrues to accounts payable without a partner. The
  engine now refuses that ("Foreign-currency payable lines need a partner"), so a foreign credit expense needs a
  supplier or an employee claimant.

## F. Advances, credits, loans and opening balances — built (refunds excepted)

- **Advance allocation.** New route `POST /payments/{id}/allocations` with body
  `{allocations: [{documentId, amount}]}`, permission `payment:allocate`, idempotent. It allocates a posted receipt's
  or supplier payment's unallocated remainder to open invoices or bills of the same partner and currency.
  - Each allocation records its carrying rate, settlement rate and FX on `PaymentAllocation`.
  - For a foreign payment, the realised difference between the advance (at the payment rate) and the documents' carrying
    value posts as a base-only adjustment on the partner's AR / AP line against FX gain / loss
    (source `payment_allocation`). The line is tagged with the currency and a zero foreign amount, so the currency
    bucket ties.
  - Voiding the payment also reverses these entries.
- **Credit-note allocation.** `POST /credit-notes/{id}/allocate` now realises FX when a foreign credit settles an
  invoice carried at another rate (`credit_note_fx`, one entry per allocation). Credit-note reversal already reverses
  every `credit_note_fx` entry.
- **Foreign loans.** A `loan_received` / `loan_repayment` cash-flow operation on a currency account tags the liability
  line with the currency, so the loan is a USD liability and revalued under C. A repayment relieves the liability at its
  carrying value. The difference from the cash's carrying value is realised FX.
- **Foreign opening balances.** `POST /opening-balances` account, customer and supplier lines accept `currencyCode` and
  `exchangeRate` (additive). The value is taken at the stated rate, else the table on the opening date (context
  `opening_balance`). Account lines are tagged with the currency. Customer and supplier balances are invoices / bills
  in that currency.
- **Not done in F:**
  - foreign credit refunds. The FX-1 refusal stays: a foreign credit must be allocated, not refunded, until a
    currency-aware refund path exists.
  - a loan paid from a functional account against a foreign liability.
  - web screens for the allocation route and the opening-balance currency fields (API only).
- Test: `fx2/FxAdvancesIT`:
  - advance $100 at 3,700 on an invoice at 3,800 → loss 10,000, AR clear in both currencies, void restores FX;
  - credit $40 at 3,800 on an invoice at 3,700 → 4,000, AR 60 / 222,000;
  - loan 1,000 at 3,700, repay 400 from a bank carried at 3,750 → loss 20,000, loan 600 / 2,220,000;
  - opening bank 500 @3,700, customer 200 @3,700, supplier 300 @3,750.
- `InvoicingIT` route matrix 79 → 80.

## G. Goods receipt at the receipt-date rate (decision 2) — built (core)

- A foreign PO receipt values stock, GRNI and the vouchered AP at the **receipt-date rate**. That is the rate table on
  the receipt date under the `goods_receipt` rate policy, **else the PO rate** (`rateSource = 'po'`), so receiving is
  never blocked by a missing rate. Both receive paths apply it: `POST /purchase-orders/{id}/receive` and the
  goods-receipt post.
- The PO's own rate is not rewritten. Stock is never revalued afterwards.
- V588 adds `GoodsReceiptNote.currencyCode`, `exchangeRate` and `rateSource`. Existing posted foreign receipts are
  backfilled from their PO (`rateSource = 'po'`): the rate they were actually costed at, so history is recorded and no
  value changes.
- At the bill, `remeasureVoucheredPayable` uses the receipt's stored rate (PO rate for older receipts). The difference
  to the bill rate goes to FX on AP, never to stock.
- A cash-PO settlement at receipt converts at the receipt's rate. The PO pay route measures what is owed at the
  receipts' value-weighted carrying rate (`carryingRate`) instead of the PO rate, so AP raised at receipt rates clears
  without residue.
- **Not done in G:**
  - the PO pay route still credits cash at that carrying rate. It has no payment-date rate and no realised FX
    (`PurchasePayment` rate columns). Paying through a vendor bill and `/supplier-payments` realises FX as before.
  - receipts without a PO cannot state a currency.
- Test changed: `ProcurementIntegrityIT.poReceiptThenForeignBillAtAnotherRate` now covers the G case. PO at 3,700, the
  receipt at the table rate 3,750 (stock +75,000, receipt rate recorded, PO rate unchanged), the bill at 3,800 (FX
  loss 1,000, AP 76,000, stock unchanged).

## Tests

- `FunctionalCurrencyIT`: clearing refused; changeable before and locked after the first posting; blank refused by
  the database.
- `FxRatePolicyIT`: defaults and tenant isolation; a bank-rate purchase policy with its snapshot; pending ignored,
  enterer refused, second person approves; tolerance refusal, then a header override with the reason snapshotted;
  stated rates forbidden; validation.
- Unchanged and green: `CurrencyServiceResolveTest`, `FxGainLossFx1IT`, `SharedFxIT`, `FxPaymentsIT`, `FxDepositsIT`,
  `FxTreasuryIT`, `Acc0FxIT`, `ForeignCashTest`, `LandedCostAuditIT`, `ProcurementIntegrityIT`.
- `FxRevaluationPartnerIT`:
  - two customers revalued per partner, with subledger per partner = GL, idempotent re-run and reversal;
  - a loan revalued while prepaid and a non-monetary override are not;
  - engine refusals;
  - legacy partnerless lines block the run until acknowledged.
- `FxDepositKindIT`: refundable vs advance (own account, apply refused, revalued per partner, refund clears at
  carrying value; advance never revalued); review queue, warning, reason required, classification transfer with no
  FX, reclassification back.
- Deposit regression unchanged and green: `FxDepositsIT`, `CashSaleIT`, `SalesBillingIT`, `SalesDeliveryIT`,
  `SalesOrderPolicyIT`, `CustomerDepositReceiptPdfTest`.
- `FxExpenseFundingIT`:
  - the reviewer example (USD cash carried at 5,000 pays $500 at 5,500: expense 2,750,000, cash 2,500,000, gain
    250,000);
  - accrued from USD cash;
  - accrued paid later from a functional bank in two parts at 5,800 (losses of 12,000 and 18,000, bank untagged),
    then void;
  - third-currency refusal;
  - funds check in the currency.
- Expense regression green: `ExpensesFlowIT`, `ExpenseAccrualIT`, `ExpenseClaimsIT`, `ExpenseControlsIT` (on a
  fresh DB), `ExpenseTreasuryIT`, `FleetFuelIT`, `FleetIncidentsIT`, `InvoicingIT` (authorization count +2 for the
  D routes), `Acc0PostingDateIT`, `ControlAccountRoutesIT`, `BankLedgerMatchIT`.
- Procurement regression green for G (fresh DB): `ProcurementIntegrityIT`, `ProcurementIT`, `ProcurementControlIT`,
  `PoBillApplicationIT`, `ProcureToPayGapsIT`, `SourceToPayEndToEndIT`, `ReceivingQualityIT`, `LandedCostVoucherIT`,
  `LandedCostGapsIT`, `LandedCostAuditIT`, `ImportLandedScenarioIT`, `SupplierClaimSettlementIT`,
  `SupplierPaymentScheduleIT`, `ValuationGapsIT`, `ValuationIntegrityIT`, `VendorBillListIT`, `IntercompanyIT`,
  `ReadinessBlockersIT`, `SharedFxIT`, `FxPaymentsIT`, `FxGainLossFx1IT`, `Acc0ArApIT`, `Acc0LifecycleIT`,
  `Acc0MoneyPrecisionIT`, `Acc0ReversalIT`, `Acc8PostingIntegrityIT`, `DocumentReversalIT`.
