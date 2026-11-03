# ADR-S053 — Landed cost to ERP grade (LC-0 … LC-7)

- Status: accepted in principle; built phase by phase. **LC-0 to LC-3 (the gates) done.**
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

### LC-2 — financial controls (V293)

- **Approval.** `costing.landedCost.allowDirectApproval` (default false): `approve` accepts only `submitted`
  (`Only a submitted landed cost voucher can be approved; submit it first`). `ApprovalLimitService.assertWithin(
  "landed_cost_voucher", |total|, …)` applies the approver's limit. `reopen` (submitted / approved → draft) clears the
  approval.
- **`approvedHash`**: SHA-256 of canonical JSON (sorted keys) of every money driver — shipment, cost centre, each
  receipt's stock lines (line, product, variant, location, lot, quantity, value, weight, volume, unit) and per charge
  component, kind, treatment, basis, lines, manual split, amount, base, tax, recoverability, tax code, currency, rate,
  supplier, invoice, bill mode, bill, accounts, cost centre. Any change → `Voucher changed since approval; reopen and
  re-approve`. The unreviewed-tax guard (LC-0) is checked first.
- **`approvedDisposition`** per target: `stock:<product>:<location>` (STANDARD: `adjunct:`), `parked:<product>:<location>`,
  `wip:<order>`, `return:<product>`, `scrap:<product>`, `cogs:<product>`. At posting the disposition is recomputed from
  current lineage (that is what posts) and compared target by target. **OR rule:** a change above
  `costing.landedCost.reapprovalTolerancePct` (5) **or** `costing.landedCost.reapprovalToleranceAmount` (1,000) refuses
  with `Inventory moved since approval (<target>: approved <a>, now <b>); reopen and re-approve`. A target new or gone
  since approval has no percentage: only the amount applies. 0 = any change. `postedDisposition` is stored, and the
  audit records approved, posted and their difference.
- **Duplicate invoices.** `procurement.invoiceRef.normalization`: `strict` (default; upper case, trimmed, separators
  space - _ / . removed — `AB-12` and `A-B12` both become `AB12`, a deliberate false positive the override covers) or
  `exact` (upper case, trimmed). **Deviation from the plan (smallest safe correction, standing rule 3):** the plan put
  the partial unique indexes on `LandedCostCharge`, but one supplier invoice legitimately spans several charge lines of
  a voucher (freight + handling). The indexes are on a registry, `LandedCostInvoiceRef` — one row per (voucher,
  supplier, normalised number), `live` while the voucher is not cancelled or reversed:
  `…_normal_uq (org, supplier, refNorm) WHERE duplicateOverrideId IS NULL AND live` and
  `…_override_uq (org, supplier, refNorm, duplicateOverrideId) WHERE duplicateOverrideId IS NOT NULL AND live`.
  The check runs when charges are written (create / update), under the advisory lock on (org, supplier, refNorm); a
  unique violation maps to the same 409, which names the original reference and voucher (`… is already on landed cost
  LC-00012 (entered as AB-12)`). Credit vouchers and linked bills are exempt. Historic duplicates got a migration
  override each (listed).
- **Overrides** (`landed_cost:override_duplicate`, reason required, audited): `POST /procurement/invoice-duplicate-overrides`,
  `GET` (with the voucher that used each), `POST …/{id}/revoke` (unused only). A charge names one with
  `duplicateOverrideId`; it must be approved, unrevoked, same org, supplier and normalised number, and unused; it is
  marked used (`usedByChargeId` = the registry row, unique) in the same transaction. Rewriting a draft frees it.
- **Allocation validation.** Weight / volume bases list every product missing it (`Charge <label> is allocated by
  weight; set the weight of A, B`) — previously a product without weight silently got nothing. A unit with no quantity
  is refused. Quantities are the receipt's stock-ledger quantities, which are already in the base unit. Σ allocated =
  charge base is asserted. `weightUsed`, `volumeUsed` and `uomId` are stored on each allocation row.
- **Engine consolidation.** The voucher basis split uses `AllocationCalculator` (LAST, 6 dp) — identical figures (all
  existing voucher ITs unchanged). The calculator moved to `com.inventoryerp.shared.costing` (it is pure) because
  procurement cannot depend on costing (costing already depends on procurement).

