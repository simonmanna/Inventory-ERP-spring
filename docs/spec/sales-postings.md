# Sales / O2C — accounting and stock postings specification

Design: [ADR-S011](../adr/ADR-S011-sales-o2c.md). Module spec: [`sales.md`](sales.md).

Status: **draft — requires accountant sign-off before S5 (fulfilment postings) and S6 (billing / deposits) are built.**
Tax treatment (VAT tax point on advances, deliveries) must be confirmed for Uganda and any other target country.

## 1. Principles

1. Sales never posts. Every entry is produced by the owning module's posting path: inventory `StockPostingService`,
   invoicing `InvoicingWorkflowsInitializer.postInvoice` / `PaymentService` / `CreditNoteService`, accounting
   `PostingService`.
2. Accounts come from `AccountMappingService` keys (effective-dated), never hardcoded numbers.
3. Every event below has: source document · trigger · posting key(s) · reversal path · reconciliation test.
4. One stock issue per physical unit, one revenue recognition per billable unit, one COGS per issued unit.
5. Every posting is idempotent on its source document (existing `PostingService` source keys).
6. Reversals create new entries; posted entries are never edited.

## 2. Account mapping keys

| Key | Type | Exists | Used for |
|---|---|---|---|
| `accounts_receivable` | asset (control) | yes | AR (partner override `receivableAccountId`) |
| `sales_revenue` | income | yes | revenue (category override via `incomeAccount`) |
| `cogs` | expense | yes | cost of goods sold |
| `stock_valuation` | asset | yes | inventory |
| `tax_payable` / output VAT | liability | yes (tax engine) | output VAT |
| `default_bank`, `default_cash`, `card_clearing` | asset | yes | receipts |
| `bad_debt` | expense | yes | write-off (S8) |
| `customer_deposit` | liability | **new** | advances received |
| `goods_in_transit_customer` | asset | **new** | seller-owned stock between gate and ownership transfer |
| `unbilled_receivable` | asset | **new** | revenue recognised before invoice |
| `freight_income` | income | **new** (replaces fleet account 4250) | delivery charges billed |
| `freight_expense` | expense (cost of sales) | **new** | own-fleet / transporter delivery cost (fleet 1.9) |
| `sales_returns` | contra-income | **new** | optional: credit notes for returns |
| `sales_rebates` | contra-income | **new** | rebate accrual (S9) |
| `rebate_accrual` | liability | **new** | rebates owed (S9) |
| `sales_commission_expense` | expense | **new** | commissions queued into payroll (S9; code 6150) |
| `inventory_quarantine` | asset | **new** | returned stock under inspection (or location-based valuation) |
| `stock_writeoff` | expense | **new** | scrapped returns |

## Sales Accounting Go-Live Sign-off

Reviewer condition B2 (2026-11-24): developers do not decide accounting policy. For each question the accountant
records the decision; the code ships the **current behaviour** below until a decision says otherwise. Every policy that can be switched without new accountant-approved postings is a setting (Sales settings,
"Accounting policies") whose default is the current behaviour, so nothing changes until the accountant chooses. Any decision that differs from
the current behaviour is its own PR, linked in "Implementation ref", with its test in "Test evidence".

