# Branch cash sales → head-office invoicing (`com.inventoryerp.sales`, CSS)

Design: [ADR-S056](../adr/ADR-S056-branch-cash-sales-central-invoicing.md). Related: [`sales.md`](sales.md),
[`sales-postings.md`](sales-postings.md) (P2 / P9 / P10 reused). Phases: [`sales-phases.md`](sales-phases.md) § CS.

Status: **CS-0 design frozen (2026-11-04)**. This is a Spring-native extension with no parity routes.

**Production gate.** Before go-live, the accountant must sign off three things:

- the VAT tax point for showroom sales;
- EFRIS fiscal-document timing (`fiscalTrigger`);
- the walk-in partner policy.

Until then, the defaults below ship and the fiscal trigger stays unset.

## 1. Flow

1. **Showroom:**
   1. A draft is created and edited freely.
   2. Payments are captured and verified.
   3. **Confirm** marks the sale complete and the goods handed over.
   4. **Submit** sends it to HO.
2. **HO:**
   1. **Start review**.
   2. Then **return** (for correction), **reject**, or **approve**.
   3. After approval, **generate invoice**. This is a separate action, not automatic.
3. **Showroom:** sees the status, the timeline, and the invoice and its PDF.

## 2. States

```
execution:   draft ──confirm──▶ confirmed ──void──▶ voided
             draft ──discard──▶ cancelled
processing:  not_submitted ──submit──▶ submitted ──start-review──▶ under_review
             under_review ──return──▶ returned_for_correction ──resubmit──▶ submitted
             under_review ──approve──▶ approved ──generate-invoice──▶ invoiced
             invoiced ──cancel-invoice──▶ approved            (attempt kept, replacement allowed)
             under_review ──reject──▶ rejected ──reopen──▶ under_review
```

| Transition | Requires | GL / stock |
|---|---|---|
| confirm | execution `draft`; every payment line `verified`; Σ payments = grandTotal; ATP at profile location; profile valid; `Idempotency-Key` | P1 + P2 + P3 (one tx) |
| submit / resubmit | execution `confirmed` | none |
| start-review | `submitted` | none |
| return | `under_review`; reason | none |
| amend | `returned_for_correction`; non-financial fields only; fiscal identity lock (§ 7) | none (amendment row) |
| approve | `under_review`; segregation | none |
| reject | `under_review`; reason; sets `ownerUserId` | none (exception queue) |
| reopen | `rejected`; `sales:cash_sale:approve` | none |
| generate-invoice | `approved`; no active invoice; `Idempotency-Key` | P4 + P5 (one tx) |
| cancel-invoice | `invoiced`; fiscal gate | P7 (one tx) |
| void | execution `confirmed`, processing ≠ `invoiced`; reason + goods-returned attestation; fiscal gate; `Idempotency-Key` | P6 (one tx) |
| discard | execution `draft` | none |

**Fields frozen after confirm:** product, qty, unit (uomId / uomFactor), unitPrice, discount, taxIds and tax amounts,
currency and rate, payment lines, locationId, and cost snapshot. A wrong figure is handled by **void** followed by a
new CSS.

**Amendable fields** (audited as `SalesSubmissionEvent` kind `amendment`, recording field, old, new, who and when):
customer name, phone, TIN, address, remarks, and attachments.

## 3. Postings

`N` = net, `T` = VAT, `C` = issue cost. Every entry carries the CSS `branchId`, the CSS currency + rate, and
`postingKey = css:<id>:<step>[:<n>]`. Duplicate keys are blocked by the DB unique index
`JournalEntry_organizationId_postingKey_key`.

| # | Trigger | Owner path | Dr | Cr |
|---|---|---|---|---|
| P1 | confirm | `StockService.issue` (source `cash_sale_line`, location = profile) | `cogs` C | `stock_valuation` C |
| P2 | confirm, per verified payment | `CustomerDepositService.receiveForCashSale` (source `cash_sale`, `cashSaleId`) | profile tender account | `customer_deposit` |
| P3 | confirm | `BranchSaleRecognitionService.accrue` (`DocumentBuilderService.groupForPosting`) | `unbilled_receivable` N+T | revenue N (category / `sales_revenue`), output VAT T (tax account / `tax_payable`) |
| P4 | generate-invoice | `postInvoice` with `data.revenueAccruedBy` + `stockIssuedBy` | `accounts_receivable` N+T | `unbilled_receivable` N+T |
| P5 | generate-invoice | `CustomerDepositService.apply` (linked invoice only) | `customer_deposit` | `accounts_receivable` |
| P6 | void | reverse P3; `StockService.receiveReturn` at P1 cost; refund P10 or re-tag deposit as plain advance | reversal | reversal |
| P7 | cancel-invoice | unapply P5 (void deposit-application payment), then `cancelDocument` reverses P4 | reversal | reversal |

