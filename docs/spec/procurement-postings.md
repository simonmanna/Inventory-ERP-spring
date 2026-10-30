# Procurement postings specification

- Status: **proposed** (ADR-PROCUREMENT-001). The current behaviour in each section is as built, with references to
  the [audit](../audit/procurement-current-state-audit.md#4-traced-transaction-path-po--gl).
- Every posting goes through `accounting/application/PostingService.post`, which handles period checks, idempotency
  by `postingKey`, balance validation and rate conversion. No procurement code writes `JournalEntry` rows directly.
- Account names are **mapping keys** from `backend/src/main/resources/contracts/account-mappings.json`. Keys marked
  *(new)* must be added to that registry and to `coa-template.json`.
- Tax treatment is **configurable** through `core` `Tax` / `TaxType` and fiscal positions, never hard-coded. Every
  Uganda-specific default is marked **[accountant to confirm]**.

## 0. Invariants (checked by reconciliation ITs and the nightly report)

| ID | Invariant |
|---|---|
| I-1 | GRNI balance per org = Σ(posted receipt value at actual cost) − Σ(value cleared by vouchers or bills), by PO line. GRNI for a fully vouchered and fully billed line is 0. |
| I-2 | AP control balance = Σ open vendor-bill residuals + Σ open receipt-voucher residuals − unapplied advances (AP ageing = supplier ledger = GL). |
| I-3 | Stock valuation GL = Σ `StockItem` value (`quantity × runningAverageCost`, or Σ layer value). |
| I-4 | Each source document has at most one posted entry per `postingKey`. Reversal keys end with `:rev:<n>`. |
| I-5 | Σ landed-cost allocations = Σ capitalisable charges (to the cent; the rounding remainder goes to the largest line). |
| I-6 | Commitments: open PO value = Σ commitment − Σ relieved, per budget line. |

## 1. Recognition model

AP is recognised at the **receipt voucher** (Nest parity). The bill posts only what the receipts did not voucher, plus
variances. This stays the default. A policy `ap.recognition = receipt | bill` **[OD-5]** allows switching new orders to
bill-based recognition: receipt Dr stock / Cr GRNI, bill Dr GRNI / Cr AP (the classic SAP GR/IR model). Both models
must satisfy I-1 and I-2.

## 2. Goods receipt (stock lines)

| Case | Debit | Credit | Key |
|---|---|---|---|
| AVCO / FIFO / SPECIFIC | `stock_valuation` (product stock account) = qty × actual unit cost (in functional currency) | `grni_accrued` same | GL key from `StockService.receiveCore` source |
| STANDARD | `stock_valuation` = qty × standard; `purchase_price_variance` (5905) Dr if actual > standard, Cr if lower | `grni_accrued` = qty × **actual** | same |
| Receipt voucher (when `ap.recognition=receipt`) | `grni_accrued` = Σ line value **at actual**; `tax_receivable` = recoverable input tax | `accounts_payable` (partner) | `receipt_voucher:goods_receipt:{grnId}` |

**Fix P0-1.** The voucher must use the *actual* receipt value: PO price converted to functional currency (or the
request override) × qty. It must not use `Received.totalValue`, which is at standard for STANDARD products.
Acceptance: STANDARD product, standard 100, PO 110, qty 10 → stock 1,000, PPV Dr 100, GRNI Cr 1,100 / Dr 1,100
(net 0), AP Cr 1,100.

**Foreign currency (fix P0-5).** A receipt converts at the rate of the receipt date (`accounting` rate table; a PO-fixed
rate is used only if `po.rateFixed=true`). The posting carries `currencyId`, `exchangeRate` and the transaction amounts,
so the AP line keeps its currency amount for later realized-FX calculation.

**Rejected quantity.** A rejected quantity is not received. Only `acceptedQuantity` is posted. Rejected goods awaiting
return are kept off-book, with a record on `SupplierClaim`.

## 3. Service entry, expense and asset lines

| Line type | Debit | Credit |
|---|---|---|
| Service (accepted value) | expense / project WIP account per line (`AccountDeterminationService.expenseAccount`) | `grni_accrued` (services use GR/IR too) |
| Expense (non-stock goods) | expense account | `grni_accrued` |
| Asset | `fixed_asset_clearing` *(new)* | `grni_accrued` |
| Direct-to-site, expensed by policy | project WIP / site expense | `grni_accrued` |

The voucher or bill then clears GRNI in the same way as for stock lines.

## 4. Vendor bill

| Case | Debit | Credit | Notes |
|---|---|---|---|
| Line matched to a **vouchered** receipt, same price | — (nothing; AP already raised) | — | today's behaviour |
| Line matched to a vouchered receipt, bill price ≠ receipt price **(fix P1-7)** | `invoice_price_variance` *(new; falls back to `purchase_price_variance`)* if higher; stock instead, if `ppv.capitalise=true` and stock is still on hand | `accounts_payable` (difference) | Opposite signs when lower |
| Line matched to an **accrued** receipt (`ap.recognition=bill`) | `grni_accrued` = receipt value; variance as above | `accounts_payable` | |
| No receipt, stockable (invoice before receipt) | `goods_in_transit` *(new; default `stock_in_transit`)* | `accounts_payable` | A later GRN debits stock and credits `goods_in_transit` instead of GRNI. Replaces today's auto-receive (`receiveFromBill`), behind policy `bill.withoutReceipt = autoReceive | inTransit | block`. |
| Service or expense with no entry | expense | `accounts_payable` | Allowed only under 2-way match policy |
| Input VAT, recoverable | `tax_receivable` | — | **[accountant to confirm]** recoverability per tax code |
| Input VAT, non-recoverable | capitalised into the line cost | — | |
| WHT on bill (if `wht.timing=bill`) | — | `withholding_payable`; AP reduced by the same | default stays `payment` |

Key: `vendor_bill:{id}`. Cancelling a bill posts a mirror entry (`PostingService.reverse`) and releases its matches
(`BillReceiptMatcherService.releaseForBill`).

## 5. Supplier payment (single engine: invoicing `PaymentService`)

| Case | Debit | Credit |
|---|---|---|
| Pay bill(s) | `accounts_payable` (allocated residual) | bank / cash; `withholding_payable` (WHT) |
| Realized FX **(fix P0-5)** | `fx_loss` or `accounts_payable` | `accounts_payable` or `fx_gain` |
| PO `pay` (parity route, adapter) | as above, allocated to the PO's posted bills, oldest first; otherwise an advance (§6) | |

Realized FX = (payment rate − bill rate) × allocated foreign amount, posted in the same entry. Key:
`payment:{paymentId}`.

## 6. Advances / prepayments

| Event | Debit | Credit |
|---|---|---|
| Advance against PO | `supplier_advances` *(new, asset)* | bank / cash |
| Apply to bill | `accounts_payable` | `supplier_advances` |
| Refund | bank | `supplier_advances` |

## 7. Returns, debit notes, credit notes, claims

| Event | Debit | Credit | Notes |
|---|---|---|---|
| Return to vendor (stock) | `accounts_payable` (or `grni_accrued` if not yet vouchered) | stock at **current cost** (AVCO) or the layer cost (FIFO); the difference against the purchase value goes to `purchase_price_variance` | Exists (`StockPostingService.postReturnToSupplier`). Add the input-tax reversal: Cr `tax_receivable`. |
| Inbound debit note, no goods **(fix B1)** | `accounts_payable` | expense or the original line account; `tax_receivable` (tax part) | today it is unbalanced |
| Supplier credit note (from supplier) | `accounts_payable` | as above; reduces the bill residual through allocation | new; invoicing owns it (`documentType='vendor_credit_note'`) |
| Claim settled by credit note | as supplier credit note | | `SupplierClaim.status=settled` |

Debit and credit notes must allocate against the bill residual. Today they never do.

## 8. Landed cost

See [`procurement-landed-cost.md`](procurement-landed-cost.md) §4. In summary: Dr stock (on-hand share) / Dr COGS
(consumed share) / Dr expense (non-capitalisable) / Dr `tax_receivable` / Cr `landed_cost_clearing` (2155) or the
supplier's AP. Each charge's own supplier bill clears 2155.

## 9. Commitments (budget control, no GL)

Commitments are memo records, not journals. A PO approved commits `lineValue`. A GRN or service entry relieves
`acceptedValue`. A short-close or cancel relieves the remainder. A PR can **pre-commit** when `budget.precommit=true`.
They are reported in budget versus actual versus committed (`BudgetService.vsActual` is extended).

## 10. Reversals and corrections

- Never edit or delete a posted entry. Use `PostingService.reverse(entryId, date, desc)` (mirror entry, original
  marked REVERSED).
- The reversal date is the original date if that period is open; otherwise it is the first open date (policy
  `reversal.dateMode`).
- GRN reversal is refused when it is matched to a bill, has posted landed cost, or has payments (as today). The check
  and the claim run in **one transaction with the GRN row locked** (fix P1-6).

## 11. Required ITs

1. Receipt before invoice, at equal price and at a different price.
2. Invoice before receipt (in transit).
3. Partial receipts × partial bills (3 × 2).
4. A STANDARD product through a PO (P0-1).
5. A USD PO: receipt at rate r1, bill at r2, payment at r3.
6. Return to vendor after the bill.
7. Inbound debit note (B1).
8. Supplier credit note allocation.
9. Advance applied to a bill.
10. Landed cost with four suppliers, 40 % consumed before the late charge.
11. Reversal of each document type.

After every scenario, assert invariants I-1 to I-6.