| # | Question | Current behaviour (shipped default) | Configurable today | Decision | Approved treatment | Approver | Date | Implementation ref | Test evidence |
|---|---|---|---|---|---|---|---|---|---|
| 1 | VAT on advances (tax point) | `delivery`: plain deposits carry no VAT; VAT arises on the final invoice | Yes: `depositTaxPoint`. `receipt` = advances on taxed orders must be a down-payment invoice (which charges the VAT); plain deposits on taxed orders are refused at all three entry points (V683 / R6) | pending | | | | feat/sales-accounting-policy-settings | `SalesAccountingPolicyIT.vatOnAdvances`, `SalesBillingIT.downPaymentInvoice` |
| 2 | Deposit application across invoices | Manual by default; `oldest_first` applies open deposits when an order invoice posts. No pro-rata | Yes: `depositApplication` (`manual` / `oldest_first`) | pending | | | | | `SalesBillingIT`, `SalesLifecycleIntegrityIT.fullLifecycle` |
| 3 | Returns: contra-income or reduce revenue | `salesReturnsAccountId` set → returns post there; blank → they reduce the line's revenue account | Yes: `salesReturnsAccountId` | pending | | | | | `SalesReturnFlowIT` |
| 4 | Contractor revenue recognition | Revenue at invoice posting. `revenueRecognition = at_ownership_transfer` is refused by the settings service until the transit postings (§3 rows C–E) are approved | Locked to `at_invoice`; the alternative needs accountant-approved postings first (not built) | pending | | | | — | — |
| 5 | Weighbridge excess over ordered | `bill`: a load above the ordered quantity (within the line tolerance) is billed as accepted. Excess the customer reports above what was dispatched is never billed | Yes: `overOrderExcess` (`bill` / `absorb` = billable capped at ordered − cancelled) (V683 / R6) | pending | | | | feat/sales-accounting-policy-settings | `SalesAccountingPolicyIT.overOrderExcess` |
| 6 | Rebate VAT | No VAT on rebate settlement credit notes; accruals gross | Yes: `rebateTaxId`. Set = the credit note is VAT-inclusive (customer credit unchanged), output VAT reduced, the VAT share cleared from the accrual against rebate cost (V683 / R6) | pending | | | | feat/sales-accounting-policy-settings | `SalesAccountingPolicyIT.rebateVat` |
| 7 | Showroom VAT tax point | `at_confirm`: output VAT credited when the cash sale is confirmed (accrual P3); the invoice reclassifies only | Yes: `showroomTaxPoint` (`at_invoice` = VAT held on 2216 "Output VAT not yet invoiced" at confirm, moved to the tax account by the invoice; a void reverses it) (V683 / R6) | pending | | | | feat/sales-accounting-policy-settings | `CashSaleIT.showroomVatAtInvoice`, T1/T4, T5 |
| 8 | EFRIS fiscal trigger | Unset: no fiscal document is requested | Yes, per branch profile: `fiscalTrigger` (`at_confirm` / `at_invoice` / unset) | pending | | | | | `CashSaleIT` T24–T26 |
| 9 | Walk-in customer policy | Sales without a partner post to the profile's `walkInPartnerId`; a TIN is required above `requireTaxNumberAbove` (0 = never) | Yes: branch profile `walkInPartnerId`, `SalesSettings.requireTaxNumberAbove` | pending | | | | | `CashSaleIT` T11/T12 |

Go-live needs every row with a decision, an approver and a date. `docs/spec/cash-sales.md` (production gate) points
here for rows 7–9.

### ADR-S094 decisions (owner, 2026-11-28)

[ADR-S094](../adr/ADR-S094-centralized-sales.md) §14 lists these. They are recorded here in the same way: decision,
approved treatment, approver, date, implementation ref, test evidence. "Overlaps" points at the go-live row above that
the same answer settles.

