# ADR-S076 — Foreign-currency treasury (R-8)

- Status: accepted (owner decisions 2026-11-16 on plan §10: all five recommendations); built 2026-11-16 on branch
  `phase/fx-treasury`. **R-8 stays open until the implementation, migration and regression review are independently
  verified.**
- Plan: [`docs/accounting/FX-TREASURY-PLAN.md`](../accounting/FX-TREASURY-PLAN.md) (architecture, rules, phases).
- Finding: accounting review 2026-11-15, R-8 ([review](../audits/2026-11-15-accounting-review.md)).
- Builds on: [ADR-S054](ADR-S054-shared-fx-resolution-realised-fx.md) (rate resolution, realised FX on documents),
  ADR-S070 (org-local accounting day), ADR-S073 (bank matching rules).
- Migrations: `V510__fx_treasury_foundation.sql`, `V511__fx_revaluation_per_currency.sql`.
- Extension: no NestJS counterpart. New routes and request fields are additive. Two deliberate deviations: the
  functional-currency lock and the account-currency lock (below).

## Context

Treasury posted the typed amount of a foreign-currency bank account as if it were functional currency. Payments refused
foreign accounts. Bank reconciliation compared functional ledger amounts with statement lines in the account's
currency. There was no correct way to hold, move or reconcile foreign currency.

## Decision

### Model
- **Functional currency:** `Organization.currencyCode`. It cannot change once the organisation has a posted or
  reversed entry: "The functional currency cannot change once entries are posted" (both settings routes).
- **Denomination:** `Account.currencyId` on a cash, bank or mobile-money account (null = functional). It cannot change
  once the account has postings: "The currency of account X cannot change once it has postings".
- **Lines:** every line on a designated foreign account carries the denomination, through the existing per-line
  override (`PostingRequest.Line.inCurrency`):
  - `debit`/`credit` hold the foreign amount;
  - `baseDebit`/`baseCredit` hold the functional amount;
  - `exchangeRate` is functional ÷ foreign.

  The posting engine enforces it: "Account X is held in USD; post it in USD". Only revaluations and designations may
  put base-only lines on such an account.
- **Rate snapshots:** one `JournalEntryFxRate` row per currency pair of an entry (new unique key). The source may be
  `document`, `table` or `carrying`.
- **No second engine:** everything posts through `PostingService.post`, with all its checks: periods, VAT locks,
  account rules, idempotency keys.

### Rules
- **Rate direction:** a rate row (FROM, TO, r) means 1 FROM = r TO. Resolution follows ADR-S054: a stated rate, else
  the rate table, else refused.
- **Precision:** amounts are rounded to 6 dp, `HALF_UP`. A foreign amount may not have more decimals than
  `Currency.decimalPlaces`: "USD amounts take at most 2 decimals".
- **Carrying value:** Σ base ÷ Σ foreign of the account's tagged lines. Revaluations (base-only) never enter it.
  - An outflow relieves the amount at the carrying rate.
  - An outflow that empties the balance relieves the whole remaining carrying value.
  - Any part beyond a positive balance is relieved at the spot rate.
- **Funds:** checked in the denomination: "<account> has insufficient funds (available USD 60.00)".
- **Realised FX** goes to `fx_gain` (credit) / `fx_loss` (debit), the ADR-S054 accounts:
  - withdrawal: counterpart at the spot rate − cash relieved;
  - transfer: destination value − source relieved;
  - payment: the document FX (ADR-S054) and the cash-side FX, netted into one line.
- **Unrealised FX:** the existing period-end revaluation, now also over cash-equivalent accounts, with one row per
  (period, account, currency); it reverses on the next day. Because carrying excludes revaluations, a settlement after a
  revaluation is not double counted.

