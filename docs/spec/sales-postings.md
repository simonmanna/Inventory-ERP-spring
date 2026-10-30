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
| `inventory_quarantine` | asset | **new** | returned stock under inspection (or location-based valuation) |
| `stock_writeoff` | expense | **new** | scrapped returns |

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

Guards:
- P4 / P5 run once per load line (fleet already enforces "stock issue once" with a row lock).
- P8 never issues stock when the SO was fulfilled through fleet / collect (`stockIssuedBy=delivery`); the invoicing
  auto-issue path is used only for direct invoices and must take the line's source location (fixes "first active
  warehouse").
- P7 and P8 are mutually exclusive per billable unit; P8a clears P7.
- Freight income (P8) and freight expense (P14) never net in the same account.

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