| # | Question | Overlaps | Decision | Approved treatment | Approver | Date | Implementation ref | Test evidence |
|---|---|---|---|---|---|---|---|---|
| AD-1 | Cash sale paid now, delivered later: interim order + full advance / contract liability / bill-and-hold | — | interim order + full advance | Order-type Sale with a full advance (J1 / J1-D); no cash-delivered-later flow | owner (recommended option) | 2026-11-28 | ADR-S094 C3a (V735): a cash sale is confirmed only with a full handover ("A cash sale hands every line over in full at the counter") | `SalesCashSaleIT` handover |
| AD-2 | Invoice before ownership transfer (F4): contract liability released at dispatch / period-end deferral journal / materiality threshold | row 4 | contract liability released at dispatch | ADR-S094 §3.6 Option A, account mapping `unearned_revenue` | owner (recommended option) | 2026-11-28 | ADR-S094 C2b (V734): `SalesRevenueDeferralService`, sources `sales_revenue_deferral` / `sales_revenue_release`. It applies whatever `revenueRecognition` says: an invoice of goods already dispatched still recognizes revenue at the invoice. | `SalesRevenueDeferralIT` (9) |
| AD-3 | Delivered-not-invoiced at period end: period-end accrual / per-delivery P7 | — | period-end accrual | Period-end accrual of delivered-not-invoiced, reversed next period; P7 stays where the policy uses it | owner (recommended option) | 2026-11-28 | ADR-S094 | |
| AD-4 | Tax point per scenario (pickup; advances in Mode R vs Mode D; invoice before delivery) under local VAT law | rows 1, 7 | as designed | Pickup: tax at the cash invoice; advances per `depositTaxPoint`; invoice before delivery: tax at the invoice | owner (recommended option) | 2026-11-28 | ADR-S094 | |
| AD-5 | EFRIS timing (cash confirm; all sale invoices; credit notes on reversal) and the zero-total invoice | row 8 | fiscalise every sale invoice | Through the fiscal outbox, including the zero-total final invoice and reversal credit notes | owner (recommended option) | 2026-11-28 | ADR-S094 C3a (V735): the cash confirm queues `FiscalDocument` `so:<sale>:issue:<n>` in its transaction (`CashSaleFiscalService.requestForSale`). Order-type invoices from C4. C3b (V736): a cash sale's reversal first cancels its fiscal document through the outbox (phase 1, `gateSaleReversal`); the reversal credit note is not fiscalised on its own; unknown outcomes are looked up, resent under the same reference, or reviewed by hand | `SalesCashSaleIT` F1, FI; `SalesCashReversalIT` FR-5; `SalesFiscalRecoveryIT` |
| AD-6 | Cash postings P4 + P8 + P11 with no unbilled-receivable (1215), deposit or 2216 entries | row 7 | approved | P4 + P8 + P11, no 1215 / deposit / 2216 entries | owner (recommended option) | 2026-11-28 | ADR-S094 C3a (V735): `SalesCashSaleService.confirm`. Counter pickup (P4), invoice from the sale (P8), one receipt per verified tender (P11), in one transaction | `SalesCashSaleIT` F1 (exact journals; no lines on `unbilled_receivable` / `customer_deposit` / `output_tax_deferred`), TP-1, FI |
| AD-7 | Branch-issued invoices from the single org gap-free series | — | approved | One gap-free invoice series per organization | owner (recommended option) | 2026-11-28 | ADR-S094 C3a: the cash invoice is posted through `InvoiceService.post` (gap-free `invoice:<year>` counter) | `SalesCashSaleIT` CC-5 (contiguous numbers), FI (counter unchanged after a fault) |
| AD-8 | Walk-in invoicing (walk-in partner per branch, buyer identity, `requireTaxNumberAbove`) | row 9 | approved | Walk-in partner per branch; buyer identity per `requireTaxNumberAbove` | owner (recommended option) | 2026-11-28 | ADR-S094 C3a: the profile's walk-in customer by default; `customerName` / `customerPhone` / `customerTin` / `customerAddress` on the sale; `SalesBillingService.assertTaxRules` accepts the sale's `customerTin` | `SalesCashSaleIT` walk-in defaults |
| AD-9 | Returns accounts, restock cost, damaged / scrap accounts, refund tender rules, credit retention | row 3 | existing accounts | Restock at the original issue cost; damaged to quarantine; scrap to the scrap account; refund over the original tender; retention with permission | owner (recommended option) | 2026-11-28 | ADR-S094 | |
| AD-10 | Transport revenue account if freight moves onto the sale invoice | — | not applicable | Freight stays on the fleet invoice (OD-4) | owner (recommended option) | 2026-11-28 | ADR-S094 | |
| AD-11 | Migrated history acceptance (1215 / 2216 residuals zero after drain) | — | not applicable | No historical migration (C5 dropped) | owner (recommended option) | 2026-11-28 | ADR-S094 | |
| AD-12 | POS refunds / voids / write-offs in the revenue measures | — | as designed | Refunds and settled voids negative on the refund date; write-offs excluded (bad debt) | owner (recommended option) | 2026-11-28 | ADR-S094 | |
| AD-13 | Module sources allowed to issue customer invoices (ADR-S094 §8.1) | — | approved | ADR-S094 §8.1 module sources | owner (recommended option) | 2026-11-28 | ADR-S094 | |
| AD-14 | Deducting taxed down payments across partial invoices: pro-rata / oldest-first / manual | row 2 | pro-rata | Deduction = advance remaining × invoiced net / remaining order net, last invoice takes the rest | owner (recommended option) | 2026-11-28 | ADR-S094 C1b (V731) | `SalesDownPaymentVatIT` |
| AD-15 | A credit note on a down-payment invoice reduces the deposit; refused once consumed | — | approved | Credit note on a DP invoice reduces its deposit; refused once consumed | owner (recommended option) | 2026-11-28 | ADR-S094 C1b (V731) | `SalesDownPaymentVatIT` |
| AD-16 | Contract-liability account and release trigger for F4 (dispatch / POD) | row 4 | dispatch | Release at dispatch, per load line | owner (recommended option) | 2026-11-28 | ADR-S094 C2b: `DeliverySourcePort.deliveryChanged` from fleet dispatch, POD and load return | `SalesRevenueDeferralIT` DF-2, DF-3 |
| AD-17 | Zero-total final invoice after a 100% taxed advance, issued as a tax invoice | — | approved | Zero-total final invoice is issued as a tax invoice | owner (recommended option) | 2026-11-28 | ADR-S094 C1b (V731) | `SalesDownPaymentVatIT` |
| AD-18 | Historical D-VAT-1 corrections (credit notes), if any occurred | — | none needed | C0 assessment: no occurrences | owner (recommended option) | 2026-11-28 | ADR-S094 C0 | C0 read-only assessment |
| AD-19 | Zero-value sales (replacement, sample): tax invoice required, or delivery note only | — | delivery note only | Zero-value sales get no tax invoice | owner (recommended option) | 2026-11-28 | ADR-S094 | |
| AD-20 | Quantity credit without a return (write-off): accounts, approval level, stock treatment | — | approved | Own permission plus approval; no stock movement; posted like a return credit | owner (recommended option) | 2026-11-28 | ADR-S094 | |

## 3. Policy matrix (fulfilment and recognition)

