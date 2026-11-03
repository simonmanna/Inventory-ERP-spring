# PC-1 — Valuation correctness: implementation plan

- Status: **plan accepted by the owner (2026-11-03).** WP1 done (ADR-S055). WP2 (transfer lineage) was built in parallel by
  LC-1 (ADR-S053, `V292__lc1_transfer_lineage_returns_credit.sql`) and is not ported; the remaining WPs must be
  re-checked against LC-0..LC-7 before work starts.
- Authorised: 2026-11-03 (owner): PC-1 start and decision V1.
- Roadmap: [`costing-production-ready-plan.md`](../roadmap/costing-production-ready-plan.md), PC-1
  ([`landed-cost-plan.md`](../roadmap/landed-cost-plan.md) LC-1 is part of it).
- Evidence base:
  - [`costing-gl-trace.md`](../audits/costing-gl-trace.md)
  - [`landed-cost-implementation-map.md`](../audits/landed-cost-implementation-map.md) (findings F2, F5–F9)
  - [`pc0-accounting-baseline.md`](../audits/pc0-accounting-baseline.md)
- ADR: **ADR-S055** (valuation correctness), written before code.

## 1. Goal

Every late cost follows the goods it belongs to:
- across AVCO, FIFO / lot, serial and STANDARD;
- through transfers (multi-hop, split, in transit), returns, scrap, negative stock and production (WIP → finished goods);
- for every late-cost source: landed cost, landed accrual, cost-adjustment run, absorption settlement, distribution
  freight, quarry, revaluation.

This must be proven with independently computed figures and exact accounting.

## 2. Where the engine is today (from code)

There is **one** lineage engine. All late-cost families call it:

| Caller | Entry point |
|---|---|
| Landed voucher / legacy landed cost | `LandedCostLayers.apply` → `bumpAverage` (AVCO), lot path, serial path, `standardSplit` → `CostLineageService.distribute` |
| Landed accrual, cost-adjustment run, distribution freight, quarry | `LineageCapitalisation.spread` → `distribute` / `byTarget` → `accountFor` |
| Absorption settlement | `AbsorptionSettlementService` → `distribute` / `byTarget` |
| Adjunct / NRV release | `CostLineageService.release` → `AvcoLineageCalculator.distribute` from the anchor |
| Pure maths | `AvcoLineageCalculator` (pro-rata survival; `min(q, pool)` cap; epoch reset) |

**Defects found (each gets a test that fails first, then the fix):**

| # | Defect | Effect |
|---|---|---|
| F5 | AVCO landed cost uses `min(pool qty, receipt qty)` (`bumpAverage`), not lineage | Later receipts and transfers distort the on-hand share; consumption by an open order goes to COGS, not WIP |
| F2 | Lot landed cost bumps only the receipt-location batch; transfer ledger rows carry `batchId = NULL` | A transferred lot's share goes to COGS |
| T1 | `transfer` target → 1396 adjunct anchored at the **source** pool (`release` re-anchors at the source) | The adjunct is released by the *source's* later consumption, not by the goods' own consumption at the destination |
| F6 | `return_to_supplier` → `cogs` (`AvcoLineageCalculator.target` default) | Late cost on returned goods lands in COGS |
| F7 | `production_consume` of a **closed** order → `cogs` | A late cost on materials of a completed order skips its finished goods |
| **V1** | `release` posts the consumed **NRV allowance** to NRV expense (`nrvExpenseAccount`); ADR-S031 says cost of sales | COGS overstated, NRV expense understated (owner-approved fix) |
| **V2** (new) | `release` sends consumed adjunct shares of **scrap** moves to COGS, while `LineageCapitalisation.accountFor` sends scrap to `stock_adjustment_expense` | The same late cost is classified differently depending on whether it is posted now or released later |
| **V3** (new) | `release` maps `negative_stock` / pool-empty remainders to COGS without a stated policy | Policy gap; documented and tested |

Serials are already location-independent (`InventorySerial.receiptRef`, status `in_stock`), so a transferred serial keeps
its increment. This is **verified by tests, not changed**.

## 3. Work packages, in order

Each work package follows the same loop: failing test, fix, exact-classification test green, `BooksGuard` green. Then
the next.

### WP1 — V1 + V2: release classification (authorised) — ✅ done

> Done 2026-11-03: `CostLineageService.consumptionAccount` is the single target → account function, used by
> `LineageCapitalisation.accountFor` and `release` ([ADR-S055](../adr/ADR-S055-lineage-target-classification.md)).
> Tests: `CostingAuditIT` `nrvRelease`, `nrvRecoveryAfterRelease`, `nrvReversalRefusedAfterRelease`,
> `nrvScrapRelease` (were red before the fix: V1, V2); `LandedCostAuditIT` `standardAdjunctReleasedAsSold`,
> `standardAdjunctScrapRelease` (scrap red before: V2). Posted releases are untouched.

