# ADR-S080 — Foreign-currency customer deposits

- Status: **built, pending owner (and external reviewer) sign-off on the accounting policy** (2026-11-19).
  The owner asked for foreign-currency deposits to be built ("go ahead with foreign currency deposits"). The
  accounting treatment below (IFRIC 22, no revaluation) is a policy choice that needs explicit approval.
- Extension: no NestJS counterpart. No new routes. `POST /customer-deposits`, `POST /sales/orders/{id}/deposits` and
  `POST /customer-deposits/{id}/refund` accept the optional fields `currencyCode` and `exchangeRate`. Deposit rows add
  `currencyCode`, `exchangeRate`, `rateSource`, `baseAmount`, `availableBase` and `currency`.
- Migration: `V583__customer_deposit_currency.sql`.
- Builds on ADR-S076 (FX treasury). That ADR's row "customer deposits: functional only" is superseded.

## Context

Before this change, customer deposits (ADR-S011 S6) could only be held in the functional currency:
- `CustomerDeposit` had no currency column.
- `PaymentService` refused a foreign-currency deposit application.

So a customer who paid an advance in USD, for a USD order, had to be booked as a functional-currency amount at a
rate someone chose by hand. That deposit then couldn't settle the USD invoice without a currency mismatch.

## Decision

1. **A deposit is held in one currency.** `amount`, `appliedAmount` and `refundedAmount` stay in that currency.
   `exchangeRate` is in functional units per foreign unit. `currencyCode` null means functional (rate 1), and every
   existing deposit is functional.
2. **Receipt.** A foreign deposit must be received into a designated bank / cash / mobile-money account held in that
   currency (ADR-S076 `TenderAccount` with a foreign currency, `ForeignCash.of`). Otherwise the receipt returns 400:
   "A USD deposit must be received into a bank, cash or mobile-money account held in USD".
   - The decimals of the amount are checked against the currency.
   - The rate is the stated `exchangeRate`, or else the rate table (`CurrencyService.resolveStated`). Its source is
     kept in `rateSource`.
   - Posting: Dr the foreign account, Cr customer deposits (with the partner). Both lines are tagged with the currency
     and the foreign amount (`Line.inCurrency`) and carry the functional value round(amount × rate, 6).
3. **No revaluation (IFRIC 22).** An advance received is a non-monetary liability. It stays at the rate of the day it
   was received. FX revaluation (`RevaluationService`) only covers cash-equivalent and reconcilable categories, so the
   customer-deposit account (current liabilities) is never revalued. No change is needed there.
4. **Application: same currency only.** A deposit settles invoices in its own currency. Anything else is refused, with
   no silent conversion: "Deposit DEP-… is in USD; it can only settle USD invoices (INV-… is in UGX)".
   - The automatic oldest-first application on invoicing skips deposits in another currency.
   - A foreign application goes through the existing foreign-payment FX settlement, with the deposit's rate as the
     payment rate. So the deposit is relieved at its own rate, the receivable at the invoice's rate, and the
     difference is realised FX (`fx_gain` / `fx_loss`).
   - This is the IFRIC 22 result: revenue was recognised at the invoice rate, and the advance has been consumed at the
     rate it was received.
   - Reversing an application reverses that payment, FX line included.
5. **Refund.** The refund leaves from an account held in the deposit's currency, with the funds checked in that
   currency.
   - The cash is relieved at the account's carrying value (ADR-S076 `ForeignCash.relieve`; a stated `exchangeRate`
     applies only to any part beyond the balance).
   - The deposit is relieved at its own rate. The last relief clears the exact remaining functional value, so no
     rounding residue is left.
   - The difference is realised FX.
6. **Down-payment invoice.** A down-payment invoice in a foreign currency registers its deposit in the invoice's
   currency, at the invoice's carrying rate (`rateSource = document`).
7. **Reporting in functional value.** Every place that adds deposits together converts them at each deposit's own
   rate, so currencies are never summed at face value:
   - credit exposure (bucket D);
   - the AR customer-advances report and the sales deposits report (each also gets a "Currency" column, e.g.
     "USD 1,000.00 @ 3700");
   - the customer statement's deposit section (the description notes the foreign amount and rate);
   - customer open balances.

   The deposit receipt PDF prints the deposit in its own currency, plus an "Exchange rate" line with the functional
   value.
8. **Unchanged:** cash-sale deposits and mobile-money deposits stay functional (`CustomerDepositService
   .receiveForCashSale`, `SalesMobileMoneyService`). Foreign-currency showroom cash sales remain a separate decision
   (`cash-sales.md` deviation 1).

## Consequences

- The subledger always ties to the ledger per partner, in both currencies: Σ open × rate (functional) and Σ open
  (foreign) for the tagged lines. `FxDepositsIT` checks this.
- Realised FX now appears when a foreign deposit is applied or refunded. That is the intended treatment. Owners who
  prefer to revalue advances (a minority policy under IFRS) would need a change to decisions 3–5.
- The web shows a currency, account and rate choice in the record-advance and refund dialogs (deposits page, customer
  page, sales order billing panel). An order in a foreign currency defaults its deposit to that currency.

## Tests

`FxDepositsIT` (all at a stated rate on a fresh test currency) covers:
- receipt at 3,600, with the ledger and the foreign amount on both lines;
- refusals: wrong account, functional deposit into the foreign account, cross-currency application;
- application of 600 to an invoice at 3,700 → FX loss 60,000;
- refund of 400 against a bank carrying 3,700 → FX loss 40,000;
- reversal of the application → FX reversed;
- subledger = ledger, statement, and receipt PDF.

Regression suites: `SalesBillingIT`, `FxPaymentsIT`, `CashSaleIT`, the credit ITs, `InvoicingIT`, `SalesOrderPolicyIT`
and `CustomerDepositReceiptPdfTest`.
