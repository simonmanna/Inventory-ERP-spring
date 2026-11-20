# FX gain & loss — code + accounting audit and implementation plan (2026-11-20)

## Context
Owner asked how the ERP should handle SSP⇄USD exchange gain/loss across sales, expenses, purchases and COGS. An
external review of my first answer asked for a **fresh code audit before any coding**, classifying each capability
instead of trusting ADR status. This file is that audit (read-only; nothing changed) plus a proposed sequence.
Evidence: three code sweeps plus my own spot-checks; items marked ✔ were re-read by me in the code.

Status levels: **L1** implemented + tested · **L2** partial · **L3** designed, not built · **L4** built, correctness
unverified (no test) · **L5** missing.

## A. Capability status

| Capability | Level | Evidence / note |
|---|---|---|
| Rate resolution: stated → table → refuse; rate snapshot per entry | L1 | `CurrencyService.java:117-145`, `PostingService.java:714`; `CurrencyServiceResolveTest`, `SharedFxIT`. Gaps: rate 0 accepted (`CurrencyController.java:44`); reversals/drafts write no snapshot |
| Per-org rate table | L1 backend / **broken UI** | V320 + RLS; `Acc0FxIT.ratesAreTenantScoped`. ✔ `currency.tsx:32` never sends `asOf` → every save 400; list always empty |
| Rate types (official/bank/market) | L5 | only free-text `source` |
| Automatic daily rate fetch | L5 | no scheduler, no client |
| USD sales invoice → receipt realised FX | L1 | `PaymentService.fxSettlement:610-684`; `SharedFxIT`, `FxPaymentsIT` |
| Partial / multiple payments on one invoice | L1 / L4 | tested with integer rates only; **no final-settlement true-up** → base residue on AR/AP with real rates |
| One payment, several invoices at different rates | L4 | code supports it; untested |
| Unallocated advance applied later (customer or supplier) | L5 | no allocate route; advance sits on AR/AP forever and is revalued |
| USD bill → supplier payment realised FX | L1 | `SharedFxIT`, `LandedCostAuditIT:289` |
| Supplier payment from USD bank (cash-side FX) | L1, **defect** | relief at carrying value works; ✔ no funds check (`PaymentService.java:436-442`) |
| Foreign cash carrying value / relieve / transfers | L1 | weighted Σbase/Σforeign excluding revaluations (`ForeignCash.java:104-126`), `TreasuryService:115-196`; `FxTreasuryIT`, `ForeignCashTest`. Meets reviewer issue 3 |
| PO receipt valuation | L2 (policy) | stock and AP valued at the **PO creation rate**, not receipt date (`PurchaseOrdersService.java:226-235,841`) |
| PO receipt → bill at different rate | L3, **defect** | ✔ `remeasureVoucheredPayable` exits early because bills store rate 1 (`InvoicingWorkflowsInitializer.java:401,614,722`) → AP residue. Untested |
| Receipt without PO + USD bill variance | **defect** | variance computed with rate 1 (`IWI:368-381`) |
| PO pay route | L2 | uses PO rate, never realises FX |
| Landed cost FX | L1 / **defect** | FX on payment kept out of stock (tested). ✔ no rate stated ⇒ rate 1, no table fallback (`LandedCostVoucherService.java:1315`) |
| Inventory / COGS isolation from FX | L1 | `SharedFxIT:218`, `LandedCostAuditIT` |
| Foreign expense accrual in USD | L1 | `ExpensesService.inExpenseCurrency:162` + `Acc0FxIT.foreignExpense`. **Reviewer's "B14 posts base-only" is outdated** for accrual/payment |
| USD expense paid (now or later) from SSP cash | **defect** | ✔ SSP cash line gets tagged USD (`ExpensesService:1160` + `PostingService:923`, no guard `ForeignCash:207`) → revaluation will revalue an SSP account. No realised FX on later payment |
| Advance offset of USD expense (`settleAgainst`) | **defect** | ✔ base-only (`ExpensesService:1088`) → phantom USD balance on payable |
| Expense paid from USD account (reviewer issue 6) | L5 | refused by design (ADR-S076 decision 4) |
| Period-end revaluation AR/AP/cash | L2 | `RevaluationService` + routes + `period-end.tsx` preview/post; `Acc0FxIT`, `FxTreasuryIT`, `ReportingIT` |
| Automatic reversal next day, linked | L1 | `reversalOfId`, same-day re-run idempotent (`booked()` + `Acc0FxIT:73`) |
| Reval integrity | **defects** | no lock (concurrent runs double-post); auto-reversal can be reversed via `/journal-entries/{id}/reverse`; AR/AP reval lines have no partner (subledger ≠ GL); prepaid expense (non-monetary) is revalued; liabilities/loans never revalued; D+1 in closed period blocks month-end run |
| Reval → reverse → partial pay → reval → reverse | L4 | only full settlement after reval tested |
| Credit notes | L1 / **defects** | FX on posting tested. ✔ FX line untagged (`DocumentFxRates.java:89-94`) → revaluation double-counts; `allocate()` realises no FX; ✔ refund of USD credit pays SSP face value (`SalesCreditFlowService.java:134`) |
| Customer deposits — advance (IFRIC 22) | L1 | `FxDepositsIT` |
| Customer deposits — refundable/security (monetary) | L5 | no classification; mapping labelled "rental deposits"; rental deposits subledger-only, `double` math |
| Supplier advances / prepayments in USD | L2 | only via overpayment; never applied; PO pay at PO rate |
| Foreign-currency loans | L5 | cash-flow `loan_received` posts liability base-only, never revalued, residue on repayment |
| Foreign opening balances (AR/AP/bank) | L5 | `OpeningBalanceService` base-only; foreign cash only via designation |
| FX reporting | L2 | `/fx-treasury/summary` realised vs unrealised; no drill-down report |
| Month-end checklist item for revaluation | L5 | |
| Functional currency lock | L1 | `FunctionalCurrencyLock`; gap: explicit null accepted |
| Multi-currency display in web | **defect** | `web/src/lib/utils.ts:8` `formatCurrency` always prints "UGX", no decimals — wrong for SSP and USD orgs |
| IAS 29 hyperinflation restatement | L5 | out of scope unless policy requires |

