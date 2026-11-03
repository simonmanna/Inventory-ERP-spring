# ADR-S053 — Landed cost to ERP grade (LC-0 … LC-7)

- Status: accepted in principle; built phase by phase. **LC-0 and LC-1 done.**
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

### LC-1 — valuation correctness (V292)

**Rule:** a transfer is not consumption. A landed charge follows the goods to wherever they are now.

- **Transfer lineage.** `InventoryTransferLineage(sourceLedgerId → destinationLedgerId, quantity)` is written by
  `StockService.transfer` in the same transaction (every transfer path — documents, transit dispatch and receipt,
  quality, rental, branch, reversal — goes through it). History is backfilled **exactly** from the shared `ledgerCode`
  (one out and one in row, same product, variant and quantity, D1); anything else becomes an `ambiguous_pair`
  exception. Transit is just another pool: A → transit → B is two hops.
- **Engine.** `LineageFollower` distributes the charge over the receipt layer with `AvcoLineageCalculator`; each
  `transfer_out` share is split over its lineage links pro rata by quantity and distributed again from each
  `transfer_in` row (recursively). The open remainder of a partial dispatch is `in_transit`. A ledger row met twice on
  one path is a `cycle`; more than 20 hops is `depth`; no link is `unpaired_transfer`. All three park the share in
  1396 (value-only row tagged `price_difference`, open adjustment with no anchor) and raise a `LineageException`. A
  posting is never silently wrong.
- **Recording.** One `CostValueAdjustment` per pool visited, linked by `parentAdjustmentId` / `rootAdjustmentId`, with
  `sourceReceiptLedgerId`; lines per target (`on_hand`, `cogs`, `wip`, `scrap`, `transfer` → destination row,
  `negative_stock`, `return`, `in_transit`).