| # | Physical movement | Ownership transfer | COGS | Revenue | Valid |
|---|---|---|---|---|---|
| A | direct | at_dispatch | at transfer | at_invoice (invoice after dispatch) | yes — **default** |
| B | direct | at_dispatch | at transfer | at_ownership_transfer (accrue unbilled) | yes |
| C | via_transit | at_delivery | at transfer | at_invoice (invoice after POD) | yes |
| D | via_transit | at_delivery | at transfer | at_ownership_transfer | yes |
| E | via_transit | at_acceptance | at transfer | at_ownership_transfer | yes |
| F | direct | at_delivery / at_acceptance | — | — | **no** (ownership after gate needs transit) |
| G | any | any | at transfer | at_invoice with invoice before transfer | **no** (revenue before transfer) — invoice allowed only as proforma / down-payment |
| H | any | any | at_revenue_recognition | at_ownership_transfer | same as "at transfer" |

## 4. Postings by event

Amounts: `C` = issue cost (AVCO/FIFO from inventory), `N` = net sales value, `T` = output VAT, `F` = freight charge.

| # | Event | Source doc | Trigger | Dr | Cr | Reversal | Reconciliation test |
|---|---|---|---|---|---|---|---|
| P1 | Order confirmed | SalesOrder | confirm | — (no GL) | — | — | exposure only |
| P2 | Deposit received (no invoice) | Payment (kind deposit, `salesOrderId`) | receipt posted | bank/cash/MoMo | `customer_deposit` | void → reverse; refund P10 | deposit subledger = `customer_deposit` balance |
| P3 | Down-payment invoice (tax point on receipt / invoice) | Document sales_invoice (kind down_payment) | invoice post | `accounts_receivable` (N+T) | `customer_deposit` (N), output VAT (T) | credit note | AR subledger = AR control; VAT report |
| P3a | Down-payment invoice settled by deposit | DepositApplication | apply | `customer_deposit` | `accounts_receivable` | unapply | deposit + AR subledgers |
| P4 | Gate-out, policy direct | FleetLoad / collect ticket | stock issue | `cogs` (C) | `stock_valuation` (C) | trip return → P13 | stock ledger value = `stock_valuation` |
| P5 | Gate-out, policy via_transit | FleetLoad | transfer to customer-transit location | `goods_in_transit_customer` (C) | `stock_valuation` (C) | return before transfer → reverse P5 | transit location value = `goods_in_transit_customer` |
| P6 | Ownership transfer (delivery / acceptance), via_transit | POD / discrepancy decision | issue from transit | `cogs` (C × accepted) | `goods_in_transit_customer` (C × accepted) | post-transfer return → P13 | as P5 |
| P6a | Rejected quantity at acceptance | discrepancy decision | transfer transit → yard / quarantine | `stock_valuation` or `inventory_quarantine` | `goods_in_transit_customer` | — | as P5 |
| P7 | Revenue at ownership transfer, not yet invoiced (policies B/D/E) | SalesFulfilmentDecision | billable decided | `unbilled_receivable` (N) | `sales_revenue` (N) | reversed by P8a | unbilled subledger = `unbilled_receivable` |
| P8 | Invoice posted (regular) | Document sales_invoice from SO | invoice post (`stockIssuedBy=delivery` → no stock issue) | `accounts_receivable` (N+T+F) | `sales_revenue` (N), output VAT (T), `freight_income` (F) | credit note P12 | AR subledger; `gross invoiced − credited ≤ billable` |
| P8a | Invoice posted where P7 accrued | same | invoice post | `accounts_receivable` (N+T) | `unbilled_receivable` (N), output VAT (T) | credit note | unbilled cleared per SO line |
| P9 | Deposit applied to regular invoice | DepositApplication | apply | `customer_deposit` | `accounts_receivable` | unapply (reverse entry) | deposit + AR subledgers |
| P10 | Deposit refunded | Payment (refund) | refund posted | `customer_deposit` | bank | void | deposit subledger |
| P11 | Customer payment | Payment + PaymentAllocation | receipt | bank/cash/MoMo | `accounts_receivable` | void | AR subledger |
| P12 | Credit note (price / quantity / return) | Document credit_note linked to invoice + SO line | post | `sales_revenue` or `sales_returns` (N), output VAT (T) | `accounts_receivable` (N+T) | — (new invoice if wrong) | credited qty ≤ gross invoiced |
| P13 | Return received — usable | RMA → return receipt | inspection: restock | `stock_valuation` (original issue cost) | `cogs` | — | stock ledger |
| P13a | Return received — quarantine | RMA | receipt to quarantine | `inventory_quarantine` | `cogs` | later restock / scrap | quarantine location value |
| P13b | Return scrapped | RMA | disposition scrap | `stock_writeoff` | `inventory_quarantine` / `cogs` | — | stock ledger |
| P14 | Freight cost (own fleet / transporter) | fleet 1.9 cost / vendor bill | bill / accrual | `freight_expense` | AP / fleet cost accrual | bill reversal | freight income vs expense per trip report |
| P15 | Rebate accrual (S9) | RebateAccrual | period job | `sales_rebates` | `rebate_accrual` | reverse accrual | accrual subledger |
| P15a | Rebate settled by credit note (S9) | credit note (kind rebate) | post | `rebate_accrual` | `accounts_receivable` | — | accrual cleared, never re-expensed |
| P16 | Bad-debt write-off (S8) | write-off doc | post | `bad_debt` | `accounts_receivable` | recovery receipt | AR subledger |
| P17 | Cash sale reversed (ADR-S094 C3b) | SalesReturn + credit note + refund payments, one transaction | `POST /sales/orders/:id/reverse` | P13 (`stock_valuation` at the issue cost), P12 (`sales_returns` N, output VAT T), refund per tender: `accounts_receivable` | `cogs`, `accounts_receivable` (N+T), refund per tender: its tender account | — (the sale stays cancelled) | `SalesCashReversalIT` (credit-note journal, AR zero and tied out, VAT zero, stock and value back) |