### LC-3 — reconciliation and trace

`GET /procurement/landed-cost-reports/reconciliation` (`landed_cost:read`) returns six rows (sub-ledger, GL,
difference, drill-down), the classification and location mismatches, the open lineage exceptions and `clean`:

| Row | Sub-ledger | GL |
|---|---|---|
| 2155 | Charges of posted vouchers / credits credited to clearing whose supplier bill is not booked (no linked or generated bill, or it was cancelled) | 2155 balance |
| 2156 | Open accruals | 2156 balance |
| 1396 | Σ open `price_difference` adjuncts (STANDARD landed cost, parked lineage shares, recoveries, accruals, material ledger), drill-down per source and parked flag | 1396 balance |
| stock | Inventory valuation (the GL tie-out), landed-cost sources listed | inventory accounts |
| cogs | Σ disposition `cogs` of posted vouchers | COGS lines of their posting entries |
| wip | Σ disposition `wip` of posted vouchers − shares already cascaded by CM-10; drill-down: open shares per order | WIP lines of their posting entries − the cascaded amounts |

- **Classification:** per posted voucher, each landed account (stock / 1396 / WIP / COGS / 5976) the dispositions imply
  vs its journal lines; listed even when the entry balances.
- **Location:** per posted voucher, product and location, the posting's value-only stock rows vs the disposition's
  stock / adjunct / parked targets.
- **2155 note:** a supplier invoice booked in payables against clearing without being linked to its charge shows as a
  difference with the charge still listed as outstanding — that is the signal to link it.
- **Trace:** `GET /procurement/landed-cost-reports/trace?voucherId|chargeId|journalLineId` — voucher → charges →
  allocations (receipt, line, product, disposition) → adjustments per pool with parent links (transfer hops) and their
  lines with the consumption movement (type, reference, location: sale, production order, return) → the journal
  lines of the posting, reversal and reclass entries. Every hop filtered by organisation; another organisation's id is
  a 404 (tested).
- **Period close:** the checklist gets `landed_cost_lineage` (non-blocking): open lineage exceptions. The accounting
  module cannot depend on procurement, so the full reconciliation stays on its own page.
- **Final acceptance** `LandedCostEndToEndIT`: import, two-supplier voucher (generated bill + bill to enter), transfer
  to the plant, issue to an open order, a late charge while goods are in W1, at the plant and in WIP (exact split),
  sales at both places, the order completed, a supplier credit voucher, reconciliation unchanged (all six rows), no
  classification or location mismatch, trace through the transfer hop to the order's WIP and from a journal line,
  reversal of the late charge (every target unwinds; the units sold since keep their share in COGS through the
  reclass, averages back exactly), and a corrected re-post. **Deviation:** the plan's FG-at-depot leg needs the CM-10
  cascade run, and a run cannot be reversed once part of what it capitalised was released; the reversal therefore
  happens while the completed order's WIP share is still pending cascade. A voucher whose WIP share was cascaded is
  refused (`reverse that run first`) — tested in LC-1.

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

## API (LC-2, additive)

- `POST /procurement/landed-cost-vouchers/{id}/reopen` (`landed_cost:create`).
- Charge lines accept `duplicateOverrideId`; voucher responses include `approvedHash`, `approvedDisposition`,
  `postedDisposition`; charges `supplierInvoiceRefNorm`, `live`, `duplicateOverrideId`; allocations `disposition`,
  `weightUsed`, `volumeUsed`, `uomId`.
- `GET|POST /procurement/invoice-duplicate-overrides`, `POST /procurement/invoice-duplicate-overrides/{id}/revoke`.
- Settings `costing.landedCost.allowDirectApproval`, `…reapprovalTolerancePct`, `…reapprovalToleranceAmount`,
  `procurement.invoiceRef.normalization`; permission `landed_cost:override_duplicate`.
- Web: Approve shows on submitted vouchers only; Reopen on submitted / approved.

## API (LC-3, additive)

- `GET /procurement/landed-cost-reports/reconciliation`, `GET /procurement/landed-cost-reports/trace`
  (`landed_cost:read`).
- Period-close checklist item `landed_cost_lineage`.

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