- **Valuation by method (vouchers only; the legacy `/procurement/landed-costs` keeps the Nest behaviour).**
  - AVCO / non-lot FIFO: each pool's on-hand share raises that pool's running average (value-only row). A share whose
    pool holds no stock any more goes to `negative_stock` (COGS), never into the average.
  - STANDARD: each pool's on-hand share is an open 1396 adjunct anchored at that pool, so the release run takes it to
    COGS / WIP when **that** pool consumes it.
  - Lots: transfers create batch rows with the same batch number, so the receipt's units still on hand are that batch
    number's quantity at every location (at most the receipt quantity); each such batch row's unit cost rises by the
    charge per received unit. The consumed rest is classified by the lot's own outbound rows.
  - Serials: unchanged (the serial's `receiptRef` follows it).
- **Targets and accounts.** on hand → stock (STANDARD: 1396); `wip` → WIP of the order (`targetRef`) — also for an
  order completed since: the CM-10 cost adjustment run cascades it to the order's output (F7). Cancelled orders →
  COGS. scrap and negative stock → COGS (unchanged classification). `return` → return policy. Parked → 1396.
- **Returns policy** `costing.landedCost.returnPolicy` (owner decision D2: default `expense`):
  `expense` and `defect_claim` → Dr 5976 *Landed Cost on Returned Goods* (mapping `landed_cost_returns`). Under
  IAS 37 a claim receivable is recognised only once the claim is accepted, so `defect_claim` expenses now.
  `recover_from_supplier` → parked in 1396 awaiting the supplier's credit; it is cleared through the exception
  workspace (write-off to the receivable or expense account) — no receivable is recognised before the credit exists.
- **STANDARD adjunct after a later transfer.** `CostLineageService.release` used to keep a transferred share at the
  source pool for ever. It now moves it (same 1396, no journal) to an open adjustment at the destination pool(s) of the
  transfer lineage, anchored there; the parent's carried amount is reduced by what moved. Unpaired transfers keep the
  old behaviour. (Shared engine change: required by the plan's STANDARD acceptance test.)
- **Accruals.** `LandedCostAccrualService.accrue` uses `LineageCapitalisation.spreadFollowing` (same engine); clearing
  and reversal skip the followed `transfer` / `in_transit` lines because each is its own child adjustment. The quarry
  and CM-10 callers of `LineageCapitalisation.spread` are unchanged.
- **Reversal mirrors the posting.** The voucher entry is reversed exactly. For each AVCO pool share, the part still on
  hand now — followed from its value-only row through any transfer since — is taken out of the pool where it is; the
  part consumed since is reclassed (Dr stock / Cr COGS, WIP or 5976). STANDARD and parked adjuncts unwind their open
  amount; their released part is reclassed to COGS. Lots unwind per batch number. Refused when a WIP share was already
  cascaded by a CM-10 run (`reverse that run first`) or a parked share was resolved (`post a credit voucher instead`),
  and while a credit voucher references the voucher.
- **Credit vouchers.** `voucherType = 'credit'`, `originalVoucherId`, charges with negative amounts and
  `creditOfChargeId` (Σ credits ≤ the original charge). They copy the original's receipts, carry no tax and no
  generated bill (the supplier's credit note goes through payables against clearing), are exempt from the duplicate
  check, cannot be edited, and run through the same engine with the sign reversed.
- **Exception workspace** (`landed_cost:resolve_lineage`, audited): *assign* the receiving `transfer_in` row (the
  link is recorded for the quantity still free on both sides, and the parked amount is distributed from that row:
  Dr targets / Cr 1396), or *write off* to an account with a reason (Dr account / Cr 1396).
- **Rounding (D4).** Shares at 2 dp; the calculator puts the residual on on-hand, else on the largest consumption;
  transfer splits give the residual to the open remainder or the last link. Σ = charge exactly (golden test).

## Findings

| # | Finding | Decision |
|---|---|---|
| G1 | `PaymentService` posted no realised FX for any foreign-currency document | Fixed in PC-0 (ADR-S054); `realisedFxOnSettlement` enabled |
| G2 | Foreign-currency posting with no configured rate rolls back the whole voucher (`CurrencyService.getRate` throws inside the transaction); G3: a configured table rate overrides the document's own rate | Fixed in PC-0 (ADR-S054, owner decision D-PC1); case 6 enabled |
| G4 | Reversal guard missed fully paid bills (status no longer `posted`) | **Fixed in LC-0**: any non-cancelled bill with `amountPaid > 0` blocks the reversal |
| F2, F5–F7 | AVCO / lot landed cost ignores lineage (transfers, WIP, returns, closed orders) | **Fixed in LC-1** |

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

## Messages (verbatim, LC-1)

- `Only a posted landed cost voucher can be credited`
- `Charge <label>: credit <amount> exceeds the uncredited amount <open>`
- `A credit voucher cannot be edited; cancel it and credit the voucher again`
- `Credit voucher <code> references this voucher; reverse or cancel it first`
- `Part of this landed cost was cascaded from WIP by a cost adjustment run; reverse that run first`
- `Part of this landed cost was resolved from a lineage exception; post a credit voucher instead`
- `Lineage exception not found`, `Only an open lineage exception can be resolved`
- `The exception's stock movement is not a transfer out`
- `The destination must be a transfer in of the same product and variant`
- `Nothing left to link between these two movements`
- `Cannot capitalise into an empty stock pool`

## API (LC-1, additive)

- `POST /procurement/landed-cost-vouchers/{id}/credit` `{date?, notes?, charges: [{chargeId, amount, supplierInvoiceRef?}]}`
  (`landed_cost:create`, idempotent). Voucher responses include `voucherType` and `originalVoucherId`; charges
  `creditOfChargeId`; allocations `disposition` (amount per target).
- `GET /inventory/lineage-exceptions?status=` (`landed_cost:read`), `POST …/{id}/assign {destinationLedgerId, reason}`,
  `POST …/{id}/write-off {accountId, reason}` (`landed_cost:resolve_lineage`, granted to Administrator, idempotent).
- Setting `costing.landedCost.returnPolicy`.

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

LC-1 enabled the production WIP and transfer cases. `LandedCostLineageIT` adds: multi-hop with sales at both ends
and a reversal; a transfer after posting followed by the reversal; unpaired → assign and → write-off (403 without
the permission); an injected cycle; both return policies; STANDARD adjunct released at the destination; lot transfer;
credit voucher with its guards; a completed production order; an accrual after a transfer.
`AvcoLandedMathTest` (pure JUnit): the negative-stock cap (100 available, issue 120 → 100), a full sequence with a
partial transfer and a later receipt, a layer that settled negative stock, the epoch reset and the rounding rule.

## Deviations from the plan

Fixtures are own warehouses, suppliers and products inside the demo organisation, with every assertion a delta or
scoped to them. A whole new organisation per IT would need the full seed of accounts and mappings. No seed or demo
balance can make a scenario pass.