**Worked example.** N 1,000,000, T 180,000, C 700,000, paid in cash.

| Step | Entries |
|---|---|
| Confirm | Dr unbilled 1,180,000 / Cr revenue 1,000,000, Cr VAT 180,000 · Dr branch cash 1,180,000 / Cr deposit 1,180,000 · Dr COGS 700,000 / Cr stock 700,000 |
| Invoice | Dr AR 1,180,000 / Cr unbilled 1,180,000 · Dr deposit 1,180,000 / Cr AR 1,180,000 |
| Balances | AR 0, deposit 0, unbilled 0, revenue −1,000,000, VAT −180,000, COGS 700,000, branch cash 1,180,000 |

**Guards:**

- **Invoice total.** At generate-invoice, the invoice total must equal the P3 total, in both transaction and
  functional amounts. Otherwise 400 `Invoice total does not match the accrued sale`.
- **Replacement invoice.** A replacement after cancel-invoice reuses the P3 accrual. It never reposts P1, P2 or P3.
- **Credit exposure.** For each CSS, the unapplied `cash_sale` deposit and the unbilled accrual net to zero. Credit
  exposure therefore excludes both.

## 4. Account validation (profile save and every confirm)

| Account | Expected `ReportSection` |
|---|---|
| tender: cash, MTN, Airtel, bank clearing | `current_assets` |
| `unbilled_receivable` (new key, code 1215 "Unbilled Receivables", created on first use) | `current_assets` |
| `stock_valuation` | `current_assets` |
| `customer_deposit` | `current_liabilities` |
| output VAT (`tax_payable` / tax account) | `current_liabilities` |
| revenue (`sales_revenue` / category income) | `revenue` |
| `cogs` | `cogs` |

Each account must also be active, postable (not a group account), and in the same organization. The profile
location must be an active `InventoryLocation` with the profile's `branchId`.

## 5. Payments

- **Methods** (first release): `cash`, `mobile_money` (`mtn` | `airtel`), `bank`. Any other method, or a method whose
  profile account is null, returns 400.
- **Tender account.** Always taken from the profile. The client cannot set it.
- **Verification status:** `pending` → `verified` | `failed`. Each verification records `verifiedById`, `verifiedAt`
  and `verificationSource`:
  - cash: `confirmer_cash`;
  - MoMo: `momo_gateway` (`MobileMoneyService` success);
  - MoMo or bank: `verifier`, which needs `sales:cash_sale:verify_payment`.
- **Segregation.** With `requireSegregation` on:
  - cash verifier ≠ collector, and verifier ≠ confirmer;
  - confirmer ≠ approver ≠ invoicer.
  A violation returns 403.
- **Settlement.** `settlementStatus` (`unsettled` | `settled` | `remitted`) is stored now and driven by CS-5.
- **References.** Unique (org, provider, reference) when the reference is not null.

## 6. Data (migration `V3xx__sales_cash_sales.sql`; number picked above other sessions)

- `BranchSalesProfile`:
  - keys: `organizationId`; `branchId` (unique per org).
  - mode: `invoicingMode` (check `= 'head_office'`).
  - accounts: `locationId`, `cashAccountId`, `momoMtnAccountId`, `momoAirtelAccountId`, `bankClearingAccountId`.
  - defaults: `walkInPartnerId`, `numberPrefix`, `requireSegregation`, `fiscalTrigger` (null | `at_confirm` |
    `at_invoice`), `rejectedAlertDays` (default 7).
- `SalesSubmission`:
  - identity: `number` (unique per org; `SequenceService` key `css:<branchId>:<year>`), `branchId`, `locationId`.
  - customer: `partnerId` plus the snapshot `customerName`, `customerPhone`, `customerTin`, `customerAddress`.
  - sale: `salespersonId`, `saleDate`, `currencyId`, `exchangeRate`, `subtotal`, `discountTotal`, `taxTotal`,
    `grandTotal`, `paidTotal`.
  - status: `executionStatus`, `processingStatus`.
  - actors: `{confirmed,submitted,reviewStarted,approved,rejected,voided}{At,ById}`, `ownerUserId`.
  - links: `accrualJournalEntryId`, `currentInvoiceId`.
  - other: `remarks`, `version`.