Guards:
- P4 / P5 run once per load line (fleet already enforces "stock issue once" with a row lock).
- P8 never issues stock when the SO was fulfilled through fleet / collect (`stockIssuedBy=delivery`); the invoicing
  auto-issue path is used only for direct invoices and must take the line's source location (fixes "first active
  warehouse").
- P7 and P8 are mutually exclusive per billable unit; P8a clears P7.
- Freight income (P8) and freight expense (P14) never net in the same account.
- Customer weight evidence (Phase A2) posts **nothing**: it never moves stock, never posts to the GL and never changes
  the quarry ticket or a document. A confirmed weight claim lowers the load line's billable quantity through the
  existing discrepancy approval (no journal). Invoiceability by bucket and any financial effect are A3 / A4, which
  need a separate financial review first.

## 5. Deposit and down-payment scenarios (each = one integration test)

| # | Scenario | Expected entries | Expected balances after |
|---|---|---|---|
| D1 | Deposit 30M without invoice | P2 30M | `customer_deposit` −30M (credit), AR 0 |
| D2 | Down-payment invoice 30M (VAT 18 % on advance, tax point = receipt), then receipt | P3 (AR 30M, deposit 25.42M, VAT 4.58M); P11 30M | deposit −25.42M, AR 0, VAT −4.58M |
| D3 | Receipt first (P2 30M), then down-payment invoice for the tax point | P2 30M; P3 then P3a | deposit balance = net of VAT; AR 0 |
| D4 | Full application to final invoice 100M (VAT incl.) | P8 100M; P9 30M; P11 70M | deposit 0, AR 0 |
| D5 | Partial application across 3 invoices (40M / 40M / 20M) | P9 12M / 12M / 6M (rule: pro-rata or oldest-first — **owner/accountant to choose**) | deposit 0 at last invoice |
| D6 | Deposit refund 10M | P10 10M | deposit −20M |
| D7 | SO cancelled with unapplied deposit 30M → refund | P10 30M | deposit 0 |
| D8 | SO cancelled with unapplied deposit → keep on account | reclass `customer_deposit` → on-account credit (Dr `customer_deposit` / Cr `accounts_receivable` unallocated) | deposit 0, AR credit −30M |
| D9 | Close short with residual deposit 5M | as D7 or D8 per instruction | — |
| D10 | VAT on advance not triggered (tax point = delivery) | P2 only; VAT arises on P8 | VAT only on final invoice |

Open questions for the accountant (block S6):
1. Uganda VAT tax point for advances (receipt vs invoice vs delivery) and required document.
2. Deposit application rule across multiple invoices (pro-rata vs oldest first vs manual).
3. Whether returns use `sales_returns` contra-income or reduce `sales_revenue`.
4. Revenue recognition policy for credit contractors (at delivery vs at invoice).
5. Treatment of weighbridge excess over ordered quantity (bill or absorb).
6. (S9, Q-S9-1) Rebate settlement credit notes are issued without VAT; confirm whether a volume rebate must reduce
   output VAT (tax credit note) and whether accruals should be net of VAT.

## 5a. Taxed advances and the final invoice — ADR-S094 (built in C1b, V731)

**Defect D-VAT-1, fixed in C1b** ([ADR-S094](../adr/ADR-S094-centralized-sales.md) §3.5). Before C1b, with
`depositTaxPoint = receipt` (Mode R):
- a down-payment invoice (D2) records the deposit at its **net** amount;
- the final invoice (D4) then charges full VAT on all goods and has no deduction line;
- applying the net deposit (P9) therefore leaves AR open by the advance's VAT, and output VAT is counted twice.

D2 followed by D4 in the table above assumes an untaxed deposit, so it only holds in Mode D (`delivery`, the default),
which is not affected.

