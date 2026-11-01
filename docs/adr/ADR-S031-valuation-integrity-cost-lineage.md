# ADR-S031 — Inventory valuation authority, cost lineage, revaluation and NRV (Cost Management CM-1)

- Status: accepted (CM-1 built 2026-11-01)
- Plan: `docs/roadmap/cost-management-plan.md` (rev 3), AD-1, AD-2, AD-2a, AD-2b, AD-9, C0
- Migration: `V224__cm1_valuation_integrity.sql`
- Extension: no NestJS counterpart.

## Context

Before CM-1, cost changes that move no quantity were missing from the stock ledger:

- landed cost layer bumps
- standard cost activation
- costing-method migration
- the material ledger

The GL changed but the stock ledger did not, so the historical valuation and the GL tie-out by source drifted.

Landed cost on a STANDARD item went entirely to cost of sales, even when the goods were still on hand. That contradicts
IAS 2.21: a standard approximates actual cost, so price differences on stock still held belong in inventory.

There was no general revaluation document, and no IAS 2.28–33 write-down to net realisable value.

## Decision

### 1. Three-layer model (C0)

1. Operational capture records quantities and rates. It never posts.
2. Inventory and product costing decides what is capitalisable and holds unit costs and lineage. The `inventory` module
   is the only place that changes stock value.
3. Financial accounting holds the actual amounts.

`CostLineageService` (inventory) is the valuation API every other module uses for value-only changes.

### 2. Value-only stock ledger rows (AD-1, AD-9)

- New `StockMoveType` value `cost_adjustment`. The row has `quantityChange = 0` and a **signed** `totalValue`.
  - The non-negative value check now applies only to quantity movements.
  - `referenceType` / `referenceId` equal the GL entry's `sourceType` / `sourceId`. Reversals use `reversal` plus the
    original journal entry id. So `GET /reports/inventory/gl-tieout` reconciles **by source**.
- Rows are written by:
  - landed cost (voucher and legacy): capitalised share; on voucher reversal −capitalised (`reversal`) and + the share
    sold since (`landed_cost_voucher_reversal`)
  - standard cost activation, per quant (`standard_cost`)
  - costing-method migration, per quant (`cost_policy`)
  - material-ledger post/reverse, spread over the product's quants
  - revaluation and NRV documents
  - the adjunct release run
- `InventoryLedger.valuationComponent` (`price_difference` | `nrv_allowance`) marks amounts carried *beside* the quant's
  unit cost. The current valuation adds them. The inventory control accounts used by the tie-out include the mappings
  `inventory_price_difference` (1396) and `inventory_nrv_allowance` (1397).

### 3. Lineage (AD-2, AD-2a)

The stock ledger **is** the event chain of each pool (product, variant, location):

- a receipt row is a cost layer, and its `unitCost` is never rewritten
- the outbound rows after it are its consumptions

This is a deviation from the plan, which proposed separate `InventoryCostLayer` / `CostLayerConsumption` tables. They
would duplicate the ledger and need a second write path in every stock movement. The plan's guarantees still hold:

- the original cost is preserved
- consumption relationships come from the ledger
- adjustments are recorded separately

`AvcoLineageCalculator` (pure) distributes an adjustment `A` to a layer received when the pool held `Q₀`:

- Units that only filled negative stock → `negative_stock`.
- Each outbound movement of `q` from a pool of `Q` gets `A·S·q/Q`, then `S ← S·(1 − q/Q)`.
- The survivor `A·S_now` is on hand.
- An empty pool resets the epoch, so the layer is fully consumed.

The shares telescope, so Σ = A exactly. Rounding is to cents, and the remainder goes to on hand (or to the largest
consumption when nothing is left).

Targets:

| Movement | Target |
|---|---|
| `transfer_out` | stays in inventory |
| waste / expiry / internal use | scrap |
| `production_consume` into an open order | WIP |
| everything else | cost of sales |

`CostValueAdjustment` and `CostValueAdjustmentLine` record every adjustment: its source, layer, amount, on-hand and
consumed split, and one line per consumption. A unique `(adjustment, consumption, target)` key means a re-run is a
no-op. An adjustment distributes only its own amount and never redistributes an earlier one.

### 4. STANDARD price differences (AD-2b)

