# ADR-S053 — Landed cost to ERP grade (LC-0 … LC-7)

- Status: accepted in principle; built phase by phase. **LC-0 in progress.**
- Plan: [`docs/roadmap/landed-cost-plan.md`](../roadmap/landed-cost-plan.md) (rev 3.1)
- Audit: [`landed-cost-implementation-map.md`](../audits/landed-cost-implementation-map.md),
  [`landed-cost-gl-trace.md`](../audits/landed-cost-gl-trace.md)
- Extension: no NestJS counterpart. The legacy `/procurement/landed-costs` routes keep their Nest contract.

## Context

Three review rounds asked for proof that landed cost is right before any new feature is added:
- **Valuation:** every amount follows the goods.
- **Accounting:** correct debits, credits and classification.
- **Auditability:** an amount can be traced from the invoice to the stock, WIP, FG or COGS it affected.

LC-0 to LC-3 are acceptance gates.

## Decisions

### Owner decisions (2026-11-02)

- **D1:** keep a physical `InventoryTransferLineage` table, filled by the transfer posting and backfilled exactly from
  `ledgerCode` pairs (LC-1).
- **D2:** the default return policy is `expense` (LC-1).
- **D3:** tax recoverability, done in LC-0, below.
- **D4:** rounding residual. `AvcoLineageCalculator` gives it to on-hand, or to the largest consumption when nothing is
  on hand. Kept as is and documented; Σ shares = charge holds exactly.
- **D5:** an unreviewed tax blocks new postings, but never the reversal of a posted voucher.
- **Approval:** strict draft → submitted → approved, with `costing.landedCost.allowDirectApproval` to relax it (LC-2).
- **Reapproval tolerance:** OR rule, percentage or amount, per disposition target (LC-2).

### LC-0 — tax recoverability (V290)

- `LandedCostCharge.taxRecoverable`:
  - `true`: recoverable input tax, Dr `tax_receivable`.
  - `false`: the tax is part of the charge, capitalised or expensed with its base.
  - `NULL`: unreviewed.
- The default is resolved in this order:
  1. The charge's `taxId` (`Tax.recoverability` `full` | `none`).
  2. The cost component's `taxId`.
  3. The org setting `costing.landedCost.taxRecoverableDefault` (`unset` | `recoverable` | `non_recoverable`, default
     `unset`).
  - When nothing resolves, the charge must state it, or creation is refused. Nothing is silently assumed recoverable.
- A stated value that contradicts the derived one needs `landed_cost:override_tax` and is audited
  (`landed_cost_tax_override`).
- Migration of existing data:
  - Charges without tax → `true` (irrelevant).
  - Charges with tax → `NULL` (unreviewed). They are refused at post with `Charge <label>: tax recoverability is
    unreviewed`, but can still be reversed (D5).
  - Historic postings are not changed. They were recognised as input tax.
- A `recoverable_tax` charge can't carry tax of its own: `Charge <label>: a recoverable tax charge cannot carry tax of
  its own`.
- Voucher totals:
  - `totalAmount` = Σ base + non-recoverable tax.
  - `taxAmount` = Σ recoverable tax.
- Input tax is debited once per charge.

### LC-0 — period lock policy

A voucher dated inside the books-lock period is refused when it posts, and nothing moves. A late charge for a closed
period is entered with a date in the open period. Vouchers are never back-dated.

## Findings

| # | Finding | Decision |
|---|---|---|
| G1 | `PaymentService` posted no realised FX for any foreign-currency document | Fixed in PC-0 (ADR-S054); `realisedFxOnSettlement` enabled |
| G2 | Foreign-currency posting with no configured rate rolls back the whole voucher (`CurrencyService.getRate` throws inside the transaction); G3: a configured table rate overrides the document's own rate | Fixed in PC-0 (ADR-S054, owner decision D-PC1); case 6 enabled |
| G4 | Reversal guard missed fully paid bills (status no longer `posted`) | **Fixed in LC-0**: any non-cancelled bill with `amountPaid > 0` blocks the reversal |
| F2, F5–F7 | AVCO / lot landed cost ignores lineage (transfers, WIP, returns, closed orders) | LC-1 |

## Messages (verbatim, LC-0)

- `Charge <label>: a recoverable tax charge cannot carry tax of its own`
- `Charge <label>: state whether its tax is recoverable (no tax or default in Costing & Valuation settings says so)`
- `Charge <label>: overriding the tax recoverability needs landed_cost:override_tax`
- `Charge <label>: tax recoverability is unreviewed`
- `Tax not found`

## API (LC-0, additive)

- `POST/PUT /procurement/landed-cost-vouchers` charge lines accept `taxId` and `taxRecoverable`. Responses include
  both.
- New permission `landed_cost:override_tax`, granted to Administrator.
- New setting `costing.landedCost.taxRecoverableDefault`.

## Tests

`LandedCostAuditIT` (LC-0) checks after every step:
- Entries balance.
- The GL tie-out holds.
- The 1396 movement equals this class's open-adjunct movement.
- Per-account classification.

Scenarios:
- Reversals before, after a partial and after a full sale (AVCO, lot, STANDARD).
- Paid-bill refusal.
- Accrual below and above the actual.
- USD charge.
- Concurrent posts.
- Lock period.
- Concurrent duplicate invoice.
- Layered vouchers.
- Tenancy.
- Four VAT cases plus the rejections.
- Tax-default override.
- The unreviewed guard.

`@Disabled` until their phase:
- Production WIP (LC-1).
- Transfer (LC-1).

## Deviations from the plan

Fixtures are own warehouses, suppliers and products inside the demo organisation, with every assertion a delta or
scoped to them. A whole new organisation per IT would need the full seed of accounts and mappings. No seed or demo
balance can make a scenario pass.