**Rule** (C1b; AD-14, AD-15 and AD-17 approved 2026-11-28):
- In Mode R the final invoice deducts the taxed down payment with a negative line on the deposit account, carrying the
  same tax (Odoo's method).
- The deduction consumes the deposit. P9 is used only for untaxed deposits (Mode D).
- A credit note on a down-payment invoice reduces its deposit.
- Zero-total final invoices are allowed.

**As built** (`SalesBillingService.deductions`, `CustomerDepositService.consume`, V731):

| Topic | Behaviour |
|---|---|
| Which down payments | Posted (or paid) down-payment invoices of the order whose deposit-account line carries a tax. Untaxed down payments (Mode D) are applied with P9 as before. |
| Deduction line | One per down payment, on the deposit account, with the down payment's tax, negative quantity, no product. Deducting the whole down payment at once gives the down payment's own line negated (same quantity, unit price and tax-inclusive flag), so its net and VAT come back exactly. A part is a tax-exclusive line, quantity −1, unit price = the net share; the tax engine computes its VAT and rounds it to the currency's decimals like any other line. |
| Share (AD-14 pro-rata) | what is left of the down payment × net of this invoice's goods ÷ net of the order not yet invoiced before it (live invoices, drafts included). The invoice that leaves nothing more to invoice (every line invoiced up to ordered − cancelled, close-short included) deducts everything left. *Left* = the down payment's net − its deductions on live invoices − posted credit notes on its invoice. |
| Refused at create | "A down-payment invoice of sales order X is still a draft; post or cancel it before invoicing the order" (a draft taxed down payment could otherwise be deducted later or never); "The down payments to deduct exceed what sales order X invoices now" (negative total). |
| Recheck at post (sales post guard) | Each deduction line still matches its link (deposit account, the down payment's tax, negative quantity, amount ±0.01); the down-payment invoice is still posted; the down payment is not deducted or credited beyond its net. |
| Consumption | In the posting transaction (`InvoicePostGuard.afterPost`, not the asynchronous `invoice.posted` event): one `DepositApplication` of kind `deduction` per deducted down payment, without a payment. The deposit closes when nothing is left. A posted down-payment invoice registers its deposit in the same hook (the event still does it too, idempotently). |
| Release | A discarded draft never consumed anything. Cancelling a posted final invoice stamps its deductions `reversedAt` and re-opens the deposit; the next regular invoice deducts again. |
| P9 / P10 | Refused for a taxed down-payment deposit: "Deposit X is a down payment invoiced with VAT; it is deducted on the final invoice of its sales order, not applied" and "Deposit X is a down payment invoiced with VAT; refund it with a credit note on its down-payment invoice Y". Automatic deposit application on invoice post skips them. |
| Credit note on a down-payment invoice (AD-15) | Lines on the deposit account only ("A credit note on a down-payment invoice credits the customer deposit account only"), with the down payment's tax ("A credit note on a down-payment invoice takes the tax of the down payment"); in both modes, so the deposit record always follows the deposit account. Posting consumes the deposit (`DepositApplication` kind `credit_note`) and is refused when less is left: "Down-payment invoice X has only Y of its deposit left; the rest was already deducted, credited or applied". The existing cap (credit notes ≤ invoice total) applies first. Reversing the credit note releases it. |
| Zero and negative lines (AD-17) | A zero-total invoice posts without an AR line and is `paid` at once. A negative revenue line posts as a debit ("Deduction"), a negative tax line as a debit to the tax account ("Output tax deducted"); zero lines are skipped. A negative invoice total is refused: "An invoice total cannot be negative". |

**Rounding example** (UGX, no decimals; ADR-S094 AD-14): 50% advance 590 (500 + VAT 90), reduced by a credit note of
100 + 18 to 400 left. Invoice 1 (4 of 10): deduction 400 × 400 ÷ 1,000 = 160, VAT 28.80 → **29**; total 472 − 189 =
283. Invoice 2 (the rest): 240, VAT 43; total 708 − 283 = 425. Output VAT 90 − 18 + 72 − 29 + 108 − 43 = 180; owed
590 − 118 + 283 + 425 = 1,180.

**Common data**: 10 units × 100.00 = net 1,000.00; VAT 18% = 180.00; gross 1,180.00; unit cost 60.00 (COGS 600.00).

**Deposit account**: the `customer_deposit` account mapping. It is 2175 in both live organizations; code 2350 is
created only when no mapping exists (`CustomerDepositService.depositAccount`). It is written "deposit" below.

| Required scenario | Where |
|---|---|
| 100% down payment with a tax invoice | J1 (Mode R); J1-D (Mode D) |
| Partial advance, then final delivery | J2 (both modes) |
| Deposit taxed at receipt | Mode R: J1, J2-R, J3, J4-A, J4-D |
| Deposit taxed at the final invoice | Mode D: J1-D, J2-D, J3 Mode D variant, J4-C |
| Partial delivery and partial invoicing | J3 (pro-rata, oldest-first and Mode D variants) |
| Credit note after an advance invoice | J4-A (cancel), J4-B (return), J4-D (advance reduced) |

**J1 — 100% advance with a tax invoice (Mode R)**

| Step | Entry | Dr | Cr |
|---|---|---|---|
| 1 Down-payment invoice DP-1 (P3) | AR | 1,180.00 | |
| | Customer deposit | | 1,000.00 |
| | Output VAT | | 180.00 |
| 2 Receipt (P11) | Bank / AR | 1,180.00 | 1,180.00 |
| 3 Dispatch 10 units (P4) | COGS / Inventory | 600.00 | 600.00 |
| 4 Final invoice INV-1: goods 1,000.00 + VAT 180.00; deduction −1,000.00 (deposit) VAT −180.00 → **total 0.00** | Customer deposit | 1,000.00 | |
| | Output VAT (deduction) | 180.00 | |
| | Revenue | | 1,000.00 |
| | Output VAT (goods) | | 180.00 |

Balances: AR 0 · deposit 0 · **output VAT 180.00 (once)** · revenue 1,000.00 · bank 1,180.00 · COGS 600.00.

*Before C1b (D-VAT-1)*: step 4 posted Dr AR 1,180.00 / Cr revenue 1,000.00 / Cr VAT 180.00. The 1,000.00 deposit is
then applied, which left AR open at **180.00** and output VAT at **360.00**.

**J1-D — 100% advance, Mode D**: P2 Dr Bank / Cr deposit 1,180.00 → P4 → P8 Dr AR 1,180.00 / Cr revenue 1,000.00,
Cr VAT 180.00 → P9 Dr deposit / Cr AR 1,180.00. AR 0, deposit 0, VAT 180.00.

**J2 — 30% advance, then full delivery**

| Mode R (deposit taxed at receipt) | Dr | Cr |
|---|---|---|
| DP invoice: AR / deposit 300.00, VAT 54.00 | 354.00 | 354.00 |
| Receipt: Bank / AR | 354.00 | 354.00 |
| Dispatch: COGS / Inventory | 600.00 | 600.00 |
| Final invoice: AR 826.00, deposit 300.00, VAT 54.00 / revenue 1,000.00, VAT 180.00 | 1,180.00 | 1,180.00 |
| Receipt: Bank / AR | 826.00 | 826.00 |

Balances: AR 0, deposit 0, **VAT 54 + 180 − 54 = 180**, revenue 1,000, bank 1,180.

| Mode D (deposit taxed at the final invoice) | Dr | Cr |
|---|---|---|
| Deposit (P2): Bank / deposit | 354.00 | 354.00 |
| Dispatch: COGS / Inventory | 600.00 | 600.00 |
| Final invoice (P8): AR / revenue 1,000.00, VAT 180.00 | 1,180.00 | 1,180.00 |
| Apply deposit (P9): deposit / AR | 354.00 | 354.00 |
| Receipt (P11): Bank / AR | 826.00 | 826.00 |

Balances: AR 0, deposit 0, VAT 180, revenue 1,000, bank 1,180.

**J3 — Partial delivery and partial invoicing, 50% advance, Mode R, pro-rata deduction (AD-14)**

| Step | Entry |
|---|---|
| DP invoice 590.00 | Dr AR 590 / Cr deposit 500, Cr VAT 90; receipt 590 |
| Dispatch 4 units | Dr COGS 240 / Cr Inventory 240 |
| Invoice 1 (4 units): goods 400 + VAT 72; deduction 400/1,000 × 500 = 200 + VAT 36 → total **236.00** | Dr AR 236, Dr deposit 200, Dr VAT 36 / Cr revenue 400, Cr VAT 72; receipt 236 |
| Dispatch 6 units | Dr COGS 360 / Cr Inventory 360 |
| Invoice 2 (6 units): goods 600 + VAT 108; deduction rest 300 + VAT 54 → total **354.00** | Dr AR 354, Dr deposit 300, Dr VAT 54 / Cr revenue 600, Cr VAT 108; receipt 354 |

Balances: AR 0 · deposit 0 · **VAT 90 + 36 + 54 = 180** · revenue 1,000 · COGS 600 · bank 1,180.
- *Oldest-first variant* (not built; AD-14 chose pro-rata): invoice 1 would deduct 400 + 72 (total 0); invoice 2
  100 + 18 (total 590). VAT 90 + 0 + 90 = 180.
- *Mode D variant*: deposit 590 untaxed; P9 applies 472 to invoice 1 and 118 to invoice 2; receipt 590. VAT 72 + 108 =
  180.

**J4 — Credit note after an advance invoice**

| Case | Entries | Balances after |
|---|---|---|
| A. Order cancelled before delivery; Mode R advance 354 (300 + 54) paid | Credit note on DP-1: Dr deposit 300, Dr VAT 54 / Cr AR 354. The linked deposit record is reduced by 300; refused if already consumed (AD-15). Refund: Dr AR 354 / Cr Bank 354, **or** keep as customer credit. | deposit 0, VAT 0, AR 0 (or a 354 customer credit), bank 0 |
| B. Return of 2 units after the J2-R final invoice | Credit note on INV-1, goods only: Dr sales returns 200, Dr VAT 36 / Cr AR 236. Stock return (P13): Dr Inventory 120 / Cr COGS 120. Refund 236 or keep as credit. The deduction is not reversed. | revenue net 800, VAT 144, COGS 480, deposit 0, customer net paid 944 |
| C. Order cancelled; Mode D deposit 354 | Refund (P10): Dr deposit 354 / Cr Bank 354; no VAT effect | deposit 0, VAT 0 |
| D. Advance reduced 354 → 236 before delivery (Mode R) | Credit note on DP-1: Dr deposit 100, Dr VAT 18 / Cr AR 118; refund 118. The final invoice deducts 200 + 36 → total 944. | VAT 54 − 18 + 180 − 36 = 180; deposit 0; cash 354 − 118 + 944 = 1,180 |

**Reconciliation summary** (closing balances; cash = net received after refunds):

| Example | AR open | Deposit | Output VAT | Revenue (net) | COGS | Cash | Check |
|---|---|---|---|---|---|---|---|
| J1-R, J1-D, J2-R, J2-D, J3 (all variants), J4-D | 0 | 0 | 180 | 1,000 | 600 | 1,180 | VAT = 18% × revenue; cash = revenue + VAT |
| J4-A, J4-C (cancelled, refunded) | 0 | 0 | 0 | 0 | 0 | 0 | nothing left open |
| J4-B (2 units returned, refunded) | 0 | 0 | 144 | 800 | 480 | 944 | VAT = 18% × 800 |
| **Before C1b, J1-R (D-VAT-1)** | **180** | 0 | **360** | 1,000 | 600 | 1,180 | ✗ VAT counted twice; customer shown as owing 180 |

Invariants V1–V5 (ADR-S094 §3.4) are asserted in each example's test. V1: Σ output VAT per sale = VAT on net delivered
and invoiced goods.

**Tests** (`SalesDownPaymentVatIT`, one customer and one VAT account per test; output VAT, customer deposits, AR, the AR
tie-out and the trial balance asserted): J1, J1-D, J2-R, J3, J4-A (with the P9 / P10 refusals and the wrong-account
credit note), J4-D (with the refusal of a credit note once the deposit is consumed), the rounding example above, draft
discard and posted-invoice cancel releasing the deduction, and the draft down-payment guard. J4-B and J4-C use
existing flows (credit note on the final invoice; P10 refund) and keep their existing tests.

## 6. Worked example — contractor, 100 t aggregate, policy A (default)

Price 50,000 / t excl. VAT 18 %; issue cost 30,000 / t; deposit 1,770,000 (30 t incl. VAT, no down-payment invoice).

| Step | Event | Entries |
|---|---|---|
| 1 | Confirm SO 100 t | none |
| 2 | Deposit 1,770,000 | Dr bank 1,770,000 / Cr customer_deposit 1,770,000 |
| 3 | Trip 1 gate-out 30 t weighed | Dr cogs 900,000 / Cr stock_valuation 900,000 |
| 4 | POD: accepted 29.5 t (discrepancy approved, 0.5 t loss absorbed) | billable decision 29.5 t; loss per fleet discrepancy rules |
| 5 | Invoice 29.5 t | Dr AR 1,740,500 / Cr revenue 1,475,000, Cr VAT 265,500 |
| 6 | Apply deposit | Dr customer_deposit 1,740,500 / Cr AR 1,740,500 |
| 7 | Balances | customer_deposit −29,500; AR 0; quantities: ordered 100, weighed 30, accepted 29.5, billable 29.5, gross invoiced 29.5, outstanding billable 0, undelivered 70 |

## 7. Reconciliation tests (harness `SalesLedgerAssertions`, built in S0)
- Trial balance sums to zero.
- AR subledger (open residuals by partner) = `accounts_receivable` control.
- Deposit subledger (unapplied deposits) = `customer_deposit` balance.
- Unbilled subledger = `unbilled_receivable`.
- Stock ledger value per location type = `stock_valuation` / `goods_in_transit_customer` / `inventory_quarantine`.
- Per SO line: `gross invoiced − credited ≤ billable ≤ max(weighed, accepted, ordered per basis)`.
- Credit exposure total = AR + unbilled-delivered + undelivered − deposits (see `credit.md`).
