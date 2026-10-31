# Credit management (`com.inventoryerp.credit`)

Design: [ADR-S011](../adr/ADR-S011-sales-o2c.md) §3. Postings: [`sales-postings.md`](sales-postings.md).

Status: **design (Step 0)**. Built in phase S3; this transition model and its tests come first.

## 1. Ownership and dependencies

- `credit` owns: `CreditLimit`, `CreditPolicy`, `CreditDecision` (immutable), holds, exposure snapshots.
- Contributors implement `CreditExposureContributor` in their own module (credit depends on none of them):

  | Contributor | Module | Buckets |
  |---|---|---|
  | `SalesCreditContributor` | sales | undelivered, delivered-uninvoiced |
  | `InvoicingCreditContributor` | invoicing | AR open, deposits unapplied, unapplied credits, draft non-SO invoices |
  | `PosCreditContributor` | pos | POS customer tabs / open credit sales |
  | `FleetCreditContributor` | fleet | manual / invoice-sourced DOs not linked to a sales order (delivered-uninvoiced) |

- Consumers: sales (quote submit, SO confirm, amendment), fleet (DO release, trip dispatch), POS (credit sale,
  replaces `CreditStatus` logic), collections (S8).

## 2. Exposure buckets

All values in the limit currency at the organization rate; gross (incl. tax).

| Bucket | Definition | Sign |
|---|---|---|
| U — undelivered | Σ confirmed SO lines (ordered − cancelled − billable) × unit gross price; held orders count (still commitments); cancelled / done excluded | + |
| B — delivered, uninvoiced | Σ (billable − gross invoiced + credited-for-rebill) × unit gross price | + |
| A — AR open | Σ residual of posted invoices (incl. down-payment invoices) | + |
| D — unapplied deposits | Σ `customer_deposit` liability per partner not yet applied | − |
| C — unapplied credits | Σ unallocated payments / posted credit notes not applied | − |
| X — other | draft invoices not from an SO, POS tabs, non-SO fleet DOs delivered | + |

`Exposure = U + B + A + X − D − C`; `Headroom = Limit − Exposure`.

## 3. Exposure policy (configurable only within these options; audited, effective-dated)

| # | Rule | Default | Options |
|---|---|---|---|
| 1 | Deposits reduce exposure on receipt | yes | yes |
| 2 | Undelivered valued at | gross incl. tax at SO price | gross / net |
| 3 | Posted down-payment invoices | in A like any receivable; settled by deposit → both drop | (fixed) |
| 4 | Credit notes | reduce A when applied; unapplied → C | (fixed) |
| 5 | Overdue > N days | approval-based hold | approval hold / hard hold |
| 6 | `Partner.creditHold` | hard block on confirm / dispatch | (fixed) |
| 7 | Group limit | parent partner's limit shared by child partners within the organization | on / off |
| 8 | Check points | quote submit, SO confirm, amendment increasing value, trip dispatch | per check point on / off; SO confirm cannot be turned off |

## 4. Transition model

Each event moves value between buckets; the sum `U + B + A − D` changes only when value enters (confirm, deposit,
non-SO invoice) or leaves (payment, cancel, close short, refund, credit) the customer relationship.

| Event | U | B | A | D | Notes |
|---|---|---|---|---|---|
| E1 order confirmed (V) | +V | | | | |
| E2 amendment +ΔV / −ΔV | ±ΔV | | | | never below billable |
| E3 deposit received (P) | | | | +P | exposure −P |
| E4 partial delivery, billable (q × p) | −qp | +qp | | | |
| E5 regular invoice posted (I) | | −I | +I | | from B |
| E6 down-payment invoice posted (G) | | | +G | see note | settled at once by E7 against the deposit |
| E7 deposit applied to invoice (Y) | | | −Y | −Y | both shrink, exposure unchanged |
| E8 payment received (R) | | | −R | | |
| E9 credit note posted (K) | | | −K (or C +K if unapplied) | | |
| E10 order cancelled (remaining W) | −W | | | | deposit stays in D until refund / reclass |
| E11 close short (W) | −W | | | | |
| E12 return processed (no credit yet) | | | | | physical only; exposure changes at E9 |
| E13 deposit refunded (Z) | | | | −Z | exposure +Z |
| E14 ordered-basis invoice before delivery (I) | −I | | +I | | billing policy `ordered` |