- `SalesSubmissionLine`: `productId`, `uomId`, `uomFactor`, `quantity`, `unitPrice`, `discountPct`, `taxIds`,
  `lineSubtotal`, `lineTax`, `lineTotal`, `costSnapshot`, `stockMoveId`.
- `SalesSubmissionPayment`: `method`, `provider`, `amount`, `reference`, `accountId`, `customerDepositId`,
  `collectedById`, `collectedAt`, `verificationStatus`, `verifiedById`, `verifiedAt`, `verificationSource`,
  `settlementStatus`.
- `SalesSubmissionInvoice`: `attempt`, `invoiceId`, `status` (`active` | `cancelled`), `cancelledAt`,
  `cancelledById`, `cancelReason`, `fiscalDocumentId`. Partial unique index on (`submissionId`) WHERE
  `status = 'active'`.
- `SalesSubmissionEvent`: timeline and audit. Columns: `action`, `fromExecution`, `toExecution`, `fromProcessing`,
  `toProcessing`, `reason`, `field`, `oldValue`, `newValue`, `userId`, `at`, `snapshot` jsonb.
- `FiscalDocument`: `submissionId`, `invoiceId`, `operation` (`issue` | `cancel` | `correct`), `requestRef`
  (unique), `status` (`pending` | `issued` | `unknown` | `failed` | `cancellation_pending` | `cancelled`),
  `attempts`, `lastError`, `externalRef`, `fiscalNumber`, `requestPayload`, `responsePayload`, `reason`, `userId`.
  Unique (`organizationId`, `submissionId`, `operation`) WHERE status is not `failed` / `cancelled`.
- `CustomerDeposit`: adds `cashSaleId`, `branchId`, and the source value `cash_sale`.
- Mapping key `unbilled_receivable`.
- RLS on every new table, permissions and grants (Administrator gets all).
- DMS resource type `sales_submission`.

## 7. Fiscal (EFRIS) lifecycle

- The `FiscalDocumentPort` SPI has three operations: `issue`, `cancel`, `correct`, plus `lookup(requestRef)`. The
  default adapter is `none`, which records `issued` with no external call when `fiscalTrigger` is null.
- Calls go through the transactional outbox, never inside the business transaction.
  `requestRef = css:<id>:<operation>:<attempt>`.
- **Timeout or unknown response:** status becomes `unknown`. Recovery first calls `lookup`. It re-sends only when the
  provider confirms the request was not received. A retry never creates a second fiscal document.
- **Gates:**
  - Void or cancel-invoice with an `issued` fiscal document first requests `cancel`. The internal reversal runs only
    after `cancelled`.
  - If the cancel fails, the status is `cancellation_pending`. Retries are bounded and raise an alert. Nothing is
    reversed internally.
  - Amending customer name, TIN or address after `issued` returns 409 `Fiscal document issued — correct it first`.
    It is allowed again after `correct` succeeds. The original snapshot is always kept.

## 8. Rejected sales

A rejected sale stays fully posted: revenue, VAT, COGS and deposit stand.

- **Ownership.** `ownerUserId` is the rejecting user. It can be reassigned with `sales:cash_sale:approve`.
- **Exception queue.** The HO dashboard has a queue of rejected and uninvoiced sales showing age, reason and owner.
- **Alert.** Raised after `rejectedAlertDays` days, via the alerts module.
- **Resolution.** `reopen` now; RMA and credit note later.

## 9. API (`/sales/cash-sales`)

