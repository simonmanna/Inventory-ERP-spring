# ADR-S081 — FX gain/loss correctness fixes (FX-1)

- Status: built 2026-11-20 on branch `phase/fx-gainloss-fx1`; **the five refusals were owner-approved 2026-11-20**. The owner approved the FX
  gain/loss audit and its plan (FX-1 to FX-5); FX-1 needs no schema change and no new policy decision.
- Audit: owner question "how should the ERP handle SSP–USD exchange gain and loss", external review asking for a code
  audit before any coding. The audit classified each capability as implemented and tested, partial, designed, built but
  unverified, or missing, with file references. FX-1 fixes the defects it confirmed that need no policy decision.
- Builds on: [ADR-S054](ADR-S054-shared-fx-resolution-realised-fx.md), [ADR-S076](ADR-S076-foreign-currency-treasury.md),
  [ADR-S080](ADR-S080-foreign-currency-deposits.md).
- Extension: no NestJS counterpart. No routes, request fields or migrations change. New refusals are listed under
  Deviations in `docs/spec/accounting.md`.

## Decisions

| # | Defect found | Fix |
|---|---|---|
| 1 | The Currency page sent no `asOf`, so every rate save failed with 400. Its list sent no pair, so it was always empty. | The page sends an effective date (default today), defaults the pair to USD → functional currency, and lists the selected pair. |
| 2 | A rate of 0 was accepted, and `findRate` returned a direct 0. | `POST /currencies/rates` refuses with "rate must be greater than zero". A stored 0 is ignored by the lookup. |
| 3 | A foreign expense paid from a functional bank tagged the bank line with the expense currency. Revaluation would then revalue a functional account. | Expense payments post in base. The expense or payable lines keep the expense currency and foreign amount (`Line.inCurrency`), and the cash line stays untagged. |
| 4 | An advance offset of a foreign accrued expense (`settleAgainst`) posted base-only, leaving a foreign balance on the payable. | Same treatment as 3: the payable is relieved in the expense currency. |
| 5 | The realised-FX line of a credit note was untagged. The receivable's currency bucket missed it, so revaluation counted it again. | The receivable line carries the document currency with a zero foreign amount, which is the pattern of `remeasureVoucheredPayable`. |
| 6 | Refunding an open foreign credit (credit note refund, sales return with refund) paid its face value in the functional currency. | Refused: "Credit note … is in USD; a foreign-currency credit cannot be refunded here yet. Allocate it to a USD invoice instead". A currency-aware refund is FX-2. |
| 7 | Bills created through the API store rate 1. `remeasureVoucheredPayable` returned early, so AP raised at the PO rate never moved to the bill rate. Receipt-without-PO variance also used rate 1. | `DocumentFxRates.effectiveRate` resolves the bill's posting rate (stated, else table, else refused), like `PostingService`. Variance and remeasurement use it. |
| 8 | A foreign landed-cost charge without a stated rate was capitalised at 1, while its generated bill posted at the table rate. | The charge rate resolves from the rate table on the invoice date (or today), and is refused when none exists. |
| 9 | A supplier payment from a foreign account had no funds check. | `ForeignCash.assertFunds`, as treasury and deposit refunds do. |
| 10 | Two concurrent revaluation runs could both post. | `pg_advisory_xact_lock` per organisation. |
| 11 | The automatic reversal of a revaluation could be reversed with `POST /journal-entries/{id}/reverse`, making the unrealised result permanent. | Refused: "This entry is the automatic reversal of an FX revaluation and cannot be reversed by hand; run the FX revaluation again for the date instead". |
| 12 | Prepaid expenses are reconcilable but non-monetary (IAS 21.16), yet they were revalued. | Category `prepaid_expense` is excluded from revaluation. |
| 13 | The web `formatCurrency` printed "UGX" with no decimals for every organisation. | It prints the organisation currency with that currency's ISO minor digits. |

## Not in FX-1 (waiting on owner decisions; see the plan)

- Realised FX when an accrued foreign expense is paid later at a new rate. The pay route has no rate today (decision 5,
  expenses from USD cash).
- Deposit classification (advance vs refundable), a per-account monetary flag (loans in, prepaid by flag),
  partner-level AR/AP revaluation lines, advance allocation, credit-note allocation FX, foreign refunds, foreign loans,
  foreign opening balances, rate types (FX-2).
- PO receipts valued at the receipt-date rate instead of the PO rate (FX-3, decision 2).
- FX report and close checklist (FX-4); automatic rate fetch (FX-5).
- Final-settlement rounding: relief is rounded to 6 dp per payment, so any residue is at most 0.000001 per payment. It
  is not material and not addressed.

## Tests

- `FxGainLossFx1IT`:
  - zero rate refused;
  - foreign expense paid from a functional bank;
  - credit-note FX bucket;
  - foreign credit refund refused;
  - foreign payment funds check;
  - the reviewer sequence on fresh books: revalue $1,000 AP → automatic reversal → pay $400 at a new rate (realised
    60,000, no double count) → revalue the $600 → reversal; manual reversal of the automatic reversal refused;
  - prepaid not revalued.
- `ProcurementIntegrityIT.poReceiptThenForeignBillAtAnotherRate`: PO at 3,700, bill at the table 3,800 → FX loss 2,000,
  AP at 76,000, stock unchanged.
- `LandedCostAuditIT.foreignChargeWithoutRateUsesTable`: capitalised at 3,650, clearing nets to zero.