## B. Policy decisions needed (owner + finance lead)
1. **Functional currency per org** (IAS 21 primary economic environment; IAS 29 assessed separately). The ERP must
   support both SSP- and USD-functional orgs; nothing is hardcoded to SSP/USD in the backend.
2. **PO receipt rate**: keep PO rate, or value stock + AP at the **receipt-date rate** (recommended: IAS 21 transaction
   date = recognition date), with bill-date differences to FX on AP, never to stock.
3. **Deposit classification**: `advance` (historical rate) vs `refundable` (monetary, revalued). Default for existing
   rows = `advance`.
4. **Rate policy**: which source is authoritative (official vs bank), and whether separate rate types are needed.
5. **Expenses from USD cash**: keep refusal, or allow with cash-side FX (reviewer issue 6).
6. **Reval scope**: per-account "monetary" flag instead of category flags (adds loans, removes prepaid).

## C. Implementation sequence (after approval; each step = own PR, ADR-S081+, migrations V584+)
- **FX-1 Correctness, no schema** — rate UI `asOf` + list query; refuse rate ≤ 0; expense payments: functional cash
  line base-only, payable relieved in USD with realised FX; `settleAgainst` in USD; credit-note FX line tagged
  (`inCurrency`); credit-note refund in note currency (or refuse); bill remeasure uses effective entry rate
  (`DocumentFxRates.entryRate`); receipt-without-PO variance uses that rate; landed-cost missing rate → table or
  refuse; funds check on USD payments; final-settlement true-up (last relief clears remaining base); revaluation
  advisory lock, refuse manual reversal of `fx_revaluation` reversals, exclude prepaid; web `formatCurrency` uses
  org currency.
- **FX-2 Schema + flows** — deposit kind (+ refundable deposits revalued); account `monetary` revaluation flag;
  partner-level AR/AP revaluation lines; advance allocation route for payments + credit-note allocation FX; foreign
  loans (tagged liability); foreign opening balances (AR/AP/bank with currency + rate); rate types.
- **FX-3 Receipt-date rate** (if decision 2 approved) — PO receipt valued at receipt-date rate; bill remeasure to FX.
- **FX-4 Reporting + close** — FX gain/loss report (realised vs unrealised, by partner/document/account, drill to
  allocation), period-close checklist item, exposure dashboard (P2).
- **FX-5 Automatic rate fetch** — scheduled (ShedLock), provider configurable, manual override, missing-rate warning.
- Reuse throughout: `CurrencyService`, `DocumentFxRates`, `ForeignCash`, `PaymentService.fxSettlement`,
  `PostingService` (sole GL writer). No second FX engine.

## D. Verification
New `FxGainLossScenariosIT` (ApiTestSupport, non-integer rates like 3,712.45), asserting balanced journals, foreign
and functional amounts, account directions, subledger = GL per partner, audit rows and idempotent replay:
1 USD invoice paid at another rate · 2 USD bill paid at another rate · 3 partial + multiple settlements with true-up ·
3b one payment, three invoices · 4 USD expense paid immediately · 5 USD expense paid later · 6 USD cash bought at two
rates then spent/transferred · 7 month-end AR/AP reval per partner · 8 bank/cash reval · 9 reviewer sequence
(Nov 30 reval $1,000 AP → Dec 1 reverse → Dec 5 pay $400 → Dec 31 reval $600 → Jan 1 reverse) · 10 refundable vs
advance deposit · 11 GRN date ≠ bill date · 12 COGS unchanged after rate moves · credit note + refund · supplier
advance applied · loan · opening balances · concurrent reval runs. Run with `fastTest --tests '*Fx*IT'` plus existing
`SharedFxIT`, `FxPaymentsIT`, `FxDepositsIT`, `FxTreasuryIT`, `Acc0FxIT`, `LandedCostAuditIT`; smoke on org DEMO.

## E. Risks
Existing live data may already contain mis-tagged expense/credit-note lines → a one-off diagnostic query + correcting
journals (owner approval) before the first revaluation; changing PO receipt rate alters future stock cost (not history);
partner-level revaluation changes report figures.