- One shared target → account function, used by **both** `LineageCapitalisation.accountFor` and
  `CostLineageService.release`:

  | Target | Price-difference adjunct (1396) | NRV allowance (1397) |
  |---|---|---|
  | consumed by sale (`cogs`) | COGS | **COGS** (V1) |
  | `wip` | WIP | WIP |
  | `scrap` | stock adjustment expense | stock adjustment expense (V2) |
  | `transfer` | stays in inventory | stays in inventory |
  | `return` | return policy (WP5) | return policy (WP5) |
  | `negative_stock` | COGS (V3, stated in ADR-S055) | COGS |

- **History preserved:** posted releases are never re-posted. The change applies to release runs posted after the fix.
  ADR-S055 records the date and the commit.
- **Tests** (`CostingAuditIT`, exact):
  1. NRV write-down → partial sale → release: allowance → COGS.
  2. Second sale → second release.
  3. Full sale → the whole allowance released.
  4. Recovery after a partial release: the remeasured allowance is never below the released state; IAS 2.33 cap.
  5. Reversal refused after a release (existing rule), and a new document instead.
  6. Scrap after a write-down → stock adjustment expense.
  7. STANDARD adjunct counterparts of 1–3 and 6.
- Re-enable `CostingAuditIT.nrvRelease`.

### WP2 — Transfer lineage (D1)

- **Migration V292:**
  - `InventoryTransferLineage` (id, org, transferId, sourceLedgerId, destinationLedgerId, quantity, createdAt), unique
    (source, destination), RLS.
  - Backfilled **exactly** from `ledgerCode` pairs (`transfer_out` ↔ `transfer_in`; one pair per move,
    `StockService.transfer:855`).
  - `LineageException` (id, org, kind, ledgerId, detail, status, resolvedBy/At, resolution) with RLS.
- **Writer:** `StockService.transfer` inserts the lineage row in the same transaction. This covers immediate moves,
  dispatch to transit, each partial receipt and recalls.
- **Distribution:** `CostLineageService` gains `follow(...)`. A `transfer` share is redistributed over the
  **destination** pool from the paired `transfer_in` row: `distribute(destinationLayer, share)`.
  - Recursive, multi-hop (A → transit → B → transit → C).
  - Cycle detection with a visited set, plus a depth guard of 20. A cycle or a missing pair raises a
    `LineageException`, and the share is parked in 1396 with an exception flag. Never silently wrong.
  - The resolved targets at the destination are the ordinary ones: on hand → that pool, WIP, COGS, scrap, return.
  - `byTarget` returns resolved targets, so **every caller** gets the fix at once: landed cost, accrual, cost
    adjustment, settlement, freight, quarry.
- **Anchoring:** an adjunct share that stays open is anchored at the pool where the goods now are (fixes T1). Release
  then follows that pool's consumption.
- **Lineage identity:** `CostValueAdjustmentLine` gains `rootAdjustmentId` and `sourceReceiptLedgerId`, with a unique
  key per (root, consumption ledger row), so no share is distributed twice.

### WP3 — AVCO, lot and serial through lineage (F5, F2)

- **AVCO:** `LandedCostLayers.applyLayer` uses `distribute` (+ WP2 follow) instead of `bumpAverage` whenever the
  receipt has a ledger row.
  - The on-hand share at each pool raises **that pool's** running average: `A_new = (V + share) / Q`, only when
    `Q > 0`.
  - With `Q ≤ 0` the share goes to the negative-stock policy (V3).
  - `bumpAverage` remains only for the legacy Nest per-receipt route (owner constraint) and for receipts without a
    ledger row.
- **Lots:** the destination lot is the destination batch with the same `batchNumber`, created by the transfer. It is
  located through the paired `transfer_in` row plus `batchNumber`. The lot's `unitCost` is raised by its on-hand share.
- **Serials:** behaviour unchanged; tests only.
- **Reversal (`LandedCostLayers.remove`):** removes exactly what each recorded `CostValueAdjustmentLine` put where,
  destination pools included. It no longer recomputes from today's quantities.

### WP4 — Production: WIP → finished goods (F7)