A landed charge on a STANDARD receipt is split over the receipt's lineage:

| Where the units are | Posting |
|---|---|
| On hand (and transferred) | Dr **1396 Inventory Price Differences** (adjunct) |
| Consumed by an open production order | Dr WIP |
| Consumed otherwise | Dr cost of sales |

- The standard unit cost never changes.
- The adjunct stays **open** on the adjustment, anchored at the pool's last ledger row.
- `POST /inventory/price-differences/release` releases the share consumed since the anchor (same survival rule) to cost
  of sales or WIP, re-anchors, and posts one journal entry.
- Reversing the voucher removes what is still open. The released part stays in cost of sales.

### 5. Revaluation and NRV documents

`/inventory/revaluations`, draft → posted → reversed:

- **revaluation**: sets the unit cost of AVCO / FIFO stock at a location. Dr/Cr the stock account against 5968
  Inventory Revaluation (or the document's account).
  - STANDARD is refused: activate a new standard instead.
  - Serials are refused.
  - Reversal is refused once stock has moved.
- **nrv** (IAS 2.28–33): the allowance is **remeasured** to max(0, carrying − NRV) × quantity.
  - The carrying amount of a STANDARD item includes its open price difference.
  - A larger gap writes down: Dr 5975 / Cr 1397. A recovery reverses, never beyond zero (IAS 2.33).
  - The allowance is released to cost of sales as the goods are consumed, by the same release run.
  - Reversal is refused once part of it has been released.
- Posting and reversal require an `Idempotency-Key`.
- Permissions: `inventory_valuation:read|manage|post`.

### 6. Other fixes

- A costing-method migration posts its rounding at the policy's effective date, not "now".

## Routes

| Verb | Path | Permission |
|---|---|---|
| GET | `/inventory/cost-layers/:ledgerId/trace` | `inventory_valuation:read` |
| GET | `/inventory/cost-layers/:ledgerId/distribution?amount=` (preview) | `inventory_valuation:read` |
| GET | `/inventory/price-differences` (open adjuncts) | `inventory_valuation:read` |
| POST | `/inventory/price-differences/release` (Idempotency-Key) | `inventory_valuation:post` |
| GET / POST | `/inventory/revaluations` (`?kind=revaluation\|nrv`) | read / `inventory_valuation:manage` |
| GET / DELETE | `/inventory/revaluations/:id` (delete: draft only) | read / manage |
| POST | `/inventory/revaluations/:id/post`, `/reverse` (Idempotency-Key) | `inventory_valuation:post` |

## Deferred, with the reason

| Item | Deferred to | Reason |
|---|---|---|
| Switching the **AVCO / FIFO landed-cost** on-hand/consumed split from today's "min(on hand, received)" rule to the survival-factor rule | Owner approval | Changes the split of existing behaviour (the total is unchanged). The calculator and the `distribution` preview exist so the two can be compared on real data |
| Price-difference shares of **completed** production orders going to an FG adjunct, and multi-level roll-up | CM-10 material-ledger rework | CM-1 sends them to cost of sales |
| A transferred share following the goods to the destination pool | CM-10 | Re-anchored at the source pool for now; company total correct |
| ArchUnit rule "only inventory writes stock cost columns" | CM-6 | `LandedCostLayers` (procurement) still updates quant / lot / serial costs directly. It moves behind the inventory API with the landed-cost completion. SQL strings are not ArchUnit-visible either |
| Branch / location GL accounts | CM-2 / CM-7 | |
| Posting date of inter-location transfers | CM-7 | Still "now" |

## Tests

- `AvcoLineageCalculatorTest` (5): pro-rata depletion, epoch reset, negative stock, 500 randomised exact-sum runs,
  targets.
- `ValuationIntegrityIT` (5), each asserting that `gl-tieout` and `valuationVsLedger` stay within 0.05:
  - lineage preview and trace, plus tenant isolation
  - revaluation post/reverse, Idempotency-Key required, refused after movement, STANDARD refused
  - NRV write-down, recovery capped at zero, re-write-down, release on sale, reversal refused after release
  - STANDARD landed cost: 60 to the adjunct, 20 to COGS, release half, voucher reversal
  - authorization matrix
- Added to `accountingRegression` through `com.inventoryerp.costing.*`.