| Verb | Path | Permission | Notes |
|---|---|---|---|
| GET | `/sales/cash-sales` | `read` (own branch scope) / `read_all` | filters `branchId`, `executionStatus`, `processingStatus`, `from`, `to`, `salespersonId`, `q`; page envelope as other sales lists |
| POST | `/sales/cash-sales` | `create` | draft; branch from scope |
| GET / PATCH | `/sales/cash-sales/{id}` | `read` / `create` | PATCH draft only |
| POST | `/{id}/payments`, `/{id}/payments/{pid}` (DELETE) | `create` | draft only |
| POST | `/{id}/payments/{pid}/verify` | `confirm` (cash) / `verify_payment` | |
| POST | `/{id}/confirm` | `confirm` | Idempotency-Key |
| POST | `/{id}/submit`, `/{id}/resubmit` | `submit` | |
| POST | `/{id}/amend` | `submit` | non-financial fields |
| POST | `/{id}/start-review`, `/{id}/return` | `review` | |
| POST | `/{id}/approve`, `/{id}/reject`, `/{id}/reopen` | `approve` | |
| POST | `/{id}/generate-invoice` | `invoice` | Idempotency-Key |
| POST | `/{id}/cancel-invoice` | `invoice` | Idempotency-Key; reason |
| POST | `/{id}/void` | `void` | Idempotency-Key; reason + attestation |
| POST | `/{id}/discard` | `create` | draft only |
| GET | `/{id}/timeline` | `read` | |
| GET | `/sales/cash-sales/dashboard?scope=branch\|head_office` | `read` / `read_all` | tiles + exception queue |
| CRUD | `/sales/branch-profiles` | `sales:branch_profile:manage` | validation § 4 |

Permissions are `sales:cash_sale:{read,read_all,create,confirm,submit,review,approve,invoice,void,verify_payment}`
and `sales:branch_profile:manage`.

Events (payload `v: 1`): `sales.cash_sale.confirmed|submitted|returned|approved|rejected|invoiced|voided|invoice_cancelled`.

## 10. Required tests (written before CS-2 code; `CashSaleIT` and friends, `ApiTestSupport`)

Each ledger test asserts the following with `SalesLedgerAssertions`:

- the trial balance is 0;
- AR, deposit and unbilled are 0 after a fully paid invoice;
- revenue, VAT and COGS each appear exactly once per CSS;
- the branch tender account equals the amount collected.

| # | Scenario | Expect |
|---|---|---|
| T1 | Full cash sale end to end | worked example balances |
| T2 | Full MoMo (gateway verified) | as T1, MTN clearing debited |
| T3 | Mixed cash + MoMo + bank | one P2 per line |
| T4 | Discount + multi-line rounding | totals HALF_UP; equality guard passes |
| T5 | Several tax categories | VAT per tax account |
| T6 | Foreign currency; rate changes before invoice | invoice inherits rate; unbilled 0; no FX difference |
| T7 | Insufficient stock at profile location | 400, no rows posted |
| T8 | Unverified MoMo / unverified bank | confirm 400, no GL rows |
| T9 | Card method | 400 |
| T10 | Client sends tender accountId | ignored; profile account used |
| T11 | Profile: income account as tender; deposit mapping to an asset; location in another branch; missing mapping | profile save 400 |
| T12 | Mapping drifts after profile save | confirm 400 |
| T13 | Confirm replay (same Idempotency-Key) / same postingKey | one set of entries |
| T14 | HO return → amend TIN → resubmit | no GL / stock change; amendment rows |
| T15 | Reject after delivery | stock and GL unchanged; exception queue lists it with owner and age |
| T16 | Reopen → approve → invoice | normal invoice path |
| T17 | Concurrent generate-invoice ×2 | exactly one active invoice |
| T18 | Invoice amount mismatch (tampered line) | 400 |
| T19 | Cancel invoice → replacement invoice | attempt 1 cancelled + attempt 2 active; one P3, P1 and P2 total; balances 0 |
| T20 | Cancel invoice atomic (forced failure after unapply) | full rollback |
| T21 | Void before invoice, refund / keep as advance | P3 reversed, stock back at P1 cost, deposit refunded or re-tagged |
| T22 | Cash-sale deposit applied to another invoice of walk-in partner (all paths) | 400 |
| T23 | Cash-sale deposit refund / void via `/customer-deposits` | 400 |
| T24 | Fiscal issue timeout, then provider accepted | `unknown` → lookup → `issued`, one document |
| T25 | Fiscal cancel fails on cancel-invoice | `cancellation_pending`, no internal reversal; retry → one reversal |
| T26 | TIN amend after fiscal issue | 409 until `correct` succeeds |
| T27 | Segregation on: collector verifies own cash / confirmer approves | 403 |
| T28 | Cross-branch user; branchId override in body | 403 / 404 |
| T29 | Tenant isolation (org A vs org B) | 404 |
| T30 | 403 per route without permission; missing Idempotency-Key | 403 / 400 |
| T31 | Partial deposit apply + refund (deposit service regression) | subledger = GL |

## Deviations
None yet.