Note E6: with tax point on receipt, the down-payment invoice converts part of the deposit into a tax-invoiced
receivable settled by E7 (P3 + P3a in postings): A +G then −G; D ends at the net-of-VAT amount. Net effect on
exposure = + VAT portion (that part of the cash now belongs to the tax authority, not the customer). Tests must
prove A and D both match their subledgers after E6 + E7, for receipt-first (D3) and invoice-first (D2) orders.

## 5. Worked example (owner's case)

Limit 100M. SO 100M gross. Deposit 30M. Deliver and accept 40M. Invoice 40M. Apply deposit. Pay remainder of invoice.

| Step | Event | U | B | A | D | Exposure | Headroom |
|---|---|---|---|---|---|---|---|
| 0 | start | 0 | 0 | 0 | 0 | 0 | 100M |
| 1 | E1 confirm 100M | 100 | 0 | 0 | 0 | 100 | 0 |
| 2 | E3 deposit 30M | 100 | 0 | 0 | 30 | 70 | 30 |
| 3 | E4 deliver 40M | 60 | 40 | 0 | 30 | 70 | 30 |
| 4 | E5 invoice 40M | 60 | 0 | 40 | 30 | 70 | 30 |
| 5 | E7 apply deposit 30M | 60 | 0 | 10 | 0 | 70 | 30 |
| 6 | E8 pay 10M | 60 | 0 | 0 | 0 | 60 | 40 |
| 7 | E10 cancel remaining 60M | 0 | 0 | 0 | 0 | 0 | 100 |

Each row is an assertion in `CreditExposureTransitionTest`; A and D are also checked against the AR and deposit
subledgers through `SalesLedgerAssertions`.

## 6. Conservation property test

Random sequences of E1–E14 (valid by state machine) on random orders: after every step
1. every bucket ≥ 0 (C may be ≥ 0 as negative exposure);
2. `U + B + A − D − C` equals an independent recomputation from source documents (orders, decisions, invoices,
   allocations, deposits);
3. a unit of commitment is in exactly one of U, B, A at any time (tracked per SO line quantity);
4. A = AR subledger residuals; D = deposit subledger.

## 7. Limits and decisions

- `CreditLimit(partnerId, branchId?, currency, amount, validFrom, validTo, riskCategory, reviewDate, periodDays)`;
  org-level limit when `branchId` is null; most specific wins; group (parent) limit checked in addition.
- `CreditDecision`: check point, document, exposure snapshot (all buckets), limit, result (`pass`, `hold`,
  `blocked`), approval request id, decidedBy. Immutable.
- Breach → document `on_hold` + approval `sales_credit_override` (permission `credit:override`); hard hold → blocked.
- Auto-hold job (daily, ShedLock): overdue > N days or dunning level ≥ N → hold per policy 5; audit + notify.

## 8. API (planned, S3)
`/credit/partners/{id}/exposure`, `/credit/limits`, `/credit/decisions`, `/credit/holds`, `/credit/review-queue`.
Permissions: `credit:read`, `credit:manage`, `credit:override`.

## 9. Central customer credit management (ADR-S022)

The credit module is the only credit authority for every channel.

- **Profile**: `Partner.creditLimit`, `creditHold`, `creditRiskCategory`. Branch / currency `CreditLimit` rows are
  sub-limits that only lower it; the org-wide row mirrors the profile (V211).
- **Exposure contributors**: sales (undelivered, delivered-not-invoiced), orders (unbilled credit `Order`s), fleet
  (manual delivery orders not invoiced), invoicing (receivables, drafts, credits, deposits), pos (credit invoices). The
  document being checked is left out (`CreditExposureContributor.contribute(partnerId, excludeDocumentId)`).
- **Check points**: `quote_submit`, `order_confirm`, `amendment`, `trip_dispatch`, `pos_credit_sale`,
  `delivery_order`, `invoice_post` (`CreditPolicy.checkpoints`); overdue rule on `overdueCheckpoints`. An invoice that
  bills a sales order is not re-checked at posting.
- **Reservation**: `check` locks the customer row until the caller's transaction commits its document.
- **Overrides**: per document (`credit:override`) and per customer grant (`PartnerCreditOverride`, SoD, audited).
- **Routes added**: `GET /credit/reconciliation` (`credit:read`).
- **Tests**: `CentralCreditIT`, `CreditIT`, `CreditControlIT`.