### Flows
| Flow | Behaviour |
|---|---|
| Deposit / withdrawal (`/accounts/cash-flow/deposit`, `/withdraw`) | `amount` is in the account's currency. Optional `exchangeRate`. The counterpart is valued at the transaction rate. |
| Transfer (`/treasury/transfer`) | Same currency: moves at carrying value, no FX. Across currencies: `amount` is sent and `receivedAmount` (required) is received. The destination is valued at `exchangeRate` or the table rate, or exactly when it is functional. The spread is FX (owner decision 3). |
| Customer / supplier payments | A payment in currency C may use a designated bank, cash or mobile-money account held in C, outside a register shift. The cash line is tagged; outflows are relieved at carrying value. A functional payment into a foreign account is still refused ("POS tenders must use the organization currency…"), and so is a payment in a currency the account does not hold. |
| Bank matching rules (ADR-S073) | On a foreign account, the bank line is in its currency, the counter and tax lines are at the line date's rate, and outflows are relieved at carrying value with FX. |
| Bank reconciliation | A designated foreign account reconciles in its own currency: book, ledger and statement balances. Statement lines in another currency are refused on import. Responses gain `currencyCode` and `currencyState`. |
| Expenses, expense advances, petty-cash replenishment, shift banking | Refused from or to a foreign account (owner decision 4): "… cannot use account X, which is held in USD; transfer the funds to a UGX account through treasury first". |
| PO pay (`/purchase-orders/:id/pay`) | It cannot choose an account (it uses the method's mapped account), so it keeps refusing foreign accounts. Pay a foreign PO's bill through `/supplier-payments` from the foreign account (owner decision 5). |
| POS, register shifts, store credit, customer deposits | Functional only, unchanged. |

### Legacy accounts (no silent conversion)
A foreign account whose ledger holds untagged lines from before this change is **undesignated**. FX treasury, payments
and bank rules refuse it until it is designated; every other behaviour stays as it was.

`POST /fx-treasury/designations` (`account:update`, Idempotency-Key required) works as follows:
- The user states the foreign balance.
- One `fx_designation` entry moves the account's unknown-currency book value onto a line in its currency. It is
  functional-neutral.
- The account records `currencyDesignatedAt` and `currencyDesignationEntryId`, and an audit row is written.
- A balance of the opposite sign is refused, and a second designation is refused.
- Any gap to the current rate is recognised by the next revaluation.

### New routes
- `GET /fx-treasury/accounts` (`treasury:read`): each foreign account's state, foreign balance, carrying value, and the
  unknown-currency balance a designation would take over.
- `POST /fx-treasury/designations` (`account:update`): as described above.
- `GET /fx-treasury/summary?from&to` (`currency:read`): realised and unrealised FX by origin.

Additive response fields:
- `/accounts/cash-flow`: `foreignBalance` and `currencyState`.
- `/accounts/cash-flow/:id/transactions`: per-line `currencyId`, and `foreignBalance` / `carryingValue` /
  `currencyState` on the account.

## Consequences
- Historical posted amounts are untouched: no backfill and no conversion.
- Single-currency organisations post exactly as before. The engine check only fires for foreign cash accounts.
- `DocumentFxRates.entryRate` reads the snapshot of the entry's own currency pair, since an entry may now snapshot more
  than one pair.
- Statement-import currency checking applies to foreign accounts only. Functional accounts keep accepting any label,
  so existing imports don't change.
- `BankAccount.currencyId` (the Nest `/bank-accounts` CRUD record) is descriptive only. The ledger denomination is
  `Account.currencyId`.

## Tests
- `FxTreasuryIT` covers:
  - deposit / withdrawals with realised FX, the decimal and funds checks, and emptying relief;
  - same- and cross-currency transfers with two snapshots;
  - legacy designation, the engine check and both locks;
  - reconciliation in the account's currency;
  - revaluation, then settlement without double counting, and the FX summary;
  - two currencies on one account.
- `FxPaymentsIT` covers a customer receipt into a foreign bank, a supplier payment out of one, a void, and the
  currency-match refusals.
- `ForeignCashTest` covers the relief arithmetic.