- `AvcoLineageCalculator.target`: `production_consume` of a closed order gets a new target, `output`.
- `LineageCapitalisation` and `LandedCostLayers` route `output` shares through the **existing CM-10 cascade**
  (`CostAdjustmentService`: spread over the order's output receipt layers, through their lineage). No new engine.
- Finished goods sold → COGS. Finished goods on hand → their pool. Cascades exactly once (existing unique index).

### WP5 — Returns (F6) and the return-to-supplier guard

- New target `return`. `CostValueAdjustmentLine_target` check widened (V292).
- New setting `costing.returnPolicy`:
  - `expense` (default, owner D2) → mapped `landed_cost_returned_goods` expense.
  - `recover_from_supplier` → parked in 1396, flagged `awaiting_recovery`; no receivable until the supplier credit note
    posts.
  - `defect_claim` → expense; a receivable only when the claim is accepted.
- **Return-to-supplier guard:** `ProcurementIT` goes under `BooksGuard`. New exact `CostingAuditIT` cases cover a
  debit-note return before and after a late cost, for each policy.

### WP6 — Negative stock (V3) and conservation

- Golden cases:
  - Issue beyond the pool: the charge is never over-allocated (100 available, issue 120 → 100).
  - A later receipt settling negative stock never redistributes an earlier charge (a `settledNegativeLedgerId`
    marker).
- **Conservation checks** (test helper used by every PC-1 IT, per pool):
  - **Quantity:** Σ `InventoryLedger.quantityChange` = `StockItem.quantity` (and Σ lot quantities for lots).
  - **Cost:** sub-ledger value (quant × average, Σ lots, + value-only rows) = Σ ledger values.
  - **Lineage identity:** per adjustment, Σ `CostValueAdjustmentLine.amount` = adjustment amount exactly (6 dp).
  - **Reconciliation:** tie-out unchanged (`BooksGuard`), plus exact classification.

### WP7 — Credit vouchers (late landed-cost adjustments)

- `voucherType = 'credit'`: references the original voucher and carries negative charges. It runs through the same
  engine with the sign reversed (V292 widens the checks).
- A supplier credit note credits clearing.
- Tests: credit after a partial sale; after a transfer; after production.

## 4. AVCO golden test (independently computed)

`AvcoLandedMathTest`, pure JUnit with no Spring. Figures are computed **by hand in the test source**, not by the
engine. Scenario:
1. R1 100 @ 10 into A.
2. Issue 30.
3. R2 50 @ 12.
4. Transfer 40 to B.
5. Sale 10 at B.
6. Issue 150 at A, taking A negative.
7. R3 settles A.
8. Late charge 1,000 on R1.

The test asserts:
- Every share (consumption, transfer → B on hand / B sale, negative stock).
- The new averages at A and B.
- The identity Σ = 1,000 exactly (6 dp).
- The deterministic residual (the calculator rule: on-hand, else the largest consumption; ADR-S053 D4).

Then `LandedCostEndToEndIT` posts the same sequence through the APIs and must hit the same figures.

## 5. Exit criteria (all must be demonstrated in the completion report)

1. The WP1–WP7 tests are green with **exact per-account classification**. The LC-0 `@Disabled` cases (production WIP,
   transfer) and `CostingAuditIT.nrvRelease` are enabled and green.
2. The `AvcoLandedMathTest` golden figures match the engine. The end-to-end IT matches the golden figures.
3. The conservation, identity and reconciliation checks hold in every PC-1 IT.
4. `BooksGuard` covers the accounting regression suites **plus `ProcurementIT`**, all green.
5. Full `./gradlew build`: no new failures against `main`. `BackupJobsIT` / `FleetIncidentsIT` stay visible.
   Formatting passes; JaCoCo ≥ 0.80 on every touched package.
6. ADR-S055 written. The GL trace rows for transfer / return / production / negative stock move from ⚠ to ✅.
   `PORT_STATUS` updated. Completion report with test evidence.

## 6. Boundaries and risks

- **No new engine.** All changes go into `CostLineageService`, `AvcoLineageCalculator`, `LineageCapitalisation`,
  `LandedCostLayers` and `StockService.transfer`.
- **Legacy Nest per-receipt landed cost:** keeps `bumpAverage` (unchanged figures). Unifying it is a separate owner
  decision.
- **History:** no posted journal, adjustment or release is rewritten. The backfill only adds lineage rows. Old
  adjustments keep their recorded targets; reversals use what was recorded.
- **Behaviour changes, flagged in ADR-S055 and the report:**
  - Late costs on transferred goods now reach the destination instead of COGS or the adjunct.
  - Closed-order consumption reaches finished goods.
  - NRV / scrap releases are classified per WP1.
  - `LandedCostVoucherIT` figures may change only where a transfer or a later receipt is involved; each change is
    listed.
- **Performance:** recursion over transfer hops adds a query per hop. It is bounded by the depth guard. Indexes on
  `InventoryTransferLineage(sourceLedgerId)` and `(destinationLedgerId)`. Load testing is PC-8.
- **Out of scope:** D-PC3 (bill tax lines, on hold), material-ledger rework beyond reuse, PC-2 controls.

## 7. Migrations

V292 contains:
- `InventoryTransferLineage` + exact backfill.
- `LineageException`.
- On `CostValueAdjustmentLine`: `rootAdjustmentId`, `sourceReceiptLedgerId`, and targets widened with `return` and
  `output`.
- `adjustmentType` widened.
- `LandedCost.voucherType` + `credit`, and the charge amount sign rule.
- Settings `costing.returnPolicy` and the mapping `landed_cost_returned_goods`.

V293 is reserved if needed.
