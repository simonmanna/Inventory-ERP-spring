# ADR-S056 — Branch cash sales with head-office invoicing

- Status: accepted (owner, 2026-11-04, plan rev 4). VAT tax point and EFRIS timing still need accountant sign-off.
  That sign-off is a **production gate**, not a build gate.
- Spec: [`docs/spec/cash-sales.md`](../spec/cash-sales.md). Phases CS-0 … CS-4 are in [`sales-phases.md`](../spec/sales-phases.md).
- Builds on: [ADR-S011](ADR-S011-sales-o2c.md) (sales never posts GL) and S6 customer deposits (`CustomerDepositService`).
- Extension: no NestJS counterpart. This adds new routes only and leaves existing route contracts unchanged.

## Context

A showroom (branch) holds its own stock. It sells to walk-in and account customers, who pay on the spot (cash,
MTN / Airtel mobile money, bank transfer) and take the goods away. The official invoice must come from head office
(HO), and HO wants to review every sale before it invoices.

Two simpler designs were rejected:

1. **Showroom posts an invoice.** HO would lose control of official invoicing and numbering.
2. **HO invoice is the first financial event.** Revenue, VAT, stock and cash would wait on HO paperwork. If HO then
   re-entered the sale, revenue, payment or stock would be posted twice.

The existing code also has gaps that this feature has to deal with:

- `CustomerDepositService.apply` checks only that the partner matches, so any invoice of that customer can consume a
  deposit.
- `postInvoice` always posts revenue and tax, and it auto-issues stock from the org's first warehouse.
- `InvoiceService.create` drops the branch for users without a branch restriction.

## Decision

### 1. A dedicated document

The new document is the **Cash Sales Submission** (`SalesSubmission`, number `CSS-<prefix>-<yyyy>-nnnnn`), owned by
`sales`. It has two status dimensions:

- **execution**: `draft`, `confirmed`, `voided`, `cancelled`
- **processing**: `not_submitted`, `submitted`, `under_review`, `returned_for_correction`, `approved`, `invoiced`,
  `rejected`

### 2. One financial event, at showroom confirm

Showroom confirm means the sale is complete and the goods have been handed over. Confirm runs one transaction with
three balanced entries, each produced by the module that owns it:

| # | Entry | Owner |
|---|---|---|
| CS-P1 | Dr `cogs` / Cr `stock_valuation`, at the branch location | inventory `StockService.issue` |
| CS-P2 | Dr branch tender / Cr `customer_deposit`, one entry per verified payment line, linked to the CSS | invoicing `CustomerDepositService` |
| CS-P3 | Dr `unbilled_receivable` (N+T) / Cr revenue (N), Cr output VAT (T) | invoicing `BranchSaleRecognitionService` |

### 3. HO invoice is a reclassification only

Generating the invoice posts:

- **CS-P4**: Dr AR / Cr `unbilled_receivable`. The invoice carries `revenueAccruedBy` and `stockIssuedBy`, so it posts
  no revenue, VAT, stock or cash.
- **CS-P5**: applies the linked deposit to the invoice in the same transaction.

The invoice total must equal the accrued N+T, in both transaction and functional currency. Otherwise the request
fails with 400.

### 4. Lifecycle rules

- Submit, review, return, approve and reject post nothing and move no stock.
- Financial fields freeze at confirm.
- During `returned_for_correction`, only non-financial fields can change. Each change is recorded as an amendment.
- **Void** is the only reversal before invoicing:
  - It is authorised and limited to goods physically back on the shelf.
  - It reverses P3, returns stock at the original cost, and refunds the deposit or keeps it as a documented advance.
- A rejected sale stays posted. It goes to an owned exception queue and is resolved by `reopen` or, later, an RMA.
- Invoice cancel unapplies the deposit, reverses P4 and sets the CSS back to `approved`, all in one transaction.
  - Cancelled invoices stay linked to the CSS.
  - A replacement invoice reuses the original accrual.

### 5. Only verified money posts

Confirm requires every payment line to be `verified`:

- **Cash:** the confirmer verifies it, or a separate verifier when `requireSegregation` is on.
- **MoMo:** a gateway success or a verifier.
- **Bank:** a verifier.

A reference typed by the salesperson is never treated as verification. First-release methods are cash, mobile_money
and bank; card is rejected. The server derives tender accounts from `BranchSalesProfile` and ignores any account the
client sends.

### 6. Deposit isolation

A deposit with `source = cash_sale` can only settle its own CSS invoice. This is enforced in every application path
(`CustomerDepositService.apply`, `PaymentService` allocations, sales auto-apply). It cannot be refunded or voided
through `/customer-deposits`; only CSS void can do that.

### 7. Configuration validated up front

`BranchSalesProfile` holds one row per branch. It stores the location, tender accounts, walk-in partner, number
prefix, segregation flag and fiscal trigger.

- Only `invoicingMode = head_office` is accepted for now.
- On save, and again at confirm, every account and mapping must be active, postable, in the same organization and of
  the expected report section.

### 8. Fiscal integration is a separate, idempotent lifecycle constraint

The `FiscalDocumentPort` SPI ships with a `none` adapter.

- Calls go through the transactional outbox, using a stable `requestRef` with one live operation per CSS.
- A timeout leaves the document `unknown`, and recovery queries the provider before it re-sends.
- An issued fiscal document blocks void, invoice cancel and identity amendments until the fiscal cancel or correction
  succeeds.
- The issue trigger (`at_confirm` | `at_invoice`) stays unset until the accountant decides.

### 9. FX freeze

The currency and rate are snapshot at confirm. P2 to P5 and the invoice all use that rate, and nothing is re-rated
at the invoice date.

## Consequences

- Revenue, VAT, COGS, stock and cash are posted exactly once per sale. AR, deposit and unbilled all clear to zero on
  a fully paid invoice.
- Existing routes and documents keep their behaviour. The changes to `postInvoice`, `CustomerDepositService` and
  `InvoiceService.create` take effect only when the new flags are present.
- New mapping key `unbilled_receivable` (asset, created on first use).
- Not in the first release: branch cash book, remittance and MoMo settlement (CS-5), reports (CS-6),
  deliver-later, RMA after invoice, branch self-invoicing, partial or credit sales, and a real EFRIS adapter.
- Risk: Uganda may require an EFRIS e-receipt at the time of sale. The `at_confirm` fiscal trigger covers that case
  without any redesign.
