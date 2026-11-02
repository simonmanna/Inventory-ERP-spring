# ADR-S054 — Shared currency resolution and realised FX (PC-0)

- Status: accepted (owner decisions D-PC1, D-PC2, 2026-11-03); built in PC-0.
- Roadmap: [`costing-production-ready-plan.md`](../roadmap/costing-production-ready-plan.md)
- Findings: LC-0 G1, G2, G3 ([GL trace](../audits/landed-cost-gl-trace.md))
- Migration: `V291__pc0_fx_rate_snapshot_realised_fx.sql`
- Extension: no NestJS counterpart. New request fields are additive; no response field is renamed or removed.

## Context

LC-0 surfaced three shared FX findings:
- **G2:** `PostingService.rate` caught the `ApiException` of a `@Transactional` `CurrencyService.getRate`. The
  surrounding transaction was already rollback-only, so a foreign-currency voucher with a generated bill failed with a
  500 and a silent rollback.
- **G3:** when a table rate existed, it silently overrode the document's own rate.
- **G1:** payments posted no realised FX, and allocations subtracted base-currency amounts from foreign-currency
  residuals.

## Decision

### Rate resolution (D-PC1)

`CurrencyService.resolve(from, to, explicitRate, date)` is used by `PostingService` for every posting. It returns the
rate, its source and the date.

1. **Explicit document rate:** any positive stated rate other than exactly 1.
2. *(Authorised workflow rate: no workflow produces one today; the slot is reserved.)*
3. **Rate table:** `findRate`, the latest rate at or before the date, direct or the reciprocal of the inverse pair.
4. Otherwise **refused before anything is written**:
   `No exchange rate for <FROM>→<TO> on <date>: state the document's rate or add one to the rate table`.

Rules:
- A rate of exactly 1 on a foreign currency is the legacy "not stated" default that documents store. It is treated as
  absent. A genuine 1:1 rate is configured in the table.
- A table rate never overrides a stated document rate.
- Base-currency postings keep the caller's rate, or 1, as before.
- `findRate` never throws and opens no transaction of its own, so a missing rate cannot poison a caller's
  transaction. `getRate` keeps its contract (404) for the currency API.
- **Snapshot:** each foreign-currency entry writes one `JournalEntryFxRate` row (pair, rate, rate date, source), in the
  same transaction. Base entries write none.

### Realised FX (D-PC2)

**Payments (`PaymentService`):**
- They accept `currencyCode` and `exchangeRate`, both optional; absent means base currency, i.e. the old contract.
- The payment rate is resolved with the same precedence.
- Each allocated document is relieved at its **carrying rate** (`DocumentFxRates.documentRate`):
  1. Its journal's snapshot.
  2. Else Σ base ÷ Σ transaction amount of its entry's lines (history).
  3. Else its stated rate.
- An unallocated remainder (advance) is recorded at the payment rate.
- Cash moves at the payment rate. The difference posts to `fx_gain` (credit) or `fx_loss` (debit) **only**.
- `Payment` stores its currency and rate in the existing `currencyId` / `exchangeRate` columns, plus the rate source and the FX amount. Each `PaymentAllocation` stores carrying rate,
  settlement rate and FX.
- A payment settles only documents in its own currency:
  `Document <n> is in <CUR>; settle it with a <CUR> payment`. This also closes the old base-amount-against-foreign-
  residual defect.
- A foreign-currency payment settles documents only. Refused with: `A foreign-currency payment settles documents only:
  no withholding, store credit, deposit, cash session, refund or POS invoice`.
- The supplier overpayment check compares against outstanding bills of the same currency.

**Voids and reversals:** a void reverses the payment's journal, FX lines included, and restores the residuals.

**Credit notes (`CreditNoteService`):** a foreign-currency credit note that reduces an invoice of the same currency at
another rate realises the difference on the receivable as a separate entry (source `credit_note_fx`):
- Over-credited: Dr AR / Cr FX gain.
- Under-credited: Dr FX loss / Cr AR.

FX never reaches inventory, landed cost, production cost or COGS (asserted in `SharedFxIT` and `LandedCostAuditIT`).

## Not in scope (stated, not silently skipped)

- **Procurement debit notes** do not settle bill residuals today, so there is nothing to realise.
- **POS invoices** stay base-currency.
- **Unrealised FX revaluation** (`RevaluationService`) is unchanged. A period-end revaluation followed by settlement
  must be reversed at the start of the next period to avoid double counting; this is checked in PC-3 reconciliation.
- **VAT return conversion** of foreign documents still uses `Document.exchangeRate` (finding H4 in the D-PC3 impact
  analysis; owner review).

## Tests

- `SharedFxIT` covers:
  - Explicit rate beats table.
  - Table rate used when none is stated.
  - Refusal with nothing written.
  - Supplier partial (loss) and full (gain) settlement, then a void reversing the gain.
  - Customer collection gain.
  - Credit-note FX.
  - Currency mismatch refused.
  - FX never moving stock or COGS.
- `LandedCostAuditIT` cases 6 (USD voucher plus generated bill) and 6b (realised FX on settling it) are enabled.
